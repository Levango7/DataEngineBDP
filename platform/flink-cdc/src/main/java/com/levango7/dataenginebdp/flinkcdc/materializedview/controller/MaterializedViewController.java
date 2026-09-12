package com.levango7.dataenginebdp.flinkcdc.materializedview.controller;

import com.levango7.dataenginebdp.flinkcdc.materializedview.model.MaterializedViewDef;
import com.levango7.dataenginebdp.flinkcdc.materializedview.refresh.ViewRefresher;
import com.levango7.dataenginebdp.flinkcdc.materializedview.service.MaterializedViewService;
import com.levango7.dataenginebdp.common.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 物化视图管理 REST API 控制器。
 *
 * <p>提供物化视图定义的 CRUD、手动刷新、状态查询等 HTTP 接口。</p>
 *
 * <p>安全控制（R8 修复）：
 * <ul>
 *   <li>租户隔离：所有操作从 {@link TenantContext} 读取当前租户 ID，
 *       由 {@link MaterializedViewService} 按复合 key（tenantId|viewName）隔离存储，
 *       跨租户访问返回 404 而非 403，避免越权信息泄露。</li>
 *   <li>错误信息脱敏：异常消息不回显 {@code e.getMessage()}，统一返回通用文案，
 *       详细堆栈仅写日志，防止内部实现细节（表名/SQL/连接串）外泄。</li>
 * </ul></p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code POST   /api/materialized-views} — 注册物化视图</li>
 *   <li>{@code GET    /api/materialized-views} — 列出所有物化视图</li>
 *   <li>{@code GET    /api/materialized-views/{name}} — 查询单个物化视图</li>
 *   <li>{@code PUT    /api/materialized-views/{name}} — 更新物化视图</li>
 *   <li>{@code DELETE /api/materialized-views/{name}} — 删除物化视图</li>
 *   <li>{@code POST   /api/materialized-views/{name}/refresh} — 手动触发刷新</li>
 *   <li>{@code GET    /api/materialized-views/{name}/status} — 查询刷新状态</li>
 *   <li>{@code GET    /api/materialized-views/status} — 查询全局状态</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@RestController
@Tag(name = "Flink CDC-物化视图", description = "物化视图CRUD与刷新管理")
@RequestMapping("/api/materialized-views")
public class MaterializedViewController {

    private static final Logger log = LoggerFactory.getLogger(MaterializedViewController.class);

    /** 脱敏通用错误文案（不回显内部异常细节）。 */
    private static final String GENERIC_ERROR_MESSAGE = "操作失败，请检查参数或联系管理员";

    /** 物化视图管理服务。 */
    private final MaterializedViewService service;

    /**
     * 构造器（Spring 自动注入）。
     *
     * @param service 物化视图服务
     */
    public MaterializedViewController(MaterializedViewService service) {
        this.service = Objects.requireNonNull(service, "MaterializedViewService 不能为 null");
    }

    /**
     * 注册物化视图。
     *
     * @param def 物化视图定义
     * @return 201 创建成功；409 名称冲突
     */
    @Operation(summary = "注册物化视图")
    @PostMapping
    public ResponseEntity<Map<String, Object>> registerView(@RequestBody MaterializedViewDef def) {
        requireTenant();
        try {
            boolean success = service.registerView(def);
            if (success) {
                return ResponseEntity.created(null).body(Map.of(
                        "success", true,
                        "message", "物化视图注册成功: " + def.getName(),
                        "viewName", def.getName()
                ));
            } else {
                return ResponseEntity.status(409).body(Map.of(
                        "success", false,
                        "message", "物化视图已存在: " + def.getName()
                ));
            }
        } catch (Exception e) {
            log.error("注册物化视图失败: name={}", def.getName(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", GENERIC_ERROR_MESSAGE
            ));
        }
    }

    /**
     * 列出所有物化视图。
     *
     * @return 200 视图列表
     */
    @Operation(summary = "列出所有物化视图")
    @GetMapping
    public ResponseEntity<List<MaterializedViewDef>> listViews() {
        requireTenant();
        return ResponseEntity.ok(service.listViews());
    }

