package com.levango7.dataenginebdp.sqlgateway.rewrite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ViewRouter 单元测试。
 *
 * <p>验证自动路由决策器在不同场景下的选择行为：</p>
 * <ul>
 *   <li>无候选时返回 empty；</li>
 *   <li>评分低于阈值时返回 empty（用户无感知约束）；</li>
 *   <li>多候选时选择评分最高的；</li>
 *   <li>显式改写规则可加权提升优先级。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class ViewRouterTest {

    private final ViewMatcher viewMatcher = new ViewMatcher();
    private final ViewRouter viewRouter = new ViewRouter(viewMatcher);

    /**
     * 构造一个可用的物化视图定义。
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
    @DisplayName("route — 无候选返回 empty")
    void route_noCandidates_shouldReturnEmpty() {
        Optional<ViewMatcher.MatchResult> result = viewRouter.route("SELECT 1", List.of());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("route — SQL 为空返回 empty")
    void route_emptySql_shouldReturnEmpty() {
        MaterializedViewDefinition view = availableView("mv", "sales", "region", "amount");
        Optional<ViewMatcher.MatchResult> result = viewRouter.route("", List.of(view));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("route — 评分低于阈值返回 empty（用户无感知约束）")
    void route_scoreBelowThreshold_shouldReturnEmpty() {
        // 设置高阈值，使弱匹配不通过
        viewRouter.setMinMatchScore(0.9);
        MaterializedViewDefinition view = availableView(
                "mv_rollup", "sales", "region,product", "amount");
        // 查询维度是子集，评分 0.8 < 0.9
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";

        Optional<ViewMatcher.MatchResult> result = viewRouter.route(sql, List.of(view));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("route — 完全匹配评分达阈值时返回最优")
    void route_exactMatchAboveThreshold_shouldReturnBest() {
        viewRouter.setMinMatchScore(0.6);
        MaterializedViewDefinition view = availableView(
                "mv_sales", "sales", "region", "amount");
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";

        Optional<ViewMatcher.MatchResult> result = viewRouter.route(sql, List.of(view));

        assertThat(result).isPresent();
        assertThat(result.get().viewName()).isEqualTo("mv_sales");
        assertThat(result.get().score()).isEqualTo(ViewMatcher.SCORE_EXACT);
    }

    @Test
    @DisplayName("route — 多候选选择评分最高的")
    void route_multipleCandidates_shouldSelectHighestScore() {
        viewRouter.setMinMatchScore(0.6);
        MaterializedViewDefinition exactView = availableView(
                "mv_exact", "sales", "region", "amount");
        MaterializedViewDefinition rollupView = availableView(
                "mv_rollup", "sales", "region,product", "amount");
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";

        Optional<ViewMatcher.MatchResult> result = viewRouter.route(sql,
                List.of(rollupView, exactView));

        assertThat(result).isPresent();
        assertThat(result.get().viewName()).isEqualTo("mv_exact");
    }

    @Test
    @DisplayName("route — 显式改写规则加权提升目标视图优先级")
    void route_withRule_shouldBoostTargetView() {
        viewRouter.setMinMatchScore(0.6);
        MaterializedViewDefinition exactView = availableView(
                "mv_exact", "sales", "region", "amount");
        MaterializedViewDefinition rollupView = availableView(
                "mv_rollup", "sales", "region,product", "amount");
        // 显式规则指向 rollupView，加权后应优先选择
        RewriteRule rule = new RewriteRule("r1", "AGG_ROLLUP", "mv_rollup",
                "sales", 1, true, "优先 rollup");
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";

        Optional<ViewMatcher.MatchResult> result = viewRouter.route(sql,
                List.of(exactView, rollupView), List.of(rule));

        assertThat(result).isPresent();
        // 加权后 rollupView (0.8+0.1=0.9) 仍低于 exactView (1.0)，应选 exact
        assertThat(result.get().viewName()).isEqualTo("mv_exact");
    }

    @Test
    @DisplayName("route — 显式改写规则加权使弱视图胜出")
    void route_withRule_shouldMakeWeakViewWin() {
        viewRouter.setMinMatchScore(0.6);
        // 两个视图都是 rollup（评分 0.8）
        MaterializedViewDefinition viewA = availableView(
                "mv_a", "sales", "region,product", "amount");
        MaterializedViewDefinition viewB = availableView(
                "mv_b", "sales", "region,product", "amount");
        // 规则加权 viewB
        RewriteRule rule = new RewriteRule("r1", "AGG_ROLLUP", "mv_b",
                "sales", 1, true, "优先 b");
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";

        Optional<ViewMatcher.MatchResult> result = viewRouter.route(sql,
                List.of(viewA, viewB), List.of(rule));

        assertThat(result).isPresent();
        assertThat(result.get().viewName()).isEqualTo("mv_b");
    }

    @Test
    @DisplayName("route — 未启用的规则不参与加权")
    void route_disabledRule_shouldNotBoost() {
        viewRouter.setMinMatchScore(0.6);
        MaterializedViewDefinition viewA = availableView(
                "mv_a", "sales", "region,product", "amount");
        MaterializedViewDefinition viewB = availableView(
                "mv_b", "sales", "region,product", "amount");
        // 规则未启用
        RewriteRule rule = new RewriteRule("r1", "AGG_ROLLUP", "mv_b",
                "sales", 1, false, "未启用");
        String sql = "SELECT region, sum(amount) FROM sales GROUP BY region";

        Optional<ViewMatcher.MatchResult> result = viewRouter.route(sql,
                List.of(viewA, viewB), List.of(rule));

        assertThat(result).isPresent();
        // 两个评分相同，应返回第一个（viewA）
        assertThat(result.get().viewName()).isEqualTo("mv_a");
    }

    @Test
    @DisplayName("getMinMatchScore/setMinMatchScore — 阈值读写一致")
    void threshold_getSet_shouldBeConsistent() {
        viewRouter.setMinMatchScore(0.75);
        assertThat(viewRouter.getMinMatchScore()).isEqualTo(0.75);
    }
}