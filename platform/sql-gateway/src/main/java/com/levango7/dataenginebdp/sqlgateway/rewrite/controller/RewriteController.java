package com.levango7.dataenginebdp.sqlgateway.rewrite.controller;

import com.levango7.dataenginebdp.sqlgateway.rewrite.MaterializedViewDefinition;
import com.levango7.dataenginebdp.sqlgateway.rewrite.RewriteResult;
import com.levango7.dataenginebdp.sqlgateway.rewrite.RewriteRule;
import com.levango7.dataenginebdp.sqlgateway.rewrite.ViewMatcher;
import com.levango7.dataenginebdp.sqlgateway.rewrite.service.RewriteService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 查询改写与物化视图自动路由 REST 控制器。
 *
 * <p>暴露以下端点（前缀 {@code /api/v1/rewrite}）：</p>
 *
 * <p><b>改写与路由</b></p>
 * <ul>
 *   <li>{@code POST /execute}：对 SQL 执行自动改写，返回改写后 SQL；</li>
 *   <li>{@code POST /route}：仅返回路由决策（不改写），用于调试；</li>
 *   <li>{@code POST /candidates}：列出所有候选匹配结果（按评分降序）。</li>
 * </ul>
 *
 * <p><b>物化视图定义管理</b></p>
 * <ul>
 *   <li>{@code GET /views}：列出所有视图定义；</li>
 *   <li>{@code GET /views/{viewName}}：获取单个视图定义；</li>
 *   <li>{@code POST /views}：新增视图定义；</li>
 *   <li>{@code PUT /views/{viewName}}：更新视图定义；</li>
 *   <li>{@code DELETE /views/{viewName}}：删除视图定义；</li>
 *   <li>{@code POST /views/{viewName}/refresh}：刷新视图最近刷新时间。</li>
 * </ul>
 *
 * <p><b>改写规则管理</b></p>
 * <ul>
 *   <li>{@code GET /rules}：列出所有改写规则；</li>
 *   <li>{@code GET /rules/{ruleName}}：获取单个改写规则；</li>
 *   <li>{@code POST /rules}：新增改写规则；</li>
 *   <li>{@code DELETE /rules/{ruleName}}：删除改写规则。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@RestController
@Tag(name = "SQL网关-查询改写", description = "SQL改写与物化视图路由")
@RequestMapping("/api/v1/rewrite")
public class RewriteController {

    private static final Logger log = LoggerFactory.getLogger(RewriteController.class);

    private final RewriteService rewriteService;

    /**
     * 构造控制器。
     *
     * @param rewriteService 改写业务服务
     */
    public RewriteController(RewriteService rewriteService) {
        this.rewriteService = rewriteService;
    }

    // ===================== 改写与路由 =====================

    /**
     * 对 SQL 执行自动改写。
     *
     * <p>若命中物化视图则返回改写后 SQL，否则透传原始 SQL。
     * 改写对用户透明，结果语义不变。</p>
     *
     * @param request 改写请求
     * @return 改写结果
     */
    @Operation(summary = "对 SQL 执行自动改写")
    @PostMapping("/execute")
    public ResponseEntity<RewriteResult> execute(@Valid @RequestBody RewriteRequest request) {
        long start = System.currentTimeMillis();
        RewriteResult result = rewriteService.rewrite(request.getSql());
        log.info("改写请求完成 rewritten={} view={} duration={}ms",
                result.isRewritten(), result.getMatchedView(),
                System.currentTimeMillis() - start);
        return ResponseEntity.ok(result);
    }

