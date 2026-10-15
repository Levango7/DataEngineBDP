package com.levango7.dataenginebdp.encaps.tenant.controller;

import com.levango7.dataenginebdp.encapsshared.repository.ProjectRepository;
import com.levango7.dataenginebdp.encaps.quota.QuotaRepository;
import com.levango7.dataenginebdp.encaps.workspace.WorkspaceRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.common.MissingTenantContextException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营后台端点（ROADMAP 前后端接线：前端 /admin）。
 *
 * <p>KPI 从真实仓储聚合，环境矩阵为轻量静态视图（真实集群状态见 query-api /cluster）。</p>
 *
 * <p><b>鉴权</b>：要求认证上下文（{@link TenantContext#getTenantId()} 非空），
 * 否则返回 403。KPI 聚合用 {@code count()} 而非 {@code findAll().size()}，
 * 避免全表加载造成 OOM。</p>
 *
 * <p><b>契约只包含"本服务能诚实算出的数"</b>（2026-10-13 收口，见 PR #451 的后续）。
 * 此前本端点返回两类<b>编造值</b>，运维会当真实 KPI 读，比返回 {@code null} 更糟：</p>
 *
 * <ol>
 *   <li><b>硬编码</b>：{@code tenantExternal=0} / {@code tenantInternal=1} /
 *       {@code clusterTotal=1} / {@code clusterXinchuang=1} —— 无对应数据模型，
 *       写死在代码里；</li>
 *   <li><b>结构性恒 0</b>：{@code assetTotal} / {@code apiTotal} /
 *       {@code datasourceTotal} —— 这三张表由 encaps-layer / encaps-gateway /
 *       encaps-data 写入 <b>encaps_layer</b> schema，而本服务的
 *       {@code currentSchema=encaps_tenant}，实体 {@code @Table} 又未带
 *       schema 前缀 ⇒ 查询落到 {@code encaps_tenant} 的空副本上，永远返回 0。
 *       {@code syncTaskTotal} 同理：本服务没有 sync_task 的写入方。</li>
 * </ol>
 *
 * <p>这些字段已全部从响应中移除，前端对应字段标 {@code @deprecated} 并显示占位符。
 * 要恢复它们，必须先解决<b>跨 schema 数据归属</b>问题（台账 #63），
 * 而不是把占位符改回 {@code ?? 0}。</p>
 *
 * <p><b>租户口径（{@code tenantTotal} 已于 2026-10-13 移除）</b>：
 * 网关（`configmap-routes.yaml`）把 {@code /api/v1/tenants/**} 路由到
 * <b>encaps-layer</b>，前端租户页读的是 {@code encaps_layer.tenants}；
 * 本服务的 {@code encaps_tenant.tenants} 是<b>只有直连才会写</b>的孤儿副本
 * ⇒ 在生产拓扑下恒为空表，{@code count()} 恒为 0。
 * 该值无法在本服务内诚实算出，故从响应移除、前端显示占位符；
 * 要恢复须先按「网关权威」收敛数据归属（encaps-shared-model 抽取）。</p>
 */
@Slf4j
@RestController
@Tag(name = "封装租户-运营后台", description = "KPI总览与环境矩阵")
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final WorkspaceRepository workspaceRepository;
    private final QuotaRepository quotaRepository;
    private final ProjectRepository projectRepository;

    /**
     * KPI 总览（要求认证上下文）。
     *
     * <p>仅返回本服务能诚实算出的三个计数：工作空间数、配额数、项目数。
     * 已下线的字段与原因见类注释。</p>
     */
    @Operation(summary = "KPI 总览")
    @GetMapping("/kpi")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> kpi() {
        String tenantId = requireTenant();
        Map<String, Object> body = new LinkedHashMap<>();
        // 用 count() 替代 findAll().size()，避免全表加载造成 OOM；
        // 且必须带 tenantId —— 此前 workspaceRepository.count() 是全表计数，口径跨了租户
        body.put("workspaceTotal", workspaceRepository.countByTenantId(tenantId));
        body.put("quotaTotal", quotaCount(tenantId));
        body.put("projectTotal", projectRepository.countByTenantId(tenantId));
        return ResponseEntity.ok(body);
    }

    /** 配额数（tenantId 容错：非数字返回 0）。 */
    private int quotaCount(String tenantId) {
        try {
            return quotaRepository.findByTenantId(tenantId).size();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 环境矩阵（四环境交付视图，要求认证上下文）。 */
    @Operation(summary = "环境矩阵（四环境交付视图）")
    @GetMapping("/env-matrix")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> envMatrix() {
        // 同样按租户口径收口：此前 workspaceRepository.count() 是全表计数，
        // 任何一个租户都能看到全平台的工作空间数。
        // 注意 namespaceCount 映射的是工作空间数，该字段命名与语义不符属既有问题，
        // 本次只收口租户口径，不改指标含义。
        long workspaces = workspaceRepository.countByTenantId(requireTenant());
        return ResponseEntity.ok(List.of(
                envRow("xinchuang", "信创环境", workspaces, 3, "kubeadm + 国产化组件"),
                envRow("onprem", "本地数据中心", workspaces, 3, "kubeadm + 离线镜像"),
                envRow("publiccloud", "公有云", workspaces, 3, "托管的 K8s 服务"),
                envRow("privatecloud", "私有云", workspaces, 3, "OpenStack + kubeadm")));
    }

    private Map<String, Object> envRow(String id, String name, long ns, int nodes, String cp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("namespaceCount", ns);
        m.put("nodeCount", nodes);
        m.put("controlPlane", cp);
        return m;
    }

    /**
     * 鉴权门禁：要求认证上下文（{@link TenantContext#getTenantId()} 非空）。
     *
     * <p>缺租户上下文一律抛 {@link MissingTenantContextException}（→ 403），
     * 与 {@code AccountController} / {@code ProjectController} /
     * {@code QuotaController} / {@code WorkspaceController} 的
     * {@code currentTenantKey()} 同口径。此前本类抛
     * {@code ResponseStatusException(UNAUTHORIZED)}（→ 401），
     * 造成同服务内同一前提两种状态码；401 会触发前端
     * {@code api/client.ts} 清登录态并跳 {@code /login}，
     * 即"缺租户上下文"被误读成"登录过期"，用户被无故踢出。</p>
     *
     * @return 租户 ID
     * @throws MissingTenantContextException 缺少认证上下文时返回 403
     */
    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new MissingTenantContextException();
        }
        return tenantId;
    }
}
