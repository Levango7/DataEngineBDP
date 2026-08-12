package com.levango7.dataenginebdp.sqlgateway.calcite.explain;

import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.BaseAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.OptimizerConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PredicateType;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PushDownStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CostVisualizer} 单元测试。
 *
 * @author shuqing-bigdata
 */
@DisplayName("CostVisualizer Cost 可视化测试")
class CostVisualizerTest {

    @Test
    @DisplayName("可视化零 Cost")
    void testVisualizeZero() {
        CostVisualizer v = new CostVisualizer();
        Map<String, Object> stats = v.visualize(BaseAdapter.Cost.zero(), null, null);
        assertEquals(0.0, stats.get("cost.cpu"));
        assertEquals(0.0, stats.get("cost.io"));
        assertEquals(0.0, stats.get("cost.network"));
        assertEquals(0.0, stats.get("cost.total"));
        assertEquals("NONE", stats.get("cost.bottleneck"));
    }

    @Test
    @DisplayName("可视化 null Cost 视为零")
    void testVisualizeNull() {
        CostVisualizer v = new CostVisualizer();
        Map<String, Object> stats = v.visualize(null);
        assertEquals(0.0, stats.get("cost.total"));
    }

    @Test
    @DisplayName("可视化三维 Cost 与占比")
    void testVisualizeCost() {
        BaseAdapter.Cost cost = new BaseAdapter.Cost(100, 200, 300, 1000);
        CostVisualizer v = new CostVisualizer();
        Map<String, Object> stats = v.visualize(cost);

        assertEquals(100.0, stats.get("cost.cpu"));
        assertEquals(200.0, stats.get("cost.io"));
        assertEquals(300.0, stats.get("cost.network"));
        assertEquals(1000.0, stats.get("cost.rows"));
        assertEquals(600.0, stats.get("cost.total"));

        // 占比
        assertEquals(100.0 / 600, (Double) stats.get("cost.share.cpu"), 0.001);
        assertEquals(200.0 / 600, (Double) stats.get("cost.share.io"), 0.001);
        assertEquals(300.0 / 600, (Double) stats.get("cost.share.network"), 0.001);
        assertTrue(stats.containsKey("cost.share.cpuPct"));
        assertTrue(stats.containsKey("cost.shareBar"));

        // 瓶颈
        assertEquals("NETWORK", stats.get("cost.bottleneck"));
    }

    @Test
    @DisplayName("加权总 Cost 计算")
    void testWeightedCost() {
        OptimizerConfig config = new OptimizerConfig()
                .setCostWeight("cpu", 1.0)
                .setCostWeight("io", 10.0)
                .setCostWeight("network", 100.0);
        CostVisualizer v = new CostVisualizer(config);
        BaseAdapter.Cost cost = new BaseAdapter.Cost(10, 10, 10, 100);
        Map<String, Object> stats = v.visualize(cost);

        double weighted = (Double) stats.get("cost.weighted");
        assertEquals(10 * 1 + 10 * 10 + 10 * 100, weighted, 0.001);
        assertEquals(1.0, stats.get("cost.weights.cpu"));
        assertEquals(10.0, stats.get("cost.weights.io"));
        assertEquals(100.0, stats.get("cost.weights.network"));
    }

    @Test
    @DisplayName("瓶颈维度识别")
    void testBottleneck() {
        CostVisualizer v = new CostVisualizer();
        assertEquals("CPU", v.visualize(new BaseAdapter.Cost(300, 100, 50, 0))
                .get("cost.bottleneck"));
        assertEquals("IO", v.visualize(new BaseAdapter.Cost(50, 300, 100, 0))
                .get("cost.bottleneck"));
        assertEquals("NETWORK", v.visualize(new BaseAdapter.Cost(50, 100, 300, 0))
                .get("cost.bottleneck"));
    }

