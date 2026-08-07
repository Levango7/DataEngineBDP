package com.shuqing.bigdata.ruleengine.scheduler.tenant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多租户管理器。
 *
 * <p>维护调度引擎的租户注册表，提供租户的注册、查询、启用/禁用与运行态计数更新。
 * 所有读写基于 {@link ConcurrentHashMap} + {@link AtomicInteger}，保证多 worker 线程并发安全。</p>
 *
 * <p>租户隔离语义：</p>
 * <ul>
 *   <li>未注册租户提交任务：默认拒绝（{@link #isAllowed(String)} 返回 false），
 *       避免未知租户占用资源</li>
 *   <li>已注册但禁用租户：拒绝新任务，已在运行的任务自然完成不中断</li>
 *   <li>并发计数：{@link #incrementActive(String)}/{@link #decrementActive(String)}
 *       使用 CAS 自旋，保证计数准确</li>
 * </ul>
 */
@Slf4j
@Component
public class TenantManager {

    /** 租户注册表：tenantId → TenantInfo */
    private final ConcurrentHashMap<String, TenantInfo> tenants = new ConcurrentHashMap<>();

    /**
     * 注册租户；若已存在则覆盖配置态字段，保留运行态计数。
     *
     * @param info 租户信息
     * @return 注册后的 TenantInfo
     */
    public TenantInfo register(TenantInfo info) {
        if (info.getTenantId() == null || info.getTenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        tenants.compute(info.getTenantId(), (id, existing) -> {
            if (existing == null) {
                return info;
            }
            // 保留运行态计数，更新配置态
            existing.setName(info.getName());
            existing.setMaxConcurrentTasks(info.getMaxConcurrentTasks());
            existing.setEnabled(info.isEnabled());
            return existing;
        });
        log.info("租户已注册/更新: tenantId={}, maxConcurrent={}, enabled={}",
                info.getTenantId(), info.getMaxConcurrentTasks(), info.isEnabled());
        return tenants.get(info.getTenantId());
    }

    /**
     * 查询租户信息。
     *
     * @param tenantId 租户 ID
     * @return 租户信息；不存在返回 {@link Optional#empty()}
     */
    public Optional<TenantInfo> get(String tenantId) {
        return Optional.ofNullable(tenants.get(tenantId));
    }

    /**
     * 列出全部租户。
     *
     * @return 租户集合（快照）
     */
    public Collection<TenantInfo> listAll() {
        return tenants.values();
    }

    /**
     * 判断租户是否允许提交新任务：已注册且 enabled。
     *
     * @param tenantId 租户 ID
     * @return 允许返回 true
     */
    public boolean isAllowed(String tenantId) {
        TenantInfo info = tenants.get(tenantId);
        return info != null && info.isEnabled();
    }

    /**
     * 启用/禁用租户。
     *
     * @param tenantId 租户 ID
     * @param enabled  是否启用
     * @return 操作成功返回 true；租户不存在返回 false
     */
    public boolean setEnabled(String tenantId, boolean enabled) {
        TenantInfo info = tenants.get(tenantId);
        if (info == null) {
            return false;
        }
        info.setEnabled(enabled);
        log.info("租户状态变更: tenantId={}, enabled={}", tenantId, enabled);
        return true;
    }

    /**
     * 移除租户（仅允许在无活跃任务时移除）。
     *
     * @param tenantId 租户 ID
     * @return 移除成功返回 true；不存在或有活跃任务返回 false
     */
    public boolean unregister(String tenantId) {
        TenantInfo info = tenants.get(tenantId);
        if (info == null) {
            return false;
        }
        if (info.getActiveTaskCount() > 0 || info.getQueuedTaskCount() > 0) {
            log.warn("租户存在活跃/排队任务，拒绝移除: tenantId={}, active={}, queued={}",
                    tenantId, info.getActiveTaskCount(), info.getQueuedTaskCount());
            return false;
        }
        return tenants.remove(tenantId) != null;
    }

    /**
     * 原子递增租户活跃任务数。
     *
     * @param tenantId 租户 ID
     */
    public void incrementActive(String tenantId) {
        TenantInfo info = tenants.get(tenantId);
        if (info != null) {
            synchronized (info) {
                info.setActiveTaskCount(info.getActiveTaskCount() + 1);
            }
        }
    }

    /**
     * 原子递减租户活跃任务数，下限为 0。
     *
     * @param tenantId 租户 ID
     */
    public void decrementActive(String tenantId) {
        TenantInfo info = tenants.get(tenantId);
        if (info != null) {
            synchronized (info) {
                info.setActiveTaskCount(Math.max(0, info.getActiveTaskCount() - 1));
            }
        }
    }

    /**
     * 原子递增租户排队任务数。
     *
     * @param tenantId 租户 ID
     */
    public void incrementQueued(String tenantId) {
        TenantInfo info = tenants.get(tenantId);
        if (info != null) {
            synchronized (info) {
                info.setQueuedTaskCount(info.getQueuedTaskCount() + 1);
            }
        }
    }

    /**
     * 原子递减租户排队任务数，下限为 0。
     *
     * @param tenantId 租户 ID
     */
    public void decrementQueued(String tenantId) {
        TenantInfo info = tenants.get(tenantId);
        if (info != null) {
            synchronized (info) {
                info.setQueuedTaskCount(Math.max(0, info.getQueuedTaskCount() - 1));
            }
        }
    }

    /**
     * 获取租户当前活跃任务数。
     *
     * @param tenantId 租户 ID
     * @return 活跃数；租户不存在返回 0
     */
    public int getActiveCount(String tenantId) {
        TenantInfo info = tenants.get(tenantId);
        return info == null ? 0 : info.getActiveTaskCount();
    }
}