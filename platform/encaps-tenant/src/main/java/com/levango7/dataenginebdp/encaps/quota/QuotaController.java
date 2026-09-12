package com.levango7.dataenginebdp.encaps.quota;

import com.levango7.dataenginebdp.common.security.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * Quota REST 控制器。
 *
 * <p>统一前缀：{@code /api/v1/quotas}</p>
 * <ul>
 *   <li>POST   /                          — 设置 Quota，返回 201</li>
 *   <li>GET    /                          — 列表（支持 {@code tenantId}/{@code workspaceId} 过滤），返回 200</li>
 *   <li>GET    /{id}                      — 详情，返回 200 或 404</li>
 *   <li>PUT    /{id}                      — 更新，返回 200 或 404</li>
 *   <li>DELETE /{id}                      — 删除，返回 204 或 404</li>
 *   <li>GET    /workspace/{workspaceId}/usage — 查询当前用量，返回 200</li>
 * </ul>
 *
 * <p>JWT 鉴权复用现有 {@code SecurityConfig}，所有端点要求认证。
 * {@link QuotaExceededException} 映射为 422 Unprocessable Entity；
 * {@link IllegalStateException}（如重复设置）映射为 409 Conflict。</p>
 *
 * <p>安全控制（R8 修复）：
 * <ul>
 *   <li>类级 {@code @PreAuthorize("hasRole('ADMIN')")}：配额管理是管理员操作，
 *       仅管理员可设置/更新/删除配额。</li>
 *   <li>租户隔离：list/get/update/delete 从 {@link TenantContext} 获取 tenantId 并注入查询条件，
 *       忽略请求参数中的 tenantId（防止跨租户访问）。</li>
 *   <li>setQuota 时从 TenantContext 注入 tenantId，忽略请求体中的 tenantId。</li>
 * </ul></p>
 */
