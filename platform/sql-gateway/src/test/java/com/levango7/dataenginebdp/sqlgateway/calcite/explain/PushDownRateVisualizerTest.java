package com.levango7.dataenginebdp.sqlgateway.calcite.explain;

import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PredicateType;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.ProjectionStatistics;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PushDownStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PushDownRateVisualizer} 单元测试。
 *
 * @author shuqing-bigdata
 */
@DisplayName("PushDownRateVisualizer 下推率可视化测试")
class PushDownRateVisualizerTest {

    @Test
    @DisplayName("可视化空统计返回默认值")
    void testVisualizeEmpty() {
        PushDownRateVisualizer v = new PushDownRateVisualizer();
        Map<String, Object> stats = v.visualize(null, null, null);
        assertEquals(0, stats.get("pushDown.predicate.total"));
        assertEquals(0.0, stats.get("pushDown.predicate.rate"));
        assertEquals("0.00%", stats.get("pushDown.predicate.ratePct"));
        assertEquals(0, stats.get("pushDown.projection.totalCols"));
        assertEquals(0, stats.get("pushDown.node.total"));
        assertEquals(0.0, stats.get("pushDown.overallRate"));
    }

    @Test
    @DisplayName("可视化谓词下推统计")
    void testVisualizePredicate() {
        PushDownStatistics predStats = new PushDownStatistics();
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.EQUALITY, true, null, "id=100");
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.RANGE, true, null, "age>18");
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "UDF 不支持", "UDF(name)");

        PushDownRateVisualizer v = new PushDownRateVisualizer();
        Map<String, Object> stats = v.visualizePredicate(predStats);

        assertEquals(3, stats.get("pushDown.predicate.total"));
        assertEquals(2, stats.get("pushDown.predicate.pushed"));
        assertEquals(1, stats.get("pushDown.predicate.remaining"));
        assertEquals(2.0 / 3, (Double) stats.get("pushDown.predicate.rate"), 0.001);
        assertTrue(stats.containsKey("pushDown.predicate.byType.EQUALITY.rate"));
        assertTrue(stats.containsKey("pushDown.predicate.byType.RANGE.rate"));
        assertTrue(stats.containsKey("pushDown.predicate.byType.UNSUPPORTED.rate"));
        assertTrue(stats.containsKey("pushDown.predicate.bySource.DORIS.rate"));
    }

    @Test
    @DisplayName("可视化投影下推统计")
    void testVisualizeProjection() {
        ProjectionStatistics projStats = new ProjectionStatistics();
        projStats.recordProjection(DataSourceConfig.Type.DORIS, 10, 3, "users: 10->3");
        projStats.recordProjection(DataSourceConfig.Type.ICEBERG, 5, 2);
        projStats.recordMerge();
        projStats.recordSkip("SELECT *");

        PushDownRateVisualizer v = new PushDownRateVisualizer();
        Map<String, Object> stats = v.visualizeProjection(projStats);

        assertEquals(15, stats.get("pushDown.projection.totalCols"));
        assertEquals(5, stats.get("pushDown.projection.retainedCols"));
        assertEquals(10, stats.get("pushDown.projection.prunedCols"));
        assertEquals(2, stats.get("pushDown.projection.pushDownCount"));
        assertEquals(1, stats.get("pushDown.projection.mergeCount"));
        assertEquals(1, stats.get("pushDown.projection.skipCount"));
        assertTrue(stats.containsKey("pushDown.projection.bySource.DORIS.reductionRate"));
        assertTrue(stats.containsKey("pushDown.projection.bySource.ICEBERG.reductionRate"));
    }

    @Test
    @DisplayName("可视化节点级下推统计")
    void testVisualizeNode() {
        CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("users").setSourceName("doris")
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);
        CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                .setCondition("age>18")
                .setPushDownStatus(CustomRelNode.PushDownStatus.NOT_PUSHED)
                .setPushDownReason("UDF");
        filter.addChild(scan);
        CustomRelNode root = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);
        root.addChild(filter);

        PushDownRateVisualizer v = new PushDownRateVisualizer();
        Map<String, Object> stats = v.visualize(null, null, root);

        assertEquals(3, stats.get("pushDown.node.total"));
        assertEquals(2, stats.get("pushDown.node.pushed"));
        assertEquals(1, stats.get("pushDown.node.notPushed"));
        assertFalse((Boolean) stats.get("pushDown.node.federated"));
        assertEquals(1, stats.get("pushDown.node.sourceCount"));
    }

    @Test
    @DisplayName("跨源节点标记 federated")
    void testFederatedNode() {
        CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN)
                .setPushDownStatus(CustomRelNode.PushDownStatus.NOT_APPLICABLE);
        join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("a").setSourceName("doris")
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED));
        join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("b").setSourceName("trino")
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED));

        PushDownRateVisualizer v = new PushDownRateVisualizer();
        Map<String, Object> stats = v.visualize(null, null, join);
        assertTrue((Boolean) stats.get("pushDown.node.federated"));
        assertEquals(2, stats.get("pushDown.node.sourceCount"));
    }

    @Test
    @DisplayName("综合下推率计算")
    void testOverallRate() {
        PushDownStatistics predStats = new PushDownStatistics();
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.EQUALITY, true);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.RANGE, false, "不支持", null);

        ProjectionStatistics projStats = new ProjectionStatistics();
        projStats.recordProjection(DataSourceConfig.Type.DORIS, 10, 5);

        PushDownRateVisualizer v = new PushDownRateVisualizer();
        Map<String, Object> stats = v.visualize(predStats, projStats, null);
        double overall = (Double) stats.get("pushDown.overallRate");
        assertTrue(overall > 0 && overall <= 1);
        assertTrue(stats.containsKey("pushDown.overallBar"));
    }

    @Test
    @DisplayName("progressBar 生成进度条")
    void testProgressBar() {
        String bar = PushDownRateVisualizer.progressBar(0.5, 10);
        assertTrue(bar.startsWith("["));
        assertTrue(bar.contains("]"));
        assertTrue(bar.contains("50.00%"));

        // 边界
        String full = PushDownRateVisualizer.progressBar(1.0, 10);
        assertTrue(full.contains("100.00%"));
        String empty = PushDownRateVisualizer.progressBar(0.0, 10);
        assertTrue(empty.contains("0.00%"));
        // 超出范围截断
        String over = PushDownRateVisualizer.progressBar(1.5, 10);
        assertTrue(over.contains("100.00%"));
    }

    @Test
    @DisplayName("formatPct 格式化百分比")
    void testFormatPct() {
        assertEquals("75.00%", PushDownRateVisualizer.formatPct(0.75));
        assertEquals("0.00%", PushDownRateVisualizer.formatPct(0));
        assertEquals("100.00%", PushDownRateVisualizer.formatPct(1));
    }

    @Test
    @DisplayName("保留原因与下推描述记录")
    void testReasonsAndDescriptions() {
        PushDownStatistics predStats = new PushDownStatistics();
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED,
                false, "UDF 不支持", "UDF(name)=x");
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.EQUALITY,
                true, null, "id=100");

        PushDownRateVisualizer v = new PushDownRateVisualizer();
        Map<String, Object> stats = v.visualizePredicate(predStats);
        assertTrue(stats.containsKey("pushDown.predicate.remainingReasons"));
        assertTrue(stats.containsKey("pushDown.predicate.pushedDescriptions"));
    }
}