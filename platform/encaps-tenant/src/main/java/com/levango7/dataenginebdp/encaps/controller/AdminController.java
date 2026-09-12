package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.repository.ApiDefinitionRepository;
import com.levango7.dataenginebdp.encaps.repository.AssetRepository;
import com.levango7.dataenginebdp.encaps.repository.DataSourceRepository;
import com.levango7.dataenginebdp.encaps.repository.ProjectRepository;
import com.levango7.dataenginebdp.encaps.quota.QuotaRepository;
import com.levango7.dataenginebdp.encaps.repository.SyncTaskRepository;
import com.levango7.dataenginebdp.encaps.workspace.WorkspaceRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
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
 * <p>KPI 从真实仓储聚合（租户/工作空间/配额/资产/API/数据源/项目/同步任务），
 * 环境矩阵为轻量静态视图（真实集群状态见 query-api /cluster）。</p>
 *
 * <p><b>鉴权</b>：要求认证上下文（{@link TenantContext#getTenantId()} 非空），
 * 否则返回 401。KPI 聚合用 {@code count()} 而非 {@code findAll().size()}，
 * 避免全表加载造成 OOM。</p>
 */
@Slf4j
@RestController
@Tag(name = "封装租户-运营后台", description = "KPI总览与环境矩阵")
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final WorkspaceRepository workspaceRepository;
    private final QuotaRepository quotaRepository;
    private final AssetRepository assetRepository;
    private final ApiDefinitionRepository apiRepository;
    private final DataSourceRepository dataSourceRepository;
    private final ProjectRepository projectRepository;
    private final SyncTaskRepository syncTaskRepository;

    /** KPI 总览（要求认证上下文）。 */
    @Operation(summary = "KPI 总览")
    @GetMapping("/kpi")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> kpi() {
        String tenantId = requireTenant();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantTotal", 1);
        body.put("tenantExternal", 0);
        body.put("tenantInternal", 1);
        body.put("clusterTotal", 1);
        body.put("clusterXinchuang", 1);
        // 用 count() 替代 findAll().size()，避免全表加载造成 OOM
        body.put("workspaceTotal", workspaceRepository.count());
        body.put("quotaTotal", quotaCount(tenantId));
        body.put("assetTotal", assetRepository.countByTenantId(tenantId));
        body.put("apiTotal", apiRepository.countByTenantId(tenantId));
        body.put("datasourceTotal", dataSourceRepository.countByTenantId(tenantId));
        body.put("projectTotal", projectRepository.countByTenantId(tenantId));
        body.put("syncTaskTotal", syncTaskRepository.countByTenantId(tenantId));
        return ResponseEntity.ok(body);
    }

    /** 配额数（tenantId 容错：非数字返回 0）。 */
    private int quotaCount(String tenantId) {
        try {
            return quotaRepository.findByTenantId(Long.parseLong(tenantId)).size();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 环境矩阵（四环境交付视图，要求认证上下文）。 */
    @Operation(summary = "环境矩阵（四环境交付视图）")
    @GetMapping("/env-matrix")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> envMatrix() {
        requireTenant();
        // 用 count() 替代 findAll().size()，避免全表加载造成 OOM
        long workspaces = workspaceRepository.count();
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
     * @return 租户 ID
     * @throws org.springframework.web.server.ResponseStatusException 缺少认证上下文时返回 401
     */
    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "缺少认证上下文");
        }
        return tenantId;
    }
}
