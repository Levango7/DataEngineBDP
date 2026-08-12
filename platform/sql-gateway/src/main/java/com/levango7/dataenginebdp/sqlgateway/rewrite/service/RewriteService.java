package com.levango7.dataenginebdp.sqlgateway.rewrite.service;

import com.levango7.dataenginebdp.sqlgateway.rewrite.MaterializedViewDefinition;
import com.levango7.dataenginebdp.sqlgateway.rewrite.MaterializedViewRepository;
import com.levango7.dataenginebdp.sqlgateway.rewrite.QueryRewriter;
import com.levango7.dataenginebdp.sqlgateway.rewrite.RewriteResult;
import com.levango7.dataenginebdp.sqlgateway.rewrite.RewriteRule;
import com.levango7.dataenginebdp.sqlgateway.rewrite.RewriteRuleRepository;
import com.levango7.dataenginebdp.sqlgateway.rewrite.ViewMatcher;
import com.levango7.dataenginebdp.sqlgateway.rewrite.ViewRouter;
import com.levango7.dataenginebdp.sqlgateway.rewrite.config.RewriteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 查询改写业务服务。
 *
 * <p>整合 {@link ViewMatcher}、{@link ViewRouter}、{@link QueryRewriter}，
 * 提供完整的查询改写与物化视图定义管理能力：</p>
 * <ul>
 *   <li>{@link #rewrite(String)}：对用户 SQL 执行自动改写，返回改写结果；</li>
 *   <li>{@link #route(String)}：仅返回路由决策（不改写 SQL），用于调试与可观测；</li>
 *   <li>物化视图定义 CRUD：{@link #addView}、{@link #getView}、{@link #listViews}、
 *       {@link #updateView}、{@link #deleteView}、{@link #refreshView}；</li>
 *   <li>改写规则 CRUD：{@link #addRule}、{@link #listRules}、{@link #deleteRule}。</li>
 * </ul>
 *
 * <p>"用户无感知"约束：仅当 {@link RewriteConfig#isEnabled()} 为 true 且匹配评分
 * 达到阈值时才执行改写，否则透传原始 SQL。</p>
 *
 * @author shuqing-bigdata
 */
@Service
public class RewriteService {

    private static final Logger log = LoggerFactory.getLogger(RewriteService.class);

    private final ViewMatcher viewMatcher;
    private final ViewRouter viewRouter;
    private final QueryRewriter queryRewriter;
    private final MaterializedViewRepository viewRepository;
    private final RewriteRuleRepository ruleRepository;
    private final RewriteConfig rewriteConfig;

    /**
     * 构造服务。
     *
     * @param viewMatcher     物化视图匹配器
     * @param viewRouter      自动路由决策器
     * @param queryRewriter   查询改写器
     * @param viewRepository  物化视图定义仓储
     * @param ruleRepository  改写规则仓储
     * @param rewriteConfig   改写配置
     */
    public RewriteService(ViewMatcher viewMatcher, ViewRouter viewRouter,
                          QueryRewriter queryRewriter,
                          MaterializedViewRepository viewRepository,
                          RewriteRuleRepository ruleRepository,
                          RewriteConfig rewriteConfig) {
        this.viewMatcher = viewMatcher;
        this.viewRouter = viewRouter;
        this.queryRewriter = queryRewriter;
        this.viewRepository = viewRepository;
        this.ruleRepository = ruleRepository;
        this.rewriteConfig = rewriteConfig;
        // 同步路由器阈值与配置
        if (rewriteConfig != null) {
            viewRouter.setMinMatchScore(rewriteConfig.getMinMatchScore());
        }
    }

    // ===================== 查询改写 =====================

    /**
     * 对用户 SQL 执行自动改写。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>若改写未启用或 SQL 为空，直接返回未改写结果；</li>
     *   <li>加载所有已启用的物化视图定义作为候选；</li>
     *   <li>调用 {@link ViewRouter#route} 选择最优匹配；</li>
     *   <li>调用 {@link QueryRewriter#rewrite} 生成改写后 SQL；</li>
     *   <li>返回 {@link RewriteResult}。</li>
     * </ol>
     *
     * @param sql 用户 SQL
     * @return 改写结果
     */
    public RewriteResult rewrite(String sql) {
        long start = System.currentTimeMillis();
        if (sql == null || sql.isBlank()) {
            return RewriteResult.failure(sql, "SQL 为空");
        }
        if (!rewriteConfig.isEnabled()) {
            return RewriteResult.notRewritten(sql);
        }

        try {
            List<MaterializedViewDefinition> candidates = loadCandidates(sql);
            if (candidates.isEmpty()) {
                return RewriteResult.notRewritten(sql);
            }

            List<RewriteRule> rules = ruleRepository.findByEnabledTrue();
            Optional<ViewMatcher.MatchResult> best = viewRouter.route(sql, candidates, rules);
            if (best.isEmpty()) {
                return RewriteResult.notRewritten(sql);
            }

            RewriteResult result = queryRewriter.rewrite(sql, best.get());
            // 用服务层时间覆盖（包含加载候选耗时）
            return RewriteResult.builder()
                    .originalSql(result.getOriginalSql())
                    .rewrittenSql(result.getRewrittenSql())
                    .rewritten(result.isRewritten())
                    .matchedView(result.getMatchedView())
                    .rulesApplied(result.getRulesApplied())
                    .reason(result.getReason())
                    .matchScore(result.getMatchScore())
                    .equivalent(result.isEquivalent())
                    .durationMs(System.currentTimeMillis() - start)
                    .candidateViews(result.getCandidateViews())
                    .error(result.getError())
                    .build();
        } catch (Exception e) {
            log.error("改写异常 sql={} err={}", abbreviate(sql, 80), e.toString());
            return RewriteResult.failure(sql, e.getMessage());
        }
    }

    /**
     * 仅返回路由决策（不改写 SQL），用于调试与可观测。
     *
     * @param sql 用户 SQL
     * @return 最优匹配结果；无匹配返回 {@link Optional#empty()}
     */
    public Optional<ViewMatcher.MatchResult> route(String sql) {
        if (sql == null || sql.isBlank() || !rewriteConfig.isEnabled()) {
            return Optional.empty();
        }
        List<MaterializedViewDefinition> candidates = loadCandidates(sql);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<RewriteRule> rules = ruleRepository.findByEnabledTrue();
        return viewRouter.route(sql, candidates, rules);
    }

    /**
     * 列出所有候选匹配结果（按评分降序），用于调试。
     *
     * @param sql 用户 SQL
     * @return 匹配结果列表
     */
    public List<ViewMatcher.MatchResult> listCandidates(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        List<MaterializedViewDefinition> candidates = loadCandidates(sql);
        return viewMatcher.matchAll(sql, candidates);
    }

    /**
     * 加载候选物化视图列表，应用排除表与最大候选数限制。
     *
     * @param sql 用户 SQL
     * @return 候选视图列表
     */
    private List<MaterializedViewDefinition> loadCandidates(String sql) {
        List<MaterializedViewDefinition> all = viewRepository.findByEnabledTrue();
        if (all.isEmpty()) {
            return List.of();
        }
        List<MaterializedViewDefinition> filtered = new ArrayList<>();
        for (MaterializedViewDefinition view : all) {
            if (view.getSourceTable() != null
                    && rewriteConfig.isExcluded(view.getSourceTable())) {
                continue;
            }
            filtered.add(view);
            if (filtered.size() >= rewriteConfig.getMaxCandidates()) {
                break;
            }
        }
        return filtered;
    }

    // ===================== 物化视图定义管理 =====================

    /**
     * 列出所有物化视图定义（按优先级升序）。
     *
     * @return 物化视图定义列表
     */
    public List<MaterializedViewDefinition> listViews() {
        List<MaterializedViewDefinition> all = viewRepository.findAll();
        all.sort(Comparator.comparingInt(v ->
                v.getPriority() == null ? Integer.MAX_VALUE : v.getPriority()));
        return all;
    }

    /**
     * 按视图名获取物化视图定义。
     *
     * @param viewName 视图名
     * @return 物化视图定义；不存在返回 {@link Optional#empty()}
     */
    public Optional<MaterializedViewDefinition> getView(String viewName) {
        return viewRepository.findByViewName(viewName);
    }

    /**
     * 新增物化视图定义。
     *
     * @param view 视图定义（若 id 为空则由数据库自增分配）
     * @return 已保存的视图定义
     */
    public MaterializedViewDefinition addView(MaterializedViewDefinition view) {
        if (view.getViewName() == null || view.getViewName().isBlank()) {
            throw new IllegalArgumentException("视图名不能为空");
        }
        if (viewRepository.existsByViewName(view.getViewName())) {
            throw new IllegalStateException("视图名已存在: " + view.getViewName());
        }
        if (view.getId() != null) {
            view.setId(null);
        }
        if (view.getEnabled() == null) {
            view.setEnabled(Boolean.TRUE);
        }
        if (view.getPriority() == null) {
            view.setPriority(100);
        }
        if (view.getRefreshStrategy() == null) {
            view.setRefreshStrategy("FULL");
        }
        if (view.getLastRefreshTime() == null) {
            view.setLastRefreshTime(Instant.now());
        }
        MaterializedViewDefinition saved = viewRepository.save(view);
        log.info("物化视图定义已添加: id={} view={} sourceTable={} enabled={}",
                saved.getId(), saved.getViewName(), saved.getSourceTable(), saved.getEnabled());
        return saved;
    }

    /**
     * 更新物化视图定义。
     *
     * @param viewName 视图名
     * @param view     新的视图定义字段
     * @return 更新后的视图定义；不存在返回 {@link Optional#empty()}
     */
    public Optional<MaterializedViewDefinition> updateView(String viewName,
                                                          MaterializedViewDefinition view) {
        Optional<MaterializedViewDefinition> existing = viewRepository.findByViewName(viewName);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        MaterializedViewDefinition entity = existing.get();
        if (view.getSourceTable() != null) {
            entity.setSourceTable(view.getSourceTable());
        }
        if (view.getDefinitionSql() != null) {
            entity.setDefinitionSql(view.getDefinitionSql());
        }
        if (view.getQuerySql() != null) {
            entity.setQuerySql(view.getQuerySql());
        }
        if (view.getDimensionColumns() != null) {
            entity.setDimensionColumns(view.getDimensionColumns());
        }
        if (view.getMeasureColumns() != null) {
            entity.setMeasureColumns(view.getMeasureColumns());
        }
        if (view.getRefreshStrategy() != null) {
            entity.setRefreshStrategy(view.getRefreshStrategy());
        }
        if (view.getEnabled() != null) {
            entity.setEnabled(view.getEnabled());
        }
        if (view.getPriority() != null) {
            entity.setPriority(view.getPriority());
        }
        if (view.getDescription() != null) {
            entity.setDescription(view.getDescription());
        }
        MaterializedViewDefinition saved = viewRepository.save(entity);
        log.info("物化视图定义已更新: view={}", viewName);
        return Optional.of(saved);
    }

    /**
     * 删除物化视图定义。
     *
     * @param viewName 视图名
     * @return {@code true} 表示删除成功；不存在返回 {@code false}
     */
    public boolean deleteView(String viewName) {
        Optional<MaterializedViewDefinition> existing = viewRepository.findByViewName(viewName);
        if (existing.isEmpty()) {
            return false;
        }
        viewRepository.delete(existing.get());
        log.info("物化视图定义已删除: view={}", viewName);
        return true;
    }

    /**
     * 刷新物化视图的最近刷新时间。
     *
     * @param viewName 视图名
     * @return 更新后的视图定义；不存在返回 {@link Optional#empty()}
     */
    public Optional<MaterializedViewDefinition> refreshView(String viewName) {
        Optional<MaterializedViewDefinition> existing = viewRepository.findByViewName(viewName);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        MaterializedViewDefinition entity = existing.get();
        entity.setLastRefreshTime(Instant.now());
        MaterializedViewDefinition saved = viewRepository.save(entity);
        log.info("物化视图刷新时间已更新: view={} lastRefreshTime={}",
                viewName, saved.getLastRefreshTime());
        return Optional.of(saved);
    }

    // ===================== 改写规则管理 =====================

    /**
     * 列出所有改写规则（按优先级升序）。
     *
     * @return 改写规则列表
     */
    public List<RewriteRule> listRules() {
        List<RewriteRule> all = ruleRepository.findAll();
        all.sort(Comparator.comparingInt(r ->
                r.getPriority() == null ? Integer.MAX_VALUE : r.getPriority()));
        return all;
    }

    /**
     * 按规则名获取改写规则。
     *
     * @param ruleName 规则名
     * @return 改写规则；不存在返回 {@link Optional#empty()}
     */
    public Optional<RewriteRule> getRule(String ruleName) {
        return ruleRepository.findByRuleName(ruleName);
    }

    /**
     * 新增改写规则。
     *
     * @param rule 改写规则
     * @return 已保存的改写规则
     */
    public RewriteRule addRule(RewriteRule rule) {
        if (rule.getRuleName() == null || rule.getRuleName().isBlank()) {
            throw new IllegalArgumentException("规则名不能为空");
        }
        if (ruleRepository.existsByRuleName(rule.getRuleName())) {
            throw new IllegalStateException("规则名已存在: " + rule.getRuleName());
        }
        if (rule.getId() != null) {
            rule.setId(null);
        }
        if (rule.getEnabled() == null) {
            rule.setEnabled(Boolean.TRUE);
        }
        if (rule.getPriority() == null) {
            rule.setPriority(100);
        }
        RewriteRule saved = ruleRepository.save(rule);
        log.info("改写规则已添加: id={} ruleName={} targetView={} priority={}",
                saved.getId(), saved.getRuleName(), saved.getTargetView(), saved.getPriority());
        return saved;
    }

    /**
     * 删除改写规则。
     *
     * @param ruleName 规则名
     * @return {@code true} 表示删除成功；不存在返回 {@code false}
     */
    public boolean deleteRule(String ruleName) {
        Optional<RewriteRule> existing = ruleRepository.findByRuleName(ruleName);
        if (existing.isEmpty()) {
            return false;
        }
        ruleRepository.delete(existing.get());
        log.info("改写规则已删除: ruleName={}", ruleName);
        return true;
    }

    /**
     * 截断 SQL 用于日志输出。
     *
     * @param s      原始字符串
     * @param maxLen 最大长度
     * @return 截断后的字符串
     */
    private String abbreviate(String s, int maxLen) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}