    /**
     * 仅返回路由决策（不改写 SQL），用于调试与可观测。
     *
     * @param request 改写请求
     * @return 路由决策信息；无匹配返回 200 + notMatched 标记
     */
    @Operation(summary = "仅返回路由决策（不改写 SQL），用于调试与可观测")
    @PostMapping("/route")
    public ResponseEntity<Map<String, Object>> route(@Valid @RequestBody RewriteRequest request) {
        Optional<ViewMatcher.MatchResult> match = rewriteService.route(request.getSql());
        if (match.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "matched", false,
                    "sql", request.getSql(),
                    "reason", "未命中任何物化视图"
            ));
        }
        ViewMatcher.MatchResult m = match.get();
        return ResponseEntity.ok(Map.of(
                "matched", true,
                "sql", request.getSql(),
                "viewName", m.viewName(),
                "score", m.score(),
                "ruleType", m.ruleType() == null ? "UNKNOWN" : m.ruleType().name(),
                "reason", m.reason()
        ));
    }

    /**
     * 列出所有候选匹配结果（按评分降序），用于调试。
     *
     * @param request 改写请求
     * @return 候选匹配列表
     */
    @Operation(summary = "列出所有候选匹配结果（按评分降序），用于调试")
    @PostMapping("/candidates")
    public ResponseEntity<List<Map<String, Object>>> candidates(
            @Valid @RequestBody RewriteRequest request) {
        List<ViewMatcher.MatchResult> results = rewriteService.listCandidates(request.getSql());
        List<Map<String, Object>> response = results.stream()
                .map(m -> Map.<String, Object>of(
                        "viewName", m.viewName(),
                        "score", m.score(),
                        "ruleType", m.ruleType() == null ? "UNKNOWN" : m.ruleType().name(),
                        "reason", m.reason()
                ))
                .toList();
        return ResponseEntity.ok(response);
    }

    // ===================== 物化视图定义管理 =====================

    /**
     * 列出所有物化视图定义。
     *
     * @return 视图定义列表
     */
    @Operation(summary = "列出所有物化视图定义")
    @GetMapping("/views")
    public ResponseEntity<List<MaterializedViewDefinition>> listViews() {
        return ResponseEntity.ok(rewriteService.listViews());
    }

    /**
     * 按视图名获取物化视图定义。
     *
     * @param viewName 视图名
     * @return 视图定义；不存在返回 404
     */
    @Operation(summary = "按视图名获取物化视图定义")
    @GetMapping("/views/{viewName}")
    public ResponseEntity<MaterializedViewDefinition> getView(@PathVariable String viewName) {
        Optional<MaterializedViewDefinition> view = rewriteService.getView(viewName);
        return view.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 新增物化视图定义。
     *
     * @param view 视图定义
     * @return 已保存的视图定义；视图名已存在返回 409
     */
    @Operation(summary = "新增物化视图定义")
    @PostMapping("/views")
    public ResponseEntity<?> addView(@RequestBody MaterializedViewDefinition view) {
        try {
            MaterializedViewDefinition saved = rewriteService.addView(view);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 更新物化视图定义。
     *
     * @param viewName 视图名
     * @param view     新的视图定义字段
     * @return 更新后的视图定义；不存在返回 404
     */
    @Operation(summary = "更新物化视图定义")
    @PutMapping("/views/{viewName}")
    public ResponseEntity<MaterializedViewDefinition> updateView(
            @PathVariable String viewName,
            @RequestBody MaterializedViewDefinition view) {
        Optional<MaterializedViewDefinition> updated = rewriteService.updateView(viewName, view);
        return updated.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 删除物化视图定义。
     *
     * @param viewName 视图名
     * @return 204 删除成功；不存在返回 404
     */
    @Operation(summary = "删除物化视图定义")
    @DeleteMapping("/views/{viewName}")
    public ResponseEntity<Void> deleteView(@PathVariable String viewName) {
        boolean deleted = rewriteService.deleteView(viewName);
        return deleted ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * 刷新物化视图的最近刷新时间。
     *
     * @param viewName 视图名
     * @return 更新后的视图定义；不存在返回 404
     */
    @Operation(summary = "刷新物化视图的最近刷新时间")
    @PostMapping("/views/{viewName}/refresh")
    public ResponseEntity<MaterializedViewDefinition> refreshView(@PathVariable String viewName) {
        Optional<MaterializedViewDefinition> refreshed = rewriteService.refreshView(viewName);
        return refreshed.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ===================== 改写规则管理 =====================

    /**
     * 列出所有改写规则。
     *
     * @return 改写规则列表
     */
    @Operation(summary = "列出所有改写规则")
    @GetMapping("/rules")
    public ResponseEntity<List<RewriteRule>> listRules() {
        return ResponseEntity.ok(rewriteService.listRules());
    }

    /**
     * 按规则名获取改写规则。
     *
     * @param ruleName 规则名
     * @return 改写规则；不存在返回 404
     */
    @Operation(summary = "按规则名获取改写规则")
    @GetMapping("/rules/{ruleName}")
    public ResponseEntity<RewriteRule> getRule(@PathVariable String ruleName) {
        Optional<RewriteRule> rule = rewriteService.getRule(ruleName);
        return rule.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 新增改写规则。
     *
     * @param rule 改写规则
     * @return 已保存的改写规则；规则名已存在返回 409
     */
    @Operation(summary = "新增改写规则")
    @PostMapping("/rules")
    public ResponseEntity<?> addRule(@RequestBody RewriteRule rule) {
        try {
            RewriteRule saved = rewriteService.addRule(rule);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除改写规则。
     *
     * @param ruleName 规则名
     * @return 204 删除成功；不存在返回 404
     */
    @Operation(summary = "删除改写规则")
    @DeleteMapping("/rules/{ruleName}")
    public ResponseEntity<Void> deleteRule(@PathVariable String ruleName) {
        boolean deleted = rewriteService.deleteRule(ruleName);
        return deleted ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}