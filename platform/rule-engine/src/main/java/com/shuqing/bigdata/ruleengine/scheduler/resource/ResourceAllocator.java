package com.shuqing.bigdata.ruleengine.scheduler.resource;

import com.shuqing.bigdata.ruleengine.scheduler.config.SchedulerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多租户资源分配器。
 *
 * <p>按租户隔离管理 CPU/内存配额，提供原子分配/释放与配额管理 API。
 * 核心保证：<b>不超卖</b>——任意时刻单租户已用资源不超过其配额上限。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>注册表 {@link ConcurrentHashMap} 保证租户级配额查询并发安全</li>
 *   <li>分配/释放对 {@link ResourceQuota} 实例加 {@code synchronized}，
 *       保证同一租户的 usedCpu/usedMemory 原子更新，避免并发分配超卖</li>
 *   <li>新租户首次分配时自动按 {@code SchedulerProperties.defaultQuota} 初始化配额，
 *       实现"按需注册"</li>
 * </ul>
 *
 * <p>线程模型：分配/释放可能来自多个 worker 线程，{@code synchronized(quota)} 粒度
 * 仅为单租户实例，不影响其他租户并发。</p>
 */
@Slf4j
@Component
public class ResourceAllocator {

    private final ConcurrentHashMap<String, ResourceQuota> quotas = new ConcurrentHashMap<>();
    private final SchedulerProperties properties;

    public ResourceAllocator(SchedulerProperties properties) {
        this.properties = properties;
    }

    /**
     * 为租户设置/更新配额上限；不存在则创建。
     *
     * @param tenantId    租户 ID
     * @param maxCpuCores CPU 上限
     * @param maxMemoryMb 内存上限 MB
     * @return 设置后的配额
     */
    public ResourceQuota setQuota(String tenantId, double maxCpuCores, long maxMemoryMb) {
        ResourceQuota quota = quotas.compute(tenantId, (id, existing) -> {
            if (existing == null) {
                return ResourceQuota.builder()
                        .tenantId(tenantId)
                        .maxCpuCores(maxCpuCores)
                        .maxMemoryMb(maxMemoryMb)
                        .build();
            }
            existing.setMaxCpuCores(maxCpuCores);
            existing.setMaxMemoryMb(maxMemoryMb);
            return existing;
        });
        log.info("资源配额已设置: tenantId={}, maxCpu={}, maxMem={}MB", tenantId, maxCpuCores, maxMemoryMb);
        return quota;
    }

    /**
     * 查询租户配额（含已用量）。
     *
     * @param tenantId 租户 ID
     * @return 配额快照；不存在返回 {@link Optional#empty()}
     */
    public Optional<ResourceQuota> getQuota(String tenantId) {
        return Optional.ofNullable(quotas.get(tenantId));
    }

    /**
     * 列出全部配额。
     *
     * @return 配额集合
     */
    public Collection<ResourceQuota> listAll() {
        return quotas.values();
    }

    /**
     * 尝试为租户分配资源。
     *
     * <p>若租户配额不存在，先按默认配额初始化。分配在 {@code synchronized(quota)} 内完成，
     * 保证不超卖。</p>
     *
     * @param tenantId 租户 ID
     * @param request  资源请求
     * @return 分配成功返回 true；超配返回 false
     */
    public boolean tryAllocate(String tenantId, ResourceRequest request) {
        ResourceQuota quota = quotas.computeIfAbsent(tenantId, this::defaultQuota);
        synchronized (quota) {
            if (!quota.canAllocate(request.getCpuCores(), request.getMemoryMb())) {
                log.debug("资源分配失败(超配): tenantId={}, reqCpu={}, reqMem={}MB, usedCpu={}, usedMem={}MB, maxCpu={}, maxMem={}MB",
                        tenantId, request.getCpuCores(), request.getMemoryMb(),
                        quota.getUsedCpuCores(), quota.getUsedMemoryMb(),
                        quota.getMaxCpuCores(), quota.getMaxMemoryMb());
                return false;
            }
            quota.setUsedCpuCores(quota.getUsedCpuCores() + request.getCpuCores());
            quota.setUsedMemoryMb(quota.getUsedMemoryMb() + request.getMemoryMb());
            return true;
        }
    }

    /**
     * 释放租户资源。下限为 0，避免释放过量导致负数。
     *
     * @param tenantId 租户 ID
     * @param request  资源请求（与分配时一致）
     */
    public void release(String tenantId, ResourceRequest request) {
        ResourceQuota quota = quotas.get(tenantId);
        if (quota == null) {
            return;
        }
        synchronized (quota) {
            quota.setUsedCpuCores(Math.max(0.0, quota.getUsedCpuCores() - request.getCpuCores()));
            quota.setUsedMemoryMb(Math.max(0L, quota.getUsedMemoryMb() - request.getMemoryMb()));
        }
    }

    /**
     * 按默认配额构造新租户配额。
     *
     * @param tenantId 租户 ID
     * @return 默认配额
     */
    private ResourceQuota defaultQuota(String tenantId) {
        SchedulerProperties.DefaultQuota dq = properties.getDefaultQuota();
        return ResourceQuota.builder()
                .tenantId(tenantId)
                .maxCpuCores(dq.getMaxCpuCores())
                .maxMemoryMb(dq.getMaxMemoryMb())
                .build();
    }
}