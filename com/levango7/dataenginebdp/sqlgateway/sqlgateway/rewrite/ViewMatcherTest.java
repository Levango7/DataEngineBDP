package com.shuqing.bigdata.sqlgateway.rewrite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ViewMatcher 单元测试。
 *
 * <p>验证物化视图匹配器在不同查询模式下的匹配行为：</p>
 * <ul>
 *   <li>完全匹配（维度与指标完全一致）；</li>
 *   <li>聚合上卷（查询维度是视图维度的子集）；</li>
 *   <li>源表不匹配时返回未匹配；</li>
 *   <li>视图不可用时不参与匹配。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class ViewMatcherTest {

    private final ViewMatcher matcher = new ViewMatcher();

    /**
     * 构造一个可用的物化视图定义。
     *
     * @param viewName    视图名
     * @param sourceTable 源表名
     * @param dimensions  维度列（逗号分隔）
     * @param measures    指标列（逗号分隔）
     * @return 物化视图定义
     */
    private MaterializedViewDefinition availableView(String viewName, String sourceTable,
                                                     String dimensions, String measures) {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
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
    @DisplayName("match — 完全匹配：维度与指标完全一致，评分 1.0")
    void match_exactMatch_shouldReturnScoreOne() {
        MaterializedViewDefinition view = availableView(
                "mv_sales_daily", "sales", "region", "amount");
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";

        ViewMatcher.MatchResult result = matcher.match(sql, view);

        assertThat(result.matched()).isTrue();
        assertThat(result.score()).isEqualTo(ViewMatcher.SCORE_EXACT);
        assertThat(result.ruleType()).isEqualTo(RewriteRuleType.EXACT_MATCH);
        assertThat(result.viewName()).isEqualTo("mv_sales_daily");
    }

    @Test
    @DisplayName("match — 聚合上卷：查询维度是视图维度子集，评分 0.8")
    void match_aggRollup_shouldReturnScoreRollup() {
        // 视图按 region, product 两维度聚合
        MaterializedViewDefinition view = availableView(
                "mv_sales_rp", "sales", "region,product", "amount");
        // 查询仅按 region 聚合（更粗粒度）
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";

        ViewMatcher.MatchResult result = matcher.match(sql, view);

        assertThat(result.matched()).isTrue();
        assertThat(result.score()).isEqualTo(ViewMatcher.SCORE_ROLLUP);
        assertThat(result.ruleType()).isEqualTo(RewriteRuleType.AGG_ROLLUP);
    }

    @Test
    @DisplayName("match — 源表不匹配时返回未匹配")
    void match_sourceTableMismatch_shouldNotMatch() {
        MaterializedViewDefinition view = availableView(
                "mv_orders", "orders", "region", "amount");
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";

        ViewMatcher.MatchResult result = matcher.match(sql, view);

        assertThat(result.matched()).isFalse();
        assertThat(result.reason()).contains("源表不匹配");
    }

    @Test
    @DisplayName("match — 视图未启用时不匹配")
    void match_viewDisabled_shouldNotMatch() {
        MaterializedViewDefinition view = availableView(
                "mv_sales", "sales", "region", "amount");
        view.setEnabled(false);
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";

        ViewMatcher.MatchResult result = matcher.match(sql, view);

        assertThat(result.matched()).isFalse();
    }

    @Test
    @DisplayName("match — 视图未刷新时不匹配")
    void match_viewNotRefreshed_shouldNotMatch() {
        MaterializedViewDefinition view = availableView(
                "mv_sales", "sales", "region", "amount");
        view.setLastRefreshTime(null);
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";

        ViewMatcher.MatchResult result = matcher.match(sql, view);

        assertThat(result.matched()).isFalse();
    }

    @Test
    @DisplayName("match — SQL 为空时不匹配")
    void match_emptySql_shouldNotMatch() {
        MaterializedViewDefinition view = availableView(
                "mv_sales", "sales", "region", "amount");

        ViewMatcher.MatchResult result = matcher.match("", view);

        assertThat(result.matched()).isFalse();
    }

    @Test
    @DisplayName("matchAll — 多候选按评分降序返回")
    void matchAll_multipleCandidates_shouldSortByScoreDesc() {
        MaterializedViewDefinition exactView = availableView(
                "mv_exact", "sales", "region", "amount");
        MaterializedViewDefinition rollupView = availableView(
                "mv_rollup", "sales", "region,product", "amount");
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";

        List<ViewMatcher.MatchResult> results = matcher.matchAll(sql,
                List.of(rollupView, exactView));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).score()).isGreaterThanOrEqualTo(results.get(1).score());
        assertThat(results.get(0).viewName()).isEqualTo("mv_exact");
    }

    @Test
    @DisplayName("matchAll — 空候选列表返回空")
    void matchAll_emptyCandidates_shouldReturnEmpty() {
        List<ViewMatcher.MatchResult> results = matcher.matchAll("SELECT 1", List.of());
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("match — SQL 解析失败时返回未匹配")
    void match_parseError_shouldNotMatch() {
        MaterializedViewDefinition view = availableView(
                "mv_sales", "sales", "region", "amount");
        String invalidSql = "SELECT FROM WHERE";

        ViewMatcher.MatchResult result = matcher.match(invalidSql, view);

        assertThat(result.matched()).isFalse();
        assertThat(result.reason()).contains("SQL 解析失败");
    }
}