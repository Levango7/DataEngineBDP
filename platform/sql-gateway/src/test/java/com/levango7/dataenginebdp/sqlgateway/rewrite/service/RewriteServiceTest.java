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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * RewriteService 单元测试。
 *
 * <p>使用 Mockito 模拟仓储依赖，ViewMatcher/ViewRouter/QueryRewriter 使用真实组件，
 * 验证端到端改写流程与视图/规则管理逻辑。</p>
 *
 * @author shuqing-bigdata
 */
@ExtendWith(MockitoExtension.class)
class RewriteServiceTest {

    @Mock
    private MaterializedViewRepository viewRepository;

    @Mock
    private RewriteRuleRepository ruleRepository;

    private RewriteService rewriteService;
    private RewriteConfig rewriteConfig;

    @BeforeEach
    void setUp() {
        ViewMatcher viewMatcher = new ViewMatcher();
        ViewRouter viewRouter = new ViewRouter(viewMatcher);
        QueryRewriter queryRewriter = new QueryRewriter();
        rewriteConfig = new RewriteConfig();
        rewriteService = new RewriteService(viewMatcher, viewRouter, queryRewriter,
                viewRepository, ruleRepository, rewriteConfig);
    }

    /**
     * 构造一个可用的物化视图定义。
     */
    private MaterializedViewDefinition availableView(String viewName, String sourceTable,
                                                     String dimensions, String measures) {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        view.setId(1L);
        view.setViewName(viewName);
        view.setSourceTable(sourceTable);
        view.setDimensionColumns(dimensions);
        view.setMeasureColumns(measures);
        view.setEnabled(true);
        view.setLastRefreshTime(Instant.now());
        view.setPriority(10);
        return view;
    }

    @Test
    @DisplayName("rewrite — 命中物化视图时返回改写后 SQL")
    void rewrite_matchedView_shouldReturnRewrittenSql() {
        MaterializedViewDefinition view = availableView(
                "mv_sales_daily", "sales", "region", "amount");
        when(viewRepository.findByEnabledTrue()).thenReturn(List.of(view));
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of());

        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";
        RewriteResult result = rewriteService.rewrite(sql);

