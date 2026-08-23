package com.levango7.dataenginebdp.encaps.quota;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 */
@RestController
@Tag(name = "封装租户-配额管理", description = "Quota CRUD与用量查询")
@RequestMapping("/api/v1/quotas")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /**
     * 设置 Quota。
     *
     * @param quota 设置请求体
     * @return 201 + 已创建的 Quota；409 若同一 Workspace 已存在活跃 Quota
     */
    @Operation(summary = "设置 Quota")
    @PostMapping
    public ResponseEntity<Quota> setQuota(@Valid @RequestBody Quota quota) {
        Quota created = quotaService.setQuota(quota);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 列出 Quota，可选按租户 ID 与 Workspace ID 过滤。
     *
     * @param tenantId    租户 ID（可选）
     * @param workspaceId Workspace ID（可选）
     * @return 200 + Quota 列表
     */
    @Operation(summary = "列出 Quota，可选按租户 ID 与 Workspace ID 过滤")
    @GetMapping
    public ResponseEntity<List<Quota>> list(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long workspaceId) {
        return ResponseEntity.ok(quotaService.listQuotas(tenantId, workspaceId));
    }

    /**
     * 获取单个 Quota 详情。
     *
     * @param id Quota ID
     * @return 200 + Quota；404 若不存在
     */
    @Operation(summary = "获取单个 Quota 详情")
    @GetMapping("/{id}")
    public ResponseEntity<Quota> get(@PathVariable Long id) {
        return quotaService.getQuota(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 更新 Quota（仅可变字段：配额字段 + per-Pod 限制）。
     *
     * @param id    Quota ID
     * @param quota 新字段值
     * @return 200 + 更新后的 Quota；404 若不存在
     */
    @Operation(summary = "更新 Quota（仅可变字段：配额字段 + per-Pod 限制）")
    @PutMapping("/{id}")
    public ResponseEntity<Quota> update(@PathVariable Long id,
                                        @Valid @RequestBody Quota quota) {
        return quotaService.updateQuota(id, quota)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 删除 Quota（级联删除 K8s ResourceQuota + LimitRange）。
     *
     * @param id Quota ID
     * @return 204 若已删除；404 若不存在
     */
    @Operation(summary = "删除配额")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (quotaService.deleteQuota(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 查询 Workspace 当前资源用量（已用 / 配额）。
     *
     * @param workspaceId Workspace ID
     * @return 200 + {@code {"used": {...}, "hard": {...}}}
     */
    @Operation(summary = "查询 Workspace 当前资源用量（已用 / 配额）")
    @GetMapping("/workspace/{workspaceId}/usage")
    public ResponseEntity<Map<String, Map<String, String>>> usage(@PathVariable Long workspaceId) {
        return ResponseEntity.ok(quotaService.getUsage(workspaceId));
    }

    /* ------------------------------ 异常处理 ------------------------------ */

    /**
     * 配额超限 → 422 Unprocessable Entity。
     */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, String>> handleQuotaExceeded(QuotaExceededException e) {
        Map<String, String> body = Map.of(
                "error", "QuotaExceeded",
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
                .body(Map.of("error", "Conflict", "message", e.getMessage()));
    }
}