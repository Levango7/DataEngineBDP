package com.levango7.dataenginebdp.encaps.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Workspace 业务服务。
 *
 * <p>编排 {@link K8sWorkspaceTranslator} 与 {@link WorkspaceRepository}，对外提供
 * Workspace 的创建、查询、更新、删除能力。所有 K8s 翻译失败均被捕获并反映到
 * Workspace 状态流转上，不向调用方抛出 {@link K8sWorkspaceTranslator.K8sTranslationException}。</p>
 *
 * <p>核心流程：</p>
 * <ul>
 *   <li>{@link #createWorkspace(Workspace)} — 落 DB（CREATING）→ 翻译 K8s → 落 DB（ACTIVE/FAILED）</li>
 *   <li>{@link #deleteWorkspace(Long)} — 落 DB（DELETING）→ 删 K8s Namespace → 落 DB（DELETED）</li>
 *   <li>{@link #listWorkspaces(Long)} / {@link #getWorkspace(Long)} — 纯 DB 查询</li>
 * </ul>
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    /** 默认网络隔离策略 */
    private static final String DEFAULT_NETWORK_POLICY = "tenant-isolated";
    /** 默认资源配额 */
    private static final String DEFAULT_RESOURCE_QUOTA = "cpu=4,memory=8Gi,storage=100Gi";

    private final WorkspaceRepository workspaceRepository;
    private final K8sWorkspaceTranslator k8sTranslator;

    /** K8s mock 模式（app.k8s.mock-enabled=true 时跳过全部 K8s 翻译调用）。 */
    private final boolean k8sMockEnabled;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            K8sWorkspaceTranslator k8sTranslator,
                            @Value("${app.k8s.mock-enabled:false}") boolean k8sMockEnabled) {
        this.workspaceRepository = workspaceRepository;
        this.k8sTranslator = k8sTranslator;
        this.k8sMockEnabled = k8sMockEnabled;
    }

    /**
     * 创建 Workspace：落 DB（CREATING）→ 翻译 K8s → 落 DB（ACTIVE/FAILED）。
     *
     * <p>翻译顺序：Namespace → NetworkPolicy → RBAC → ResourceQuota。
     * 任一步骤失败均将 Workspace 状态置为 {@link Workspace.WorkspaceStatus#DELETED DELETED}
     * 并保留错误日志（不抛出异常，调用方通过状态判断）。</p>
     *
     * @param req 创建请求（id 由 DB 生成，createdAt/updatedAt 由服务层填充）
     * @return 已落地的 Workspace（含 id、namespace、status）
     */
    public Workspace createWorkspace(Workspace req) {
        LocalDateTime now = LocalDateTime.now();
        req.setId(null);
        req.setCreatedAt(now);
        req.setUpdatedAt(now);
        req.setStatus(Workspace.WorkspaceStatus.CREATING);

        // 填充默认值
        if (req.getNamespace() == null || req.getNamespace().isBlank()) {
            req.setNamespace(generateNamespaceName(req));
        }
        if (req.getNetworkPolicy() == null || req.getNetworkPolicy().isBlank()) {
            req.setNetworkPolicy(DEFAULT_NETWORK_POLICY);
        }
        if (req.getResourceQuota() == null || req.getResourceQuota().isBlank()) {
            req.setResourceQuota(DEFAULT_RESOURCE_QUOTA);
        }

        // 先落 DB（CREATING），获取 id 用于 Namespace 标签
        Workspace saved = workspaceRepository.save(req);

        try {
            if (k8sMockEnabled) {
                // mock 模式下 K8sClientConfig 返回的是未连接真实集群的懒加载 client，
                // 调用翻译会阻塞至读超时；此处直接跳过并视为翻译成功
                log.info("K8s mock 模式：跳过 workspace {} 的 K8s 翻译", saved.getId());
            } else {
                k8sTranslator.createNamespace(saved);
                k8sTranslator.createNetworkPolicy(saved);
                k8sTranslator.createRBAC(saved);
                k8sTranslator.createResourceQuota(saved);
            }
            saved.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        } catch (K8sWorkspaceTranslator.K8sTranslationException e) {
            log.error("K8s translation failed for workspace {}: {}", saved.getId(), e.getMessage(), e);
            saved.setStatus(Workspace.WorkspaceStatus.DELETED);
        }
        saved.setUpdatedAt(LocalDateTime.now());
        return workspaceRepository.save(saved);
    }

    /**
     * 删除 Workspace：落 DB（DELETING）→ 删 K8s Namespace（级联）→ 落 DB（DELETED）。
     *
     * <p>K8s 删除失败时仍将 DB 状态置为 DELETED（避免残留），并记录错误日志。</p>
     *
     * @param id Workspace ID
     * @return true 表示存在并已删除；false 表示 ID 不存在
     */
    public boolean deleteWorkspace(Long id) {
        Optional<Workspace> opt = workspaceRepository.findById(id);
        if (opt.isEmpty()) {
            return false;
        }
        Workspace ws = opt.get();
        ws.setStatus(Workspace.WorkspaceStatus.DELETING);
        ws.setUpdatedAt(LocalDateTime.now());
        workspaceRepository.save(ws);

        try {
            if (k8sMockEnabled) {
                log.info("K8s mock 模式：跳过 workspace {} 的 Namespace 删除", id);
            } else {
                k8sTranslator.deleteNamespace(ws);
            }
        } catch (K8sWorkspaceTranslator.K8sTranslationException e) {
            log.error("K8s namespace deletion failed for workspace {}: {}", id, e.getMessage(), e);
        }
        ws.setStatus(Workspace.WorkspaceStatus.DELETED);
        ws.setUpdatedAt(LocalDateTime.now());
        workspaceRepository.save(ws);
        return true;
    }

    /**
     * 列出全部 Workspace，可选按租户 ID 过滤。
     *
     * @param tenantId 租户 ID；为 null 时返回全部
     * @return Workspace 列表（不会返回 null）
     */
    public List<Workspace> listWorkspaces(Long tenantId) {
        if (tenantId == null) {
            return workspaceRepository.findAll();
        }
        return workspaceRepository.findByTenantId(tenantId);
    }

    /**
     * 按 ID 获取单个 Workspace。
     *
     * @param id Workspace ID
     * @return Optional 包装的 Workspace
     */
    public Optional<Workspace> getWorkspace(Long id) {
        return workspaceRepository.findById(id);
    }

    /**
     * 按 ID 更新 Workspace（仅更新可变字段：name、description、resourceQuota、networkPolicy）。
     *
     * @param id      Workspace ID
     * @param updated 新字段值
     * @return 更新后的 Workspace；若 ID 不存在则返回 Optional.empty()
     */
    public Optional<Workspace> updateWorkspace(Long id, Workspace updated) {
        Optional<Workspace> opt = workspaceRepository.findById(id);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        Workspace existing = opt.get();
        if (updated.getName() != null) {
            existing.setName(updated.getName());
        }
        if (updated.getDescription() != null) {
            existing.setDescription(updated.getDescription());
        }
        if (updated.getResourceQuota() != null) {
            existing.setResourceQuota(updated.getResourceQuota());
        }
        if (updated.getNetworkPolicy() != null) {
            existing.setNetworkPolicy(updated.getNetworkPolicy());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        return Optional.of(workspaceRepository.save(existing));
    }

    /**
     * 查询 Workspace 对应 K8s Namespace 的实时状态。
     *
     * @param id Workspace ID
     * @return K8s Namespace 状态字符串；若 Workspace 不存在返回 {@code "NotFound"}；
     *         mock 模式下无集群可查，返回 {@code "Unknown"}
     */
    public String getK8sStatus(Long id) {
        Optional<Workspace> opt = workspaceRepository.findById(id);
        if (opt.isEmpty()) {
            return "NotFound";
        }
        if (k8sMockEnabled) {
            return "Unknown";
        }
        return k8sTranslator.getNamespaceStatus(opt.get());
    }

    /* ------------------------------ 私有辅助 ------------------------------ */

    /**
     * 生成 K8s Namespace 名称：{@code ws-<tenantId>-<name-slug>}。
     *
     * <p>name-slug 仅保留小写字母、数字、连字符，最长 40 字符，确保符合 K8s Namespace 命名规范。</p>
     *
     * @param ws Workspace 元数据
     * @return K8s Namespace 名称
     */
    private String generateNamespaceName(Workspace ws) {
        String slug = ws.getName() == null ? "" : ws.getName().toLowerCase()
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        if (slug.isEmpty()) {
            slug = "ws";
        }
        return "ws-" + ws.getTenantId() + "-" + slug;
    }
}