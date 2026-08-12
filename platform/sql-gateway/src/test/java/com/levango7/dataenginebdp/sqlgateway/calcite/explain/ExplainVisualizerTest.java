package com.levango7.dataenginebdp.sqlgateway.calcite.explain;

import com.levango7.dataenginebdp.sqlgateway.calcite.CalciteOptimizer;
import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.BaseAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.OptimizerConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PredicatePushDownRule;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.ProjectPushDownRule;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PushDownStatistics;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.ProjectionStatistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ExplainVisualizer} 单元测试——验证统一入口的完整流程。
 *
 * <p>使用 H2 内存数据库通过 Calcite JdbcSchema 注册测试表，
 * 验证 EXPLAIN → 统计 → Cost → 调优建议 → 格式化输出全流程。</p>
 *
 * @author shuqing-bigdata
 */
@DisplayName("ExplainVisualizer 统一入口测试")
class ExplainVisualizerTest {

    private static final String H2_URL = "jdbc:h2:mem:explain_test;DB_CLOSE_DELAY=-1";
    private static final String H2_DRIVER = "org.h2.Driver";

    @BeforeAll
    static void initH2() throws SQLException {
        try (Connection conn = DriverManager.getConnection(H2_URL, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (id INT PRIMARY KEY, name VARCHAR(50), age INT)");
            stmt.execute("INSERT INTO users VALUES (1, 'Alice', 25), (2, 'Bob', 30)");
        }
    }

    /**
     * 创建带 H2 Schema 的优化器。
     */
    private CalciteOptimizer createOptimizer() {
        OptimizerConfig config = new OptimizerConfig();
        CalciteOptimizer optimizer = new CalciteOptimizer(config);
        org.apache.calcite.schema.SchemaPlus root = optimizer.getRootSchema();
        javax.sql.DataSource ds = org.apache.calcite.adapter.jdbc.JdbcSchema
                .dataSource(H2_URL, H2_DRIVER, "sa", "");
        root.add("h2", org.apache.calcite.adapter.jdbc.JdbcSchema.create(root, "h2", ds, null, null));
        return optimizer;
    }

    @Test
    @DisplayName("构造器 null optimizer 抛 NPE")
    void testNullOptimizer() {
        assertThrows(NullPointerException.class, () -> new ExplainVisualizer(null));
    }

    @Test
    @DisplayName("explain 返回结构化结果")
    void testExplainResult() {
        CalciteOptimizer optimizer = createOptimizer();
        ExplainVisualizer visualizer = new ExplainVisualizer(optimizer);

        ExplainResult result = visualizer.explain("SELECT * FROM h2.users WHERE id = 1");
        assertNotNull(result);
        assertEquals("SELECT * FROM h2.users WHERE id = 1", result.getSql());
        assertTrue(result.isSuccess());
        assertTrue(result.getDepth() > 0);
        assertNotNull(result.getPushDownStats());
        assertNotNull(result.getCostStats());
        assertNotNull(result.getTuningSuggestions());
    }

    @Test
    @DisplayName("explain 空 SQL 返回失败结果")
    void testEmptySql() {
        CalciteOptimizer optimizer = createOptimizer();
        ExplainVisualizer visualizer = new ExplainVisualizer(optimizer);

        ExplainResult result = visualizer.explain("");
        assertFalse(result.isSuccess());
        assertEquals("SQL 不能为空", result.getError());

        ExplainResult nullResult = visualizer.explain(null);
        assertFalse(nullResult.isSuccess());
    }

    @Test
    @DisplayName("explain 非法 SQL 返回失败结果")
    void testInvalidSql() {
        CalciteOptimizer optimizer = createOptimizer();
        ExplainVisualizer visualizer = new ExplainVisualizer(optimizer);

        ExplainResult result = visualizer.explain("NOT A VALID SQL @@@@");
        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
    }

    @Test
    @DisplayName("explainTree 输出树形格式")
    void testExplainTree() {
        CalciteOptimizer optimizer = createOptimizer();
        ExplainVisualizer visualizer = new ExplainVisualizer(optimizer);

        String tree = visualizer.explainTree("SELECT * FROM h2.users");
        assertNotNull(tree);
        assertTrue(tree.contains("EXPLAIN"));
        assertTrue(tree.contains("Plan:"));
    }

    @Test
    @DisplayName("explainJson 输出 JSON 格式")
    void testExplainJson() {
        CalciteOptimizer optimizer = createOptimizer();
        ExplainVisualizer visualizer = new ExplainVisualizer(optimizer);

        String json = visualizer.explainJson("SELECT * FROM h2.users");
        assertNotNull(json);
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertTrue(json.contains("\"sql\""));
    }

    @Test
    @DisplayName("explainTable 输出表格格式")
    void testExplainTable() {
        CalciteOptimizer optimizer = createOptimizer();
        ExplainVisualizer visualizer = new ExplainVisualizer(optimizer);

        String table = visualizer.explainTable("SELECT * FROM h2.users");
        assertNotNull(table);
        assertTrue(table.contains("EXPLAIN"));
        assertTrue(table.contains("Plan Nodes:"));
    }

    @Test
    @DisplayName("explain(format) 分发到三种格式")
    void testExplainWithFormat() {
        CalciteOptimizer optimizer = createOptimizer();
        ExplainVisualizer visualizer = new ExplainVisualizer(optimizer);
        String sql = "SELECT * FROM h2.users";

        String tree = visualizer.explain(sql, ExplainFormat.TREE);
        assertTrue(tree.contains("Plan:"));

        String json = visualizer.explain(sql, ExplainFormat.JSON);
        assertTrue(json.contains("\"plan\""));

        String table = visualizer.explain(sql, ExplainFormat.TABLE);
        assertTrue(table.contains("Plan Nodes:"));

        // null 默认 TREE
        String def = visualizer.explain(sql, null);
        assertTrue(def.contains("Plan:"));
    }

    @Test
    @DisplayName("visualizePushDown 返回下推指标")
    void testVisualizePushDown() {
        CalciteOptimizer optimizer = createOptimizer();
        ExplainVisualizer visualizer = new ExplainVisualizer(optimizer);

        Map<String, Object> stats = visualizer.visualizePushDown("SELECT * FROM h2.users");
        assertNotNull(stats);
        assertTrue(stats.containsKey("pushDown.predicate.rate") || stats.containsKey("pushDown.node.total"));
    }

    @Test
    @DisplayName("visualizeCost 返回 Cost 指标")
    void testVisualizeCost() {
        CalciteOptimizer optimizer = createOptimizer();
        ExplainVisualizer visualizer = new ExplainVisualizer(optimizer);

        Map<String, Object> stats = visualizer.visualizeCost("SELECT * FROM h2.users");
        assertNotNull(stats);
        assertTrue(stats.containsKey("cost.total"));
    }

    @Test
    @DisplayName("advise 返回调优建议")
    void testAdvise() {
        CalciteOptimizer optimizer = createOptimizer();
        ExplainVisualizer visualizer = new ExplainVisualizer(optimizer);

        List<String> suggestions = visualizer.advise("SELECT * FROM h2.users");
        assertNotNull(suggestions);
        // 简单查询可能无建议或少量 INFO 建议
    }

    @Test
    @DisplayName("带下推规则的优化器生成统计")
    void testWithPushDownRules() {
        CalciteOptimizer optimizer = createOptimizer();
        // 注册桩适配器与下推规则
        BaseAdapter adapter = createStubAdapter();
        optimizer.registerAdapter(adapter);
        optimizer.registerPredicatePushDownRule(adapter);
        optimizer.registerProjectPushDownRule(adapter);

        ExplainVisualizer visualizer = new ExplainVisualizer(optimizer);
        ExplainResult result = visualizer.explain("SELECT * FROM h2.users WHERE id = 1");
        assertTrue(result.isSuccess());
        // 应包含下推统计
        assertNotNull(result.getPushDownStats());
    }

    @Test
    @DisplayName("自定义子组件构造")
    void testCustomComponents() {
        CalciteOptimizer optimizer = createOptimizer();
        ExplainVisualizer visualizer = new ExplainVisualizer(
                optimizer,
                new PushDownRateVisualizer(),
                new CostVisualizer(),
                new TuningAdvisor(0.5, 0.2, 0.3, 1_000_000));

        ExplainResult result = visualizer.explain("SELECT * FROM h2.users");
        assertTrue(result.isSuccess());
        assertNotNull(visualizer.getPushDownVisualizer());
        assertNotNull(visualizer.getCostVisualizer());
        assertNotNull(visualizer.getTuningAdvisor());
    }

    @Test
    @DisplayName("Getter 返回非空")
    void testGetters() {
        CalciteOptimizer optimizer = createOptimizer();
        ExplainVisualizer visualizer = new ExplainVisualizer(optimizer);
        assertSame(optimizer, visualizer.getOptimizer());
        assertNotNull(visualizer.getPushDownVisualizer());
        assertNotNull(visualizer.getCostVisualizer());
        assertNotNull(visualizer.getTuningAdvisor());
    }

    /**
     * 创建桩 Doris 适配器（用于下推规则注册）。
     */
    private BaseAdapter createStubAdapter() {
        DataSourceConfig config = new DataSourceConfig("h2", DataSourceConfig.Type.DORIS)
                .setJdbcUrl(H2_URL);
        return new BaseAdapter() {
            @Override public DataSourceConfig.Type getAdapterType() { return DataSourceConfig.Type.DORIS; }
            @Override public DataSourceConfig getDataSourceConfig() { return config; }
            @Override public CustomRelNode toRel(String t, java.util.List<String> c) {
                return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                        .setTableName(t).setSourceName(config.getName());
            }
            @Override public PushDownResult pushDown(CustomRelNode r, PushDownContext ctx) {
                return new PushDownResult("SELECT * FROM " + r.getTableName(), r, new java.util.ArrayList<>(), true, null);
            }
            @Override public Cost costEstimate(CustomRelNode r) { return new Cost(10, 20, 30, 100); }
            @Override public com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect getDialect() {
                return com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect.DORIS;
            }
        };
    }
}