        assertThat(result.isRewritten()).isTrue();
        assertThat(result.getRewrittenSql()).contains("mv_sales_daily");
        assertThat(result.getMatchedView()).isEqualTo("mv_sales_daily");
    }

    @Test
    @DisplayName("rewrite — 无候选视图时透传原始 SQL")
    void rewrite_noCandidates_shouldPassThrough() {
        when(viewRepository.findByEnabledTrue()).thenReturn(List.of());

        String sql = "SELECT * FROM sales";
        RewriteResult result = rewriteService.rewrite(sql);

        assertThat(result.isRewritten()).isFalse();
        assertThat(result.getRewrittenSql()).isEqualTo(sql);
    }

    @Test
    @DisplayName("rewrite — 改写未启用时透传原始 SQL")
    void rewrite_disabled_shouldPassThrough() {
        rewriteConfig.setEnabled(false);
        String sql = "SELECT * FROM sales";
        RewriteResult result = rewriteService.rewrite(sql);

        assertThat(result.isRewritten()).isFalse();
        assertThat(result.getRewrittenSql()).isEqualTo(sql);
    }

    @Test
    @DisplayName("rewrite — SQL 为空时返回失败")
    void rewrite_emptySql_shouldReturnFailure() {
        RewriteResult result = rewriteService.rewrite("");
        assertThat(result.isRewritten()).isFalse();
        assertThat(result.getError()).isNotNull();
    }

    @Test
    @DisplayName("route — 返回路由决策")
    void route_shouldReturnDecision() {
        MaterializedViewDefinition view = availableView(
                "mv_sales_daily", "sales", "region", "amount");
        when(viewRepository.findByEnabledTrue()).thenReturn(List.of(view));
        when(ruleRepository.findByEnabledTrue()).thenReturn(List.of());

        Optional<ViewMatcher.MatchResult> result =
                rewriteService.route("SELECT region, sum(amount) FROM sales GROUP BY region");

        assertThat(result).isPresent();
        assertThat(result.get().viewName()).isEqualTo("mv_sales_daily");
    }

    @Test
    @DisplayName("listViews — 按优先级升序排列")
    void listViews_shouldSortByPriority() {
        MaterializedViewDefinition v1 = availableView("mv1", "t1", "a", "b");
        v1.setPriority(50);
        MaterializedViewDefinition v2 = availableView("mv2", "t2", "a", "b");
        v2.setPriority(10);
        when(viewRepository.findAll()).thenReturn(new ArrayList<>(List.of(v1, v2)));

        List<MaterializedViewDefinition> result = rewriteService.listViews();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getPriority()).isEqualTo(10);
        assertThat(result.get(1).getPriority()).isEqualTo(50);
    }

    @Test
    @DisplayName("addView — 设置默认 enabled/priority/refreshStrategy/lastRefreshTime")
    void addView_shouldSetDefaults() {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        view.setViewName("mv_new");
        view.setSourceTable("sales");
        when(viewRepository.existsByViewName("mv_new")).thenReturn(false);
        when(viewRepository.save(any(MaterializedViewDefinition.class))).thenAnswer(inv -> {
            MaterializedViewDefinition v = inv.getArgument(0);
            v.setId(1L);
            return v;
        });

        MaterializedViewDefinition saved = rewriteService.addView(view);

        assertThat(saved.getEnabled()).isTrue();
        assertThat(saved.getPriority()).isEqualTo(100);
        assertThat(saved.getRefreshStrategy()).isEqualTo("FULL");
        assertThat(saved.getLastRefreshTime()).isNotNull();
    }

    @Test
    @DisplayName("addView — 视图名已存在时抛异常")
    void addView_duplicateName_shouldThrow() {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        view.setViewName("mv_exists");
        when(viewRepository.existsByViewName("mv_exists")).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rewriteService.addView(view))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    @DisplayName("addView — 视图名为空时抛异常")
    void addView_emptyName_shouldThrow() {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rewriteService.addView(view))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("deleteView — 存在时返回 true")
    void deleteView_exists_shouldReturnTrue() {
        MaterializedViewDefinition view = availableView("mv1", "t1", "a", "b");
        when(viewRepository.findByViewName("mv1")).thenReturn(Optional.of(view));

        boolean deleted = rewriteService.deleteView("mv1");
        assertThat(deleted).isTrue();
    }

    @Test
    @DisplayName("deleteView — 不存在时返回 false")
    void deleteView_notExists_shouldReturnFalse() {
        when(viewRepository.findByViewName("mv_unknown")).thenReturn(Optional.empty());

        boolean deleted = rewriteService.deleteView("mv_unknown");
        assertThat(deleted).isFalse();
    }

    @Test
    @DisplayName("listRules — 按优先级升序排列")
    void listRules_shouldSortByPriority() {
        RewriteRule r1 = new RewriteRule("r1", "EXACT_MATCH", "mv1", "t1", 50, true, null);
        r1.setId(1L);
        RewriteRule r2 = new RewriteRule("r2", "EXACT_MATCH", "mv2", "t2", 10, true, null);
        r2.setId(2L);
        when(ruleRepository.findAll()).thenReturn(new ArrayList<>(List.of(r1, r2)));

        List<RewriteRule> result = rewriteService.listRules();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getPriority()).isEqualTo(10);
    }

    @Test
    @DisplayName("addRule — 设置默认 enabled/priority")
    void addRule_shouldSetDefaults() {
        RewriteRule rule = new RewriteRule();
        rule.setRuleName("rule_new");
        rule.setRuleType("EXACT_MATCH");
        rule.setTargetView("mv1");
        when(ruleRepository.existsByRuleName("rule_new")).thenReturn(false);
        when(ruleRepository.save(any(RewriteRule.class))).thenAnswer(inv -> {
            RewriteRule r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });

        RewriteRule saved = rewriteService.addRule(rule);

        assertThat(saved.getEnabled()).isTrue();
        assertThat(saved.getPriority()).isEqualTo(100);
    }

    @Test
    @DisplayName("addRule — 规则名已存在时抛异常")
    void addRule_duplicateName_shouldThrow() {
        RewriteRule rule = new RewriteRule();
        rule.setRuleName("rule_exists");
        when(ruleRepository.existsByRuleName("rule_exists")).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rewriteService.addRule(rule))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("refreshView — 更新最近刷新时间")
    void refreshView_shouldUpdateLastRefreshTime() {
        MaterializedViewDefinition view = availableView("mv1", "t1", "a", "b");
        Instant oldTime = view.getLastRefreshTime();
        when(viewRepository.findByViewName("mv1")).thenReturn(Optional.of(view));
        when(viewRepository.save(any(MaterializedViewDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<MaterializedViewDefinition> result = rewriteService.refreshView("mv1");

        assertThat(result).isPresent();
        assertThat(result.get().getLastRefreshTime()).isAfterOrEqualTo(oldTime);
    }
}