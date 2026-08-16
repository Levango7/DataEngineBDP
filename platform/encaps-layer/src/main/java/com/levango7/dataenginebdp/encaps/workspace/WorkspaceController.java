package com.levango7.dataenginebdp.encaps.workspace;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * <p>JWT 鉴权复用现有 {@code SecurityConfig}，所有端点要求认证。
 * 租户上下文由 {@code TenantContext} 提供，但本控制器允许显式 {@code tenantId} 查询参数覆盖。</p>
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * 创建 Workspace。
     *
     * @param workspace 创建请求体
     * @return 201 + 已创建的 Workspace
     */
    @PostMapping
    public ResponseEntity<Workspace> create(@Valid @RequestBody Workspace workspace) {
        Workspace created = workspaceService.createWorkspace(workspace);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 列出 Workspace，可选按租户 ID 过滤。
     *
     * <p>返回前端 {@code PagedResult} 契约（list/total/page），对齐
     * frontend/src/api/workspace.ts 的 listWorkspaces。</p>
     *
     * @param tenantId 租户 ID（可选）
     * @param page     页码（1 起）
     * @param size     每页大小
     * @return 200 + 分页 Workspace 列表
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
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
     * 列出全部 Workspace（不分页，用于前端下拉选择）。
     *
     * <p>对齐前端 {@code workspace.ts} 的 {@code listAllWorkspaces}。</p>
     *
     * @param tenantId 租户 ID（可选）
     * @return 200 + 全部 Workspace 列表
     */
    @GetMapping("/all")
    public ResponseEntity<List<Workspace>> listAll(@RequestParam(required = false) Long tenantId) {
        return ResponseEntity.ok(workspaceService.listWorkspaces(tenantId));
    }

    /**
     * 获取单个 Workspace 详情。
     *
     * @param id Workspace ID
     * @return 200 + Workspace；404 若不存在
     */
    @GetMapping("/{id}")
    public ResponseEntity<Workspace> get(@PathVariable Long id) {
        return workspaceService.getWorkspace(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 更新 Workspace（仅可变字段）。
     *
     * @param id        Workspace ID
     * @param workspace 新字段值
     * @return 200 + 更新后的 Workspace；404 若不存在
     */
    @PutMapping("/{id}")
    public ResponseEntity<Workspace> update(@PathVariable Long id,
                                            @Valid @RequestBody Workspace workspace) {
        return workspaceService.updateWorkspace(id, workspace)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 删除 Workspace（级联删除 K8s Namespace 及其下全部资源）。
     *
     * @param id Workspace ID
     * @return 204 若已删除；404 若不存在
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (workspaceService.deleteWorkspace(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 查询 Workspace 对应 K8s Namespace 的实时状态。
     *
     * @param id Workspace ID
     * @return 200 + {@code {"status": "Active"|"Terminating"|"NotFound"}}
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, String>> status(@PathVariable Long id) {
        String k8sStatus = workspaceService.getK8sStatus(id);
        return ResponseEntity.ok(Map.of("status", k8sStatus));
    }
}