    /**
     * 查询单个物化视图（租户隔离，跨租户返回 404）。
     *
     * @param name 视图名称
     * @return 200 视图定义；404 不存在或不属于当前租户
     */
    @Operation(summary = "查询单个物化视图")
    @GetMapping("/{name}")
    public ResponseEntity<?> getView(@PathVariable String name) {
        requireTenant();
        MaterializedViewDef def = service.getView(name);
        if (def == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "message", "物化视图不存在: " + name
            ));
        }
        return ResponseEntity.ok(def);
    }

    /**
     * 更新物化视图。
     *
     * @param name 视图名称
     * @param def  新的视图定义
     * @return 200 成功；404 不存在
     */
    @Operation(summary = "更新物化视图")
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> updateView(@PathVariable String name,
                                                           @RequestBody MaterializedViewDef def) {
        requireTenant();
        try {
            // 确保 path 中的 name 与 body 中的 name 一致
            def.setName(name);
            boolean success = service.updateView(def);
            if (success) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "物化视图更新成功: " + name
                ));
            } else {
                return ResponseEntity.status(404).body(Map.of(
                        "success", false,
                        "message", "物化视图不存在: " + name
                ));
            }
        } catch (Exception e) {
            log.error("更新物化视图失败: name={}", name, e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", GENERIC_ERROR_MESSAGE
            ));
        }
    }

    /**
     * 删除物化视图。
     *
     * @param name 视图名称
     * @return 200 成功；404 不存在
     */
    @Operation(summary = "删除物化视图")
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> removeView(@PathVariable String name) {
        requireTenant();
        boolean success = service.removeView(name);
        if (success) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "物化视图删除成功: " + name
            ));
        } else {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "message", "物化视图不存在: " + name
            ));
        }
    }

    /**
     * 手动触发物化视图刷新。
     *
     * @param name     视图名称
     * @param operator 操作人（query param，默认 "api"）
     * @return 200 触发成功；404 视图不存在
     */
    @Operation(summary = "手动触发物化视图刷新")
    @PostMapping("/{name}/refresh")
    public ResponseEntity<Map<String, Object>> refreshView(@PathVariable String name,
                                                           @RequestParam(defaultValue = "api") String operator) {
        requireTenant();
        var event = service.refreshManually(name, operator);
        if (event == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "message", "物化视图不存在或触发失败: " + name
            ));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "刷新已触发: " + name,
                "eventId", event.getEventId(),
                "triggerTime", event.getTriggerTime().toString()
        ));
    }

    /**
     * 查询单个物化视图的刷新状态。
     *
     * @param name 视图名称
     * @return 200 状态信息
     */
    @Operation(summary = "查询单个物化视图的刷新状态")
    @GetMapping("/{name}/status")
    public ResponseEntity<Map<String, Object>> getViewStatus(@PathVariable String name) {
        requireTenant();
        Map<String, Object> status = new HashMap<>();
        status.put("viewName", name);
        ViewRefresher.RefreshResult result = service.getLastRefreshResult(name);
        if (result != null) {
            status.put("lastRefreshSuccess", result.isSuccess());
            status.put("lastRefreshDurationMs", result.getDurationMs());
            status.put("lastRefreshRetryCount", result.getRetryCount());
            status.put("lastRefreshTime", result.getCompletedAt().toString());
            if (result.getErrorMessage() != null) {
                status.put("lastRefreshError", result.getErrorMessage());
            }
        } else {
            status.put("lastRefreshSuccess", false);
            status.put("message", "从未刷新");
        }
        return ResponseEntity.ok(status);
    }

    /**
     * 查询全局状态（租户隔离，仅返回当前租户视图）。
     *
     * @return 200 全局状态
     */
    @Operation(summary = "查询全局状态")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getGlobalStatus() {
        requireTenant();
        Map<String, Object> status = new HashMap<>();
        status.put("started", service.isStarted());
        status.put("viewCount", service.viewCount());
        status.put("activeRefreshCount", service.getActiveRefreshCount());
        status.put("viewNames", service.listViews().stream().map(MaterializedViewDef::getName).toList());
        return ResponseEntity.ok(status);
    }

    /**
     * 从 TenantContext 校验当前租户；缺失时返回 403。
     */
    private static void requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, "缺少租户上下文，拒绝访问物化视图资源");
        }
    }
}