package com.levango7.dataenginebdp.federated.controller;

import com.levango7.dataenginebdp.federated.model.DegradationAlert;
import com.levango7.dataenginebdp.federated.model.FederatedQueryRequest;
import com.levango7.dataenginebdp.federated.model.FederatedQueryResponse;
import com.levango7.dataenginebdp.federated.service.FederatedQueryService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 跨集群查询 REST API。
 *
 * <p><b>租户隔离</b>：tenantId 从 JWT claims 提取（{@code tenantId} claim），
 * 不信任请求体中的 tenantId 字段。admin 角色可显式指定任意 tenantId，
 * 普通用户强制使用 JWT 声明的 tenantId。
 */
@Slf4j
@RestController
@Tag(name = "多集群联邦-跨集群查询", description = "跨集群SQL查询与降级路由")
@RequestMapping("/api/v1/federated")
public class FederatedQueryController {

    private static final String TENANT_CLAIM = "tenantId";
    private static final String ROLE_CLAIM = "role";
    private static final String ADMIN_ROLE = "admin";

    private final FederatedQueryService service;

    public FederatedQueryController(FederatedQueryService service) {
        this.service = service;
    }

    /**
     * 提交跨集群查询（异步）。
     *
     * <p>POST /api/v1/federated/query
     */
    @Operation(summary = "提交跨集群查询（异步）")
    @PostMapping("/query")
    public CompletableFuture<ResponseEntity<FederatedQueryResponse>> query(@Valid @RequestBody FederatedQueryRequest request) {
        // 从 JWT 提取并校验 tenantId（不信任请求体中的 tenantId）
        resolveTenantFromJwt(request);
        log.info("Federated query submitted: sql={} database={} tenantId={}",
                abbreviate(request.getSql()), request.getDatabase(), request.getTenantId());
        return service.executeAsync(request)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * 同步跨集群查询。
     *
     * <p>POST /api/v1/federated/query/sync
     */
    @Operation(summary = "同步跨集群查询")
    @PostMapping("/query/sync")
    public ResponseEntity<FederatedQueryResponse> querySync(@Valid @RequestBody FederatedQueryRequest request) {
        // 从 JWT 提取并校验 tenantId（不信任请求体中的 tenantId）
        resolveTenantFromJwt(request);
        log.info("Federated sync query: sql={} database={} tenantId={}",
                abbreviate(request.getSql()), request.getDatabase(), request.getTenantId());
        return ResponseEntity.ok(service.executeSync(request));
    }

    /**
     * 健康检查。
     *
     * <p>GET /api/v1/federated/health
     */
    @Operation(summary = "联邦健康检查")
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "federated-query",
                "version", "0.1.0",
                "timestamp", Instant.now().toString()));
    }

    /**
     * 列出已知集群。
     *
     * <p>GET /api/v1/federated/clusters
     */
    @Operation(summary = "列出已知集群")
    @GetMapping("/clusters")
    public ResponseEntity<Map<String, Object>> clusters() {
        List<Map<String, Object>> list = service.listClusters();
        return ResponseEntity.ok(Map.of(
                "data", list,
                "total", list.size()));
    }

    /**
     * 列出降级告警事件。
     *
     * <p>GET /api/v1/federated/degradations
     */
    @Operation(summary = "列出降级告警事件")
    @GetMapping("/degradations")
    public ResponseEntity<Map<String, Object>> degradations(@RequestParam(defaultValue = "100") int limit) {
        // 限制 limit 上限，防止过大查询造成 OOM
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        List<DegradationAlert> alerts = service.listDegradeAlerts(safeLimit);
        return ResponseEntity.ok(Map.of(
                "data", alerts,
                "total", alerts.size()));
    }

    /**
     * 从 JWT claims 提取 tenantId 并覆盖请求体中的值。
     *
     * <p>策略：
     * <ul>
     *   <li>admin 角色：允许使用请求体中的 tenantId（若提供），否则回退到 JWT claims</li>
     *   <li>普通用户：强制使用 JWT claims 中的 tenantId，忽略请求体中的值</li>
     * </ul>
     *
     * @param request 联邦查询请求
     * @throws IllegalStateException JWT 上下文缺失或 tenantId claim 为空
     */
    private void resolveTenantFromJwt(FederatedQueryRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("JWT 鉴权上下文缺失：无法提取 tenantId");
        }
        String jwtTenantId = jwt.getClaimAsString(TENANT_CLAIM);
        String role = jwt.getClaimAsString(ROLE_CLAIM);
        boolean isAdmin = ADMIN_ROLE.equals(role);

        if (isAdmin && request.getTenantId() != null && !request.getTenantId().isBlank()) {
            // admin 显式指定 → 保留请求体中的 tenantId
            return;
        }
        if (jwtTenantId == null || jwtTenantId.isBlank()) {
            throw new IllegalStateException(
                    "JWT claims 中 tenantId 为空：" + (isAdmin ? "admin 必须在请求体中显式指定 tenantId" : "请联系管理员签发包含 tenantId 的 token"));
        }
        // 普通用户或 admin 未显式指定 → 强制使用 JWT claims 中的 tenantId
        request.setTenantId(jwtTenantId);
    }

    private String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