@RestController
@Tag(name = "封装租户-配额管理", description = "Quota CRUD与用量查询")
@RequestMapping("/api/v1/quotas")
@PreAuthorize("hasRole('ADMIN')")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /**
     * 从 TenantContext 获取当前租户 ID（Long 类型），若缺失或无效返回 null。
     *
     * @return 当前请求的租户 ID；若上下文未设置或非数字返回 null
     */
    private Long currentTenantIdLong() {
        String tid = TenantContext.getTenantId();
        if (tid == null || tid.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(tid);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 设置 Quota。
     *
     * <p>R8 修复：从 TenantContext 注入 tenantId，忽略请求体中的 tenantId（防止伪造）。
     *
     * @param quota 设置请求体（tenantId 字段被覆盖为 JWT 中的 tenantId）
     * @return 201 + 已创建的 Quota；409 若同一 Workspace 已存在活跃 Quota
     */
    @Operation(summary = "设置 Quota")
    @PostMapping
    public ResponseEntity<Quota> setQuota(@Valid @RequestBody Quota quota) {
        Long tenantId = currentTenantIdLong();
        if (tenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 注入 JWT 中的 tenantId，忽略请求体中的 tenantId
        quota.setTenantId(tenantId);
        Quota created = quotaService.setQuota(quota);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 列出 Quota，按当前租户 ID 过滤（忽略请求参数中的 tenantId）。
     *
     * <p>R8 修复：tenantId 从 TenantContext 获取，忽略请求参数中的 tenantId。
     *
     * @param workspaceId Workspace ID（可选）
     * @return 200 + Quota 列表（仅当前租户的）
     */
    @Operation(summary = "列出 Quota（租户隔离，按当前租户过滤）")
    @GetMapping
    public ResponseEntity<List<Quota>> list(
            @RequestParam(required = false) Long workspaceId) {
        Long tenantId = currentTenantIdLong();
        if (tenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 忽略请求参数中的 tenantId，强制使用 TenantContext 中的 tenantId
        return ResponseEntity.ok(quotaService.listQuotas(tenantId, workspaceId));
    }

    /**
     * 获取单个 Quota 详情（校验租户隔离）。
     *
     * <p>R8 修复：校验 Quota 的 tenantId 与当前请求的 tenantId 匹配。
     *
     * @param id Quota ID
     * @return 200 + Quota；404 若不存在或租户不匹配
     */
    @Operation(summary = "获取单个 Quota 详情（租户隔离）")
    @GetMapping("/{id}")
    public ResponseEntity<Quota> get(@PathVariable Long id) {
        Long tenantId = currentTenantIdLong();
        if (tenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return quotaService.getQuota(id)
                .filter(q -> tenantId.equals(q.getTenantId()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 更新 Quota（仅可变字段：配额字段 + per-Pod 限制，校验租户隔离）。
     *
     * <p>R8 修复：校验 Quota 的 tenantId 与当前请求的 tenantId 匹配。
     *
     * @param id    Quota ID
     * @param quota 新字段值
     * @return 200 + 更新后的 Quota；404 若不存在或租户不匹配
     */
    @Operation(summary = "更新 Quota（仅可变字段：配额字段 + per-Pod 限制，租户隔离）")
    @PutMapping("/{id}")
    public ResponseEntity<Quota> update(@PathVariable Long id,
                                        @Valid @RequestBody Quota quota) {
        Long tenantId = currentTenantIdLong();
        if (tenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 先校验存在且属于当前租户
        return quotaService.getQuota(id)
                .filter(existing -> tenantId.equals(existing.getTenantId()))
                .map(existing -> quotaService.updateQuota(id, quota)
                        .map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.notFound().build()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 删除 Quota（级联删除 K8s ResourceQuota + LimitRange，校验租户隔离）。
     *
     * <p>R8 修复：校验 Quota 的 tenantId 与当前请求的 tenantId 匹配。
     *
     * @param id Quota ID
     * @return 204 若已删除；404 若不存在或租户不匹配
     */
    @Operation(summary = "删除配额（租户隔离）")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long tenantId = currentTenantIdLong();
        if (tenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 先校验存在且属于当前租户
        boolean owned = quotaService.getQuota(id)
                .map(existing -> tenantId.equals(existing.getTenantId()))
                .orElse(false);
        if (!owned) {
            return ResponseEntity.notFound().build();
        }
        if (quotaService.deleteQuota(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 查询 Workspace 当前资源用量（已用 / 配额，校验租户隔离）。
     *
     * <p>R8 修复：通过 workspaceId 查询对应的 Quota，校验 Quota 的 tenantId 与当前请求匹配。
     *
     * @param workspaceId Workspace ID
     * @return 200 + {@code {"used": {...}, "hard": {...}}}；404 若不存在或租户不匹配
     */
    @Operation(summary = "查询 Workspace 当前资源用量（已用 / 配额，租户隔离）")
    @GetMapping("/workspace/{workspaceId}/usage")
    public ResponseEntity<Map<String, Map<String, String>>> usage(@PathVariable Long workspaceId) {
        Long tenantId = currentTenantIdLong();
        if (tenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 校验该 workspace 的 quota 属于当前租户
        boolean owned = quotaService.getQuotaByWorkspace(workspaceId)
                .map(q -> tenantId.equals(q.getTenantId()))
                .orElse(false);
        if (!owned) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(quotaService.getUsage(workspaceId));
    }

    /* ------------------------------ 异常处理 ------------------------------ */

    /**
     * 配额超限 → 422 Unprocessable Entity。
     */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, String>> handleQuotaExceeded(QuotaExceededException e) {
        Map<String, String> body = Map.of(
                "error", "quota_exceeded",
                "message", e.getMessage(),
                "resourceKey", e.getResourceKey() == null ? "" : e.getResourceKey(),
                "used", e.getUsed() == null ? "" : e.getUsed(),
                "hard", e.getHard() == null ? "" : e.getHard(),
                "requested", e.getRequested() == null ? "" : e.getRequested()
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /**
     * 重复设置 → 409 Conflict。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "conflict", "message", e.getMessage()));
    }
}
