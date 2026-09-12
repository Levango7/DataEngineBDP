package com.levango7.dataenginebdp.encaps.workspace;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.security.AuditLog;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Workspace REST 控制器。
 *
 * <p>统一前缀：{@code /api/v1/workspaces}</p>
 * <ul>
 *   <li>POST   /                  — 创建 Workspace，返回 201</li>
 *   <li>GET    /                  — 列表（支持 {@code tenantId} 过滤），返回 200</li>
 *   <li>GET    /{id}              — 详情，返回 200 或 404</li>
 *   <li>PUT    /{id}              — 更新，返回 200 或 404</li>
 *   <li>DELETE /{id}              — 删除，返回 204 或 404</li>
 *   <li>GET    /{id}/status       — K8s Namespace 实时状态，返回 200</li>
 * </ul>
 *
 * <p>JWT 鉴权复用现有 {@code SecurityConfig}，所有端点要求认证。</p>
 *
 * <p>安全控制（R8 修复）：
 * <ul>
 *   <li>租户隔离：list/listAll 从 {@link TenantContext} 获取 tenantId 并注入查询条件，
 *       忽略请求参数中的 tenantId，防止跨租户查看 workspace。</li>
 *   <li>get/update/delete/status 校验 Workspace 的 tenantId 与当前请求的 tenantId 匹配。</li>
 *   <li>create 时从 TenantContext 注入 tenantId，忽略请求体中的 tenantId。</li>
 * </ul></p>
 */
