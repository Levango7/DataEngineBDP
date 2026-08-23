package com.levango7.dataenginebdp.flinkcdc.materializedview.controller;

import com.levango7.dataenginebdp.flinkcdc.materializedview.model.MaterializedViewDef;
import com.levango7.dataenginebdp.flinkcdc.materializedview.refresh.ViewRefresher;
import com.levango7.dataenginebdp.flinkcdc.materializedview.service.MaterializedViewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            log.error("注册物化视图失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "注册失败: " + e.getMessage()
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
        return ResponseEntity.ok(service.listViews());
    }

    /**
     * 查询单个物化视图。
     *
     * @param name 视图名称
     * @return 200 视图定义；404 不存在
     */
    @Operation(summary = "查询单个物化视图")
    @GetMapping("/{name}")
    public ResponseEntity<?> getView(@PathVariable String name) {
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
            log.error("更新物化视图失败: {}", name, e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "更新失败: " + e.getMessage()
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
     * 查询全局状态。
     *
     * @return 200 全局状态
     */
    @Operation(summary = "查询全局状态")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getGlobalStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("started", service.isStarted());
        status.put("viewCount", service.viewCount());
        status.put("activeRefreshCount", service.getActiveRefreshCount());
        status.put("viewNames", service.listViews().stream().map(MaterializedViewDef::getName).toList());
        return ResponseEntity.ok(status);
    }
}