    @Test
    @DisplayName("节点级 Cost 累加")
    void testNodeCost() {
        CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("users").setSourceName("doris")
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);
        CustomRelNode root = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);
        root.addChild(scan);

        CostVisualizer v = new CostVisualizer();
        Map<String, Object> stats = v.visualize(BaseAdapter.Cost.zero(), root, Collections.emptyList());
        assertEquals(2, stats.get("cost.byNode.totalNodes"));
    }

    @Test
    @DisplayName("节点级 Cost null 跳过")
    void testNodeCostNull() {
        CostVisualizer v = new CostVisualizer();
        Map<String, Object> stats = v.visualize(BaseAdapter.Cost.zero(), null, null);
        assertEquals(0, stats.get("cost.byNode.totalNodes"));
    }

    @Test
    @DisplayName("按数据源分类 Cost")
    void testSourceCost() {
        BaseAdapter adapter = new BaseAdapter() {
            final DataSourceConfig config = new DataSourceConfig("doris", DataSourceConfig.Type.DORIS)
                    .setJdbcUrl("jdbc:mysql://x");
            @Override public DataSourceConfig.Type getAdapterType() { return DataSourceConfig.Type.DORIS; }
            @Override public DataSourceConfig getDataSourceConfig() { return config; }
            @Override public CustomRelNode toRel(String t, java.util.List<String> c) { return null; }
            @Override public PushDownResult pushDown(CustomRelNode r, PushDownContext ctx) { return null; }
            @Override public Cost costEstimate(CustomRelNode r) { return new Cost(10, 20, 30, 100); }
            @Override public com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect getDialect() { return com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect.DORIS; }
        };

        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("users").setSourceName("doris")
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);

        CostVisualizer v = new CostVisualizer();
        Map<String, Object> stats = v.visualize(BaseAdapter.Cost.zero(), node, List.of(adapter));
        assertTrue(stats.containsKey("cost.bySource.doris.cpu"));
        assertEquals(10.0, stats.get("cost.bySource.doris.cpu"));
        assertEquals(20.0, stats.get("cost.bySource.doris.io"));
        assertEquals(30.0, stats.get("cost.bySource.doris.network"));
    }

    @Test
    @DisplayName("shareBar 生成占比条")
    void testShareBar() {
        String bar = CostVisualizer.shareBar(10, 20, 30, 30);
        assertTrue(bar.startsWith("["));
        assertTrue(bar.endsWith("]"));
        assertTrue(bar.contains("C"));
        assertTrue(bar.contains("I"));
        assertTrue(bar.contains("N"));
        // 零 Cost
        String empty = CostVisualizer.shareBar(0, 0, 0, 30);
        assertEquals("[" + " ".repeat(30) + "]", empty);
    }

    @Test
    @DisplayName("bottleneck 静态方法")
    void testBottleneckStatic() {
        assertEquals("CPU", CostVisualizer.bottleneck(100, 50, 10));
        assertEquals("IO", CostVisualizer.bottleneck(10, 100, 50));
        assertEquals("NETWORK", CostVisualizer.bottleneck(10, 50, 100));
        assertEquals("NONE", CostVisualizer.bottleneck(0, 0, 0));
    }

    @Test
    @DisplayName("humanReadable 数值格式化")
    void testHumanReadable() {
        assertEquals("100.00", CostVisualizer.humanReadable(100));
        assertTrue(CostVisualizer.humanReadable(1500).contains("K"));
        assertTrue(CostVisualizer.humanReadable(1_500_000).contains("M"));
        assertTrue(CostVisualizer.humanReadable(1_500_000_000).contains("B"));
        assertTrue(CostVisualizer.humanReadable(1_500_000_000_000L).contains("T"));
        // 负数
        assertTrue(CostVisualizer.humanReadable(-1500).startsWith("-"));
    }

    @Test
    @DisplayName("formatPct 格式化百分比")
    void testFormatPct() {
        assertEquals("50.00%", CostVisualizer.formatPct(0.5));
        assertEquals("0.00%", CostVisualizer.formatPct(0));
    }

    @Test
    @DisplayName("默认构造器使用默认配置")
    void testDefaultConstructor() {
        CostVisualizer v = new CostVisualizer();
        BaseAdapter.Cost cost = new BaseAdapter.Cost(1, 1, 1, 0);
        Map<String, Object> stats = v.visualize(cost);
        // 默认权重 cpu=1, io=10, network=100
        double weighted = (Double) stats.get("cost.weighted");
        assertEquals(1 + 10 + 100, weighted, 0.001);
    }

    @Test
    @DisplayName("节点带 estimatedRows/Cost 累加")
    void testNodeWithEstimates() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t").setSourceName("doris")
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED)
                .setEstimatedRows(100)
                .setEstimatedCost(50);
        CostVisualizer v = new CostVisualizer();
        Map<String, Object> stats = v.visualize(BaseAdapter.Cost.zero(), node, Collections.emptyList());
        assertEquals(1, stats.get("cost.byNode.estimatedNodes"));
        assertTrue((Double) stats.get("cost.byNode.totalRows") > 0);
    }

    @Test
    @DisplayName("按数据源分类 Cost 含 null 适配器跳过")
    void testSourceCostWithNullAdapter() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t").setSourceName("doris");
        CostVisualizer v = new CostVisualizer();
        // 列表含 null 适配器，应跳过不抛异常
        Map<String, Object> stats = v.visualize(BaseAdapter.Cost.zero(), node,
                Arrays.asList(null, createStubAdapter("doris")));
        assertTrue(stats.containsKey("cost.bySource.doris.total"));
    }

    @Test
    @DisplayName("适配器 config 为 null 时跳过")
    void testFindAdapterNullConfig() {
        BaseAdapter adapter = new BaseAdapter() {
            @Override public DataSourceConfig.Type getAdapterType() { return DataSourceConfig.Type.DORIS; }
            @Override public DataSourceConfig getDataSourceConfig() { return null; }
            @Override public CustomRelNode toRel(String t, java.util.List<String> c) { return null; }
            @Override public PushDownResult pushDown(CustomRelNode r, PushDownContext ctx) { return null; }
            @Override public Cost costEstimate(CustomRelNode r) { return Cost.zero(); }
            @Override public com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect getDialect() { return null; }
        };
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t").setSourceName("doris");
        CostVisualizer v = new CostVisualizer();
        Map<String, Object> stats = v.visualize(BaseAdapter.Cost.zero(), node, List.of(adapter));
        assertNotNull(stats);
    }

    @Test
    @DisplayName("shareBar netLen 为负时截断为 0")
    void testShareBarNegativeNetLen() {
        String bar = CostVisualizer.shareBar(99, 99, 1, 10);
        assertNotNull(bar);
        assertTrue(bar.startsWith("["));
    }

    @Test
    @DisplayName("humanReadable T 级别")
    void testHumanReadableTera() {
        String result = CostVisualizer.humanReadable(1_500_000_000_000.0);
        assertTrue(result.contains("T"));
    }

    private BaseAdapter createStubAdapter(String name) {
        DataSourceConfig config = new DataSourceConfig(name, DataSourceConfig.Type.DORIS)
                .setJdbcUrl("jdbc:mysql://x");
        return new BaseAdapter() {
            @Override public DataSourceConfig.Type getAdapterType() { return DataSourceConfig.Type.DORIS; }
            @Override public DataSourceConfig getDataSourceConfig() { return config; }
            @Override public CustomRelNode toRel(String t, java.util.List<String> c) { return null; }
            @Override public PushDownResult pushDown(CustomRelNode r, PushDownContext ctx) { return null; }
            @Override public Cost costEstimate(CustomRelNode r) { return new Cost(10, 20, 30, 100); }
            @Override public com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect getDialect() { return null; }
        };
    }
}