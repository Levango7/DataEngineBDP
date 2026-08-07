package com.shuqing.bigdata.sqlgateway.rewrite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryRewriter 单元测试。
 *
 * <p>验证查询改写器在不同匹配类型下的改写行为：</p>
 * <ul>
 *   <li>完全匹配：将 FROM 子句源表替换为视图名；</li>
 *   <li>未匹配：透传原始 SQL；</li>
 *   <li>SQL 为空：返回失败结果；</li>
 *   <li>extractTables：解析 SQL 提取表名。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class QueryRewriterTest {

    private final QueryRewriter rewriter = new QueryRewriter();

    /**
     * 构造一个可用的物化视图定义。
     */
    private MaterializedViewDefinition availableView(String viewName, String sourceTable) {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        view.setViewName(viewName);
        view.setSourceTable(sourceTable);
        view.setDimensionColumns("region");
        view.setMeasureColumns("amount");
        view.setEnabled(true);
        view.setLastRefreshTime(Instant.now());
        view.setPriority(10);
        return view;
    }

    @Test
    @DisplayName("rewrite — 完全匹配：FROM 子句源表替换为视图名")
    void rewrite_exactMatch_shouldReplaceTableWithView() {
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";
        MaterializedViewDefinition view = availableView("mv_sales_daily", "sales");
        ViewMatcher.MatchResult match = ViewMatcher.MatchResult.matched(
                "mv_sales_daily", 1.0, RewriteRuleType.EXACT_MATCH, "完全匹配", view);

        RewriteResult result = rewriter.rewrite(sql, match);

        assertThat(result.isRewritten()).isTrue();
        assertThat(result.getRewrittenSql()).contains("mv_sales_daily");
        assertThat(result.getRewrittenSql()).doesNotContain("FROM sales");
        assertThat(result.getMatchedView()).isEqualTo("mv_sales_daily");
        assertThat(result.getRulesApplied()).contains("EXACT_MATCH");
        assertThat(result.isEquivalent()).isTrue();
    }

    @Test
    @DisplayName("rewrite — 聚合上卷：替换 FROM 为视图名")
    void rewrite_aggRollup_shouldReplaceTableWithView() {
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";
        MaterializedViewDefinition view = availableView("mv_sales_rp", "sales");
        view.setDimensionColumns("region,product");
        ViewMatcher.MatchResult match = ViewMatcher.MatchResult.matched(
                "mv_sales_rp", 0.8, RewriteRuleType.AGG_ROLLUP, "聚合上卷", view);

        RewriteResult result = rewriter.rewrite(sql, match);

        assertThat(result.isRewritten()).isTrue();
        assertThat(result.getRewrittenSql()).contains("mv_sales_rp");
        assertThat(result.getRulesApplied()).contains("AGG_ROLLUP");
    }

    @Test
    @DisplayName("rewrite — 未匹配时透传原始 SQL")
    void rewrite_notMatched_shouldPassThrough() {
        String sql = "SELECT * FROM sales";
        RewriteResult result = rewriter.rewrite(sql, null);

        assertThat(result.isRewritten()).isFalse();
        assertThat(result.getRewrittenSql()).isEqualTo(sql);
    }

    @Test
    @DisplayName("rewrite — SQL 为空时返回失败")
    void rewrite_emptySql_shouldReturnFailure() {
        RewriteResult result = rewriter.rewrite("", null);
        assertThat(result.isRewritten()).isFalse();
        assertThat(result.getError()).isNotNull();
    }

    @Test
    @DisplayName("rewrite — 视图定义缺少源表时返回失败")
    void rewrite_missingSourceTable_shouldReturnFailure() {
        String sql = "SELECT * FROM sales";
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        view.setViewName("mv");
        view.setEnabled(true);
        view.setLastRefreshTime(Instant.now());
        ViewMatcher.MatchResult match = ViewMatcher.MatchResult.matched(
                "mv", 1.0, RewriteRuleType.EXACT_MATCH, "匹配", view);

        RewriteResult result = rewriter.rewrite(sql, match);

        assertThat(result.isRewritten()).isFalse();
        assertThat(result.getError()).isNotNull();
    }

    @Test
    @DisplayName("rewriteTable — 直接替换表名")
    void rewriteTable_shouldReplaceTable() {
        String sql = "SELECT * FROM sales WHERE region = 'CN'";
        String result = rewriter.rewriteTable(sql, "sales", "mv_sales_daily");
        assertThat(result).contains("FROM mv_sales_daily");
        assertThat(result).contains("WHERE region = 'CN'");
    }

    @Test
    @DisplayName("rewriteTable — 源表不存在时原样返回")
    void rewriteTable_tableNotFound_shouldReturnOriginal() {
        String sql = "SELECT * FROM orders";
        String result = rewriter.rewriteTable(sql, "sales", "mv_sales_daily");
        assertThat(result).isEqualTo(sql);
    }

    @Test
    @DisplayName("extractTables — 解析 SQL 提取表名")
    void extractTables_shouldReturnTables() {
        List<String> tables = rewriter.extractTables("SELECT * FROM sales JOIN orders ON sales.id = orders.id");
        assertThat(tables).contains("sales", "orders");
    }

    @Test
    @DisplayName("extractTables — 解析失败返回空列表")
    void extractTables_parseError_shouldReturnEmpty() {
        List<String> tables = rewriter.extractTables("NOT A SQL");
        assertThat(tables).isEmpty();
    }

    @Test
    @DisplayName("rewrite — 带 WHERE 子句的查询改写保留谓词")
    void rewrite_withWhereClause_shouldPreservePredicate() {
        String sql = "SELECT region, sum(amount) FROM sales WHERE region = 'CN' GROUP BY region";
        MaterializedViewDefinition view = availableView("mv_sales_daily", "sales");
        ViewMatcher.MatchResult match = ViewMatcher.MatchResult.matched(
                "mv_sales_daily", 1.0, RewriteRuleType.EXACT_MATCH, "完全匹配", view);

        RewriteResult result = rewriter.rewrite(sql, match);

        assertThat(result.isRewritten()).isTrue();
        assertThat(result.getRewrittenSql()).contains("mv_sales_daily");
        assertThat(result.getRewrittenSql()).contains("WHERE");
        assertThat(result.getRewrittenSql()).contains("region = 'CN'");
    }
}