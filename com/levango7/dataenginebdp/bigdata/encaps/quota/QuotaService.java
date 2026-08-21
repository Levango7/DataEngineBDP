package com.shuqing.bigdata.encaps.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Quota 业务服务。
 *
 * <p>编排 {@link K8sQuotaTranslator} 与 {@link QuotaRepository}，对外提供
 * Quota 的设置、查询、更新、删除能力。所有 K8s 翻译失败均被捕获并反映到
 * Quota 状态流转上，不向调用方抛出 {@link K8sQuotaTranslator.K8sTranslationException}。</p>
 *
 * <p>核心流程：</p>
 * <ul>
 *   <li>{@link #setQuota(Quota)} — 落 DB（SETTING）→ 翻译 K8s → 落 DB（ACTIVE/FAILED）</li>
 *   <li>{@link #updateQuota(Long, Quota)} — 落 DB（UPDATING）→ 更新 K8s → 落 DB（ACTIVE/FAILED）</li>
 *   <li>{@link #deleteQuota(Long)} — 落 DB（DELETING）→ 删 K8s → 落 DB（DELETED）</li>
 *   <li>{@link #listQuotas(Long)} / {@link #getQuota(Long)} — 纯 DB 查询</li>
 *   <li>{@link #getUsage(Long)} — 委托翻译器查询 K8s ResourceQuota 实时用量</li>
 * </ul>
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    /** 默认 CPU 限制 */
    static final String DEFAULT_CPU_LIMIT = "10";
    /** 默认内存限制 */
    static final String DEFAULT_MEMORY_LIMIT = "20Gi";
    /** 默认存储限制 */
    static final String DEFAULT_STORAGE_LIMIT = "100Gi";
    /** 默认 Pod 数量限制 */
    static final String DEFAULT_POD_LIMIT = "100";
    /** 默认 PVC 数量限制 */
    static final String DEFAULT_PVC_LIMIT = "50";
    /** 默认 Service 数量限制 */
    static final String DEFAULT_SERVICE_LIMIT = "20";

    private final QuotaRepository quotaRepository;
    private final K8sQuotaTranslator k8sTranslator;

    public QuotaService(QuotaRepository quotaRepository, K8sQuotaTranslator k8sTranslator) {
        this.quotaRepository = quotaRepository;
        this.k8sTranslator = k8sTranslator;
    }

    /**
     * 设置 Quota：落 DB（SETTING）→ 翻译 K8s → 落 DB（ACTIVE/FAILED）。
     *
     * <p>翻译顺序：ResourceQuota → LimitRange。
     * 任一步骤失败均将 Quota 状态置为 {@link Quota.QuotaStatus#FAILED FAILED}
     * 并保留错误日志（不抛出异常，调用方通过状态判断）。</p>
     *
     * <p>若同一 Workspace 已存在活跃 Quota，将抛出 {@link IllegalStateException}，
     * 调用方应先删除旧 Quota 再设置新 Quota，或使用 {@link #updateQuota(Long, Quota)}。</p>
     *
     * @param req 设置请求（id 由 DB 生成，createdAt/updatedAt 由服务层填充）
     * @return 已落地的 Quota（含 id、status）
     * @throws IllegalStateException 同一 Workspace 已存在活跃 Quota
     */
    public Quota setQuota(Quota req) {
        // 检查同一 Workspace 是否已存在活跃 Quota
        Optional<Quota> existing = quotaRepository.findByWorkspaceId(req.getWorkspaceId());
        if (existing.isPresent() && existing.get().getStatus() == Quota.QuotaStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Active quota already exists for workspace " + req.getWorkspaceId()
                            + ", use updateQuota instead");
        }

        LocalDateTime now = LocalDateTime.now();
        req.setId(null);
        req.setCreatedAt(now);
        req.setUpdatedAt(now);
        req.setStatus(Quota.QuotaStatus.SETTING);

        // 填充默认值
        applyDefaults(req);

        // 先落 DB（SETTING），获取 id
        Quota saved = quotaRepository.save(req);

        try {
            k8sTranslator.createResourceQuota(saved);
            k8sTranslator.createLimitRange(saved);
            saved.setStatus(Quota.QuotaStatus.ACTIVE);
        } catch (K8sQuotaTranslator.K8sTranslationException e) {
            log.error("K8s translation failed for quota {}: {}", saved.getId(), e.getMessage(), e);
            saved.setStatus(Quota.QuotaStatus.FAILED);
        }
        saved.setUpdatedAt(LocalDateTime.now());
        return quotaRepository.save(saved);
    }

    /**
     * 按 ID 获取单个 Quota。
     *
     * @param id Quota ID
     * @return Optional 包装的 Quota
     */
    public Optional<Quota> getQuota(Long id) {
        return quotaRepository.findById(id);
    }

    /**
     * 按 Workspace ID 获取 Quota。
     *
     * @param workspaceId Workspace ID
     * @return Optional 包装的 Quota
     */
    public Optional<Quota> getQuotaByWorkspace(Long workspaceId) {
        return quotaRepository.findByWorkspaceId(workspaceId);
    }

    /**
     * 列出 Quota，可选按租户 ID 过滤。
     *
     * @param tenantId 租户 ID；为 null 时返回全部
     * @return Quota 列表（不会返回 null）
     */
    public List<Quota> listQuotas(Long tenantId) {
        if (tenantId == null) {
            return quotaRepository.findAll();
        }
        return quotaRepository.findByTenantId(tenantId);
    }

    /**
     * 列出 Quota，可选按租户 ID 与 Workspace ID 过滤。
     *
     * @param tenantId    租户 ID（可选）
     * @param workspaceId Workspace ID（可选）
     * @return Quota 列表
     */
    public List<Quota> listQuotas(Long tenantId, Long workspaceId) {
        if (workspaceId != null) {
            return quotaRepository.findAllByWorkspaceId(workspaceId);
        }
        return listQuotas(tenantId);
    }

    /**
     * 更新 Quota：落 DB（UPDATING）→ 更新 K8s → 落 DB（ACTIVE/FAILED）。
     *
     * <p>仅更新配额字段（cpuLimit/memoryLimit/storageLimit/podLimit/pvcLimit/serviceLimit
     * + per-Pod 限制），不更新 workspaceId/tenantId。</p>
     *
     * @param id      Quota ID
     * @param updated 新字段值
     * @return 更新后的 Quota；若 ID 不存在则返回 Optional.empty()
     */
    public Optional<Quota> updateQuota(Long id, Quota updated) {
        Optional<Quota> opt = quotaRepository.findById(id);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        Quota existing = opt.get();
        // 更新可变字段
        if (updated.getCpuLimit() != null) {
            existing.setCpuLimit(updated.getCpuLimit());
        }
        if (updated.getMemoryLimit() != null) {
            existing.setMemoryLimit(updated.getMemoryLimit());
        }
        if (updated.getStorageLimit() != null) {
            existing.setStorageLimit(updated.getStorageLimit());
        }
        if (updated.getPodLimit() != null) {
            existing.setPodLimit(updated.getPodLimit());
        }
        if (updated.getPvcLimit() != null) {
            existing.setPvcLimit(updated.getPvcLimit());
        }
        if (updated.getServiceLimit() != null) {
            existing.setServiceLimit(updated.getServiceLimit());
        }
        if (updated.getMaxCpuPerPod() != null) {
            existing.setMaxCpuPerPod(updated.getMaxCpuPerPod());
        }
        if (updated.getMaxMemoryPerPod() != null) {
            existing.setMaxMemoryPerPod(updated.getMaxMemoryPerPod());
        }
        if (updated.getMinCpuPerPod() != null) {
            existing.setMinCpuPerPod(updated.getMinCpuPerPod());
        }
        if (updated.getMinMemoryPerPod() != null) {
            existing.setMinMemoryPerPod(updated.getMinMemoryPerPod());
        }

        existing.setStatus(Quota.QuotaStatus.UPDATING);
        existing.setUpdatedAt(LocalDateTime.now());
        quotaRepository.save(existing);

        try {
            k8sTranslator.updateResourceQuota(existing);
            k8sTranslator.updateLimitRange(existing);
            existing.setStatus(Quota.QuotaStatus.ACTIVE);
        } catch (K8sQuotaTranslator.K8sTranslationException e) {
            log.error("K8s update failed for quota {}: {}", id, e.getMessage(), e);
            existing.setStatus(Quota.QuotaStatus.FAILED);
        }
        existing.setUpdatedAt(LocalDateTime.now());
        return Optional.of(quotaRepository.save(existing));
    }

    /**
     * 删除 Quota：落 DB（DELETING）→ 删 K8s → 落 DB（DELETED）。
     *
     * <p>K8s 删除失败时仍将 DB 状态置为 DELETED（避免残留），并记录错误日志。</p>
     *
     * @param id Quota ID
     * @return true 表示存在并已删除；false 表示 ID 不存在
     */
    public boolean deleteQuota(Long id) {
        Optional<Quota> opt = quotaRepository.findById(id);
        if (opt.isEmpty()) {
            return false;
        }
        Quota quota = opt.get();
        quota.setStatus(Quota.QuotaStatus.DELETING);
        quota.setUpdatedAt(LocalDateTime.now());
        quotaRepository.save(quota);

        try {
            k8sTranslator.deleteResourceQuota(quota);
        } catch (K8sQuotaTranslator.K8sTranslationException e) {
            log.error("K8s ResourceQuota deletion failed for quota {}: {}", id, e.getMessage(), e);
        }
        try {
            k8sTranslator.deleteLimitRange(quota);
        } catch (K8sQuotaTranslator.K8sTranslationException e) {
            log.error("K8s LimitRange deletion failed for quota {}: {}", id, e.getMessage(), e);
        }

        quota.setStatus(Quota.QuotaStatus.DELETED);
        quota.setUpdatedAt(LocalDateTime.now());
        quotaRepository.save(quota);
        return true;
    }

    /**
     * 查询 Workspace 当前资源用量（已用 / 配额）。
     *
     * @param workspaceId Workspace ID
     * @return 用量信息 {@code {used: Map, hard: Map}}
     */
    public Map<String, Map<String, String>> getUsage(Long workspaceId) {
        return k8sTranslator.getUsage(workspaceId);
    }

    /* ------------------------------ 私有辅助 ------------------------------ */

    /**
     * 为缺失的配额字段填充默认值。
     */
    private void applyDefaults(Quota req) {
        if (req.getCpuLimit() == null || req.getCpuLimit().isBlank()) {
            req.setCpuLimit(DEFAULT_CPU_LIMIT);
        }
        if (req.getMemoryLimit() == null || req.getMemoryLimit().isBlank()) {
            req.setMemoryLimit(DEFAULT_MEMORY_LIMIT);
        }
        if (req.getStorageLimit() == null || req.getStorageLimit().isBlank()) {
            req.setStorageLimit(DEFAULT_STORAGE_LIMIT);
        }
        if (req.getPodLimit() == null || req.getPodLimit().isBlank()) {
            req.setPodLimit(DEFAULT_POD_LIMIT);
        }
        if (req.getPvcLimit() == null || req.getPvcLimit().isBlank()) {
            req.setPvcLimit(DEFAULT_PVC_LIMIT);
        }
        if (req.getServiceLimit() == null || req.getServiceLimit().isBlank()) {
            req.setServiceLimit(DEFAULT_SERVICE_LIMIT);
        }
    }
}