@RestController
@Tag(name = "封装租户-工作空间", description = "Workspace CRUD与K8s Namespace状态")
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
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
     * 创建 Workspace。
     *
     * <p>R8 修复：从 TenantContext 注入 tenantId，忽略请求体中的 tenantId（防止伪造）。
     *
     * @param workspace 创建请求体（tenantId 字段被覆盖为 JWT 中的 tenantId）
     * @return 201 + 已创建的 Workspace
     */
    @Operation(summary = "创建 Workspace（注入租户 ID）")
    @AuditLog(action = "CREATE_WORKSPACE", resource = "workspace")
    @PostMapping
    public ResponseEntity<Workspace> create(@Valid @RequestBody Workspace workspace) {
        Long tenantId = currentTenantIdLong();
        if (tenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 注入 JWT 中的 tenantId，忽略请求体中的 tenantId
        workspace.setTenantId(tenantId);
        Workspace created = workspaceService.createWorkspace(workspace);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 列出 Workspace，按当前租户 ID 过滤（忽略请求参数中的 tenantId）。
     *
     * <p>返回前端 {@code PagedResult} 契约（list/total/page），对齐
     * frontend/src/api/workspace.ts 的 listWorkspaces。</p>
     *
     * <p>R8 修复：tenantId 从 TenantContext 获取，忽略请求参数中的 tenantId，
     * 仅返回当前租户的 workspace。
     *
     * @param page 页码（1 起）
     * @param size 每页大小
     * @return 200 + 分页 Workspace 列表（仅当前租户的）
     */
    @Operation(summary = "列出 Workspace（租户隔离，仅返回当前租户的）")
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long tenantId = currentTenantIdLong();
        if (tenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 忽略请求参数中的 tenantId，强制使用 TenantContext 中的 tenantId
        List<Workspace> all = workspaceService.listWorkspaces(tenantId);
        int total = all.size();
        int start = Math.min((page - 1) * size, total);
        int end = Math.min(start + size, total);
        List<Workspace> pageItems = all.subList(start, end);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", pageItems);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(result);
    }

    /**
     * 列出全部 Workspace（不分页，用于前端下拉选择，租户隔离）。
     *
     * <p>对齐前端 {@code workspace.ts} 的 {@code listAllWorkspaces}。</p>
     *
     * <p>R8 修复：tenantId 从 TenantContext 获取，仅返回当前租户的 workspace。
     *
     * @return 200 + 全部 Workspace 列表（仅当前租户的）
     */
    @Operation(summary = "列出全部 Workspace（不分页，租户隔离）")
    @GetMapping("/all")
    public ResponseEntity<List<Workspace>> listAll() {
        Long tenantId = currentTenantIdLong();
        if (tenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(workspaceService.listWorkspaces(tenantId));
    }

    /**
     * 获取单个 Workspace 详情（校验租户隔离）。
     *
     * <p>R8 修复：校验 Workspace 的 tenantId 与当前请求的 tenantId 匹配。
     *
     * @param id Workspace ID
     * @return 200 + Workspace；404 若不存在或租户不匹配
     */
    @Operation(summary = "获取单个 Workspace 详情（租户隔离）")
    @GetMapping("/{id}")
    public ResponseEntity<Workspace> get(@PathVariable Long id) {
        Long tenantId = currentTenantIdLong();
        if (tenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return workspaceService.getWorkspace(id)
                .filter(ws -> tenantId.equals(ws.getTenantId()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 更新 Workspace（仅可变字段，校验租户隔离）。
     *
     * <p>R8 修复：校验 Workspace 的 tenantId 与当前请求的 tenantId 匹配。
     *
     * @param id        Workspace ID
     * @param workspace 新字段值
     * @return 200 + 更新后的 Workspace；404 若不存在或租户不匹配
     */
    @Operation(summary = "更新 Workspace（仅可变字段，租户隔离）")
    @AuditLog(action = "UPDATE_WORKSPACE", resource = "workspace")
    @PutMapping("/{id}")
    public ResponseEntity<Workspace> update(@PathVariable Long id,
                                            @Valid @RequestBody Workspace workspace) {
        Long tenantId = currentTenantIdLong();
        if (tenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 先校验存在且属于当前租户
        return workspaceService.getWorkspace(id)
                .filter(existing -> tenantId.equals(existing.getTenantId()))
                .map(existing -> workspaceService.updateWorkspace(id, workspace)
                        .map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.notFound().build()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 删除 Workspace（级联删除 K8s Namespace 及其下全部资源，校验租户隔离）。
     *
     * <p>R8 修复：校验 Workspace 的 tenantId 与当前请求的 tenantId 匹配。
     *
     * @param id Workspace ID
     * @return 204 若已删除；404 若不存在或租户不匹配
     */
    @Operation(summary = "删除工作空间（租户隔离）")
    @AuditLog(action = "DELETE_WORKSPACE", resource = "workspace")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long tenantId = currentTenantIdLong();
        if (tenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 先校验存在且属于当前租户
        boolean owned = workspaceService.getWorkspace(id)
                .map(existing -> tenantId.equals(existing.getTenantId()))
                .orElse(false);
        if (!owned) {
            return ResponseEntity.notFound().build();
        }
        if (workspaceService.deleteWorkspace(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 查询 Workspace 对应 K8s Namespace 的实时状态（校验租户隔离）。
     *
     * <p>R8 修复：校验 Workspace 的 tenantId 与当前请求的 tenantId 匹配。
     *
     * @param id Workspace ID
     * @return 200 + {@code {"status": "Active"|"Terminating"|"NotFound"}}；404 若不存在或租户不匹配
     */
    @Operation(summary = "查询 Workspace 对应 K8s Namespace 的实时状态（租户隔离）")
    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, String>> status(@PathVariable Long id) {
        Long tenantId = currentTenantIdLong();
        if (tenantId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 先校验存在且属于当前租户
        boolean owned = workspaceService.getWorkspace(id)
                .map(existing -> tenantId.equals(existing.getTenantId()))
                .orElse(false);
        if (!owned) {
            return ResponseEntity.notFound().build();
        }
        String k8sStatus = workspaceService.getK8sStatus(id);
        return ResponseEntity.ok(Map.of("status", k8sStatus));
    }
}
