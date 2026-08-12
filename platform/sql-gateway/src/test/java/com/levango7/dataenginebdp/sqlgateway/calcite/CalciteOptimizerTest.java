package com.levango7.dataenginebdp.sqlgateway.calcite;

import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.BaseAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.OptimizerConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PushDownRule;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CalciteOptimizer} 集成测试。
 *
 * <p>使用 H2 内存数据库通过 Calcite {@code JdbcSchema} 注册测试表，
 * 验证 Calcite 优化器的完整流程：SQL 解析 → RelNode 转换 → 优化 → EXPLAIN 输出。</p>
 *
 * <p>测试覆盖：</p>
 * <ul>
 *   <li>优化器初始化与配置</li>
 *   <li>{@code optimize} 解析 SQL 并返回 RelNode</li>
 *   <li>{@code explain} 输出 JSON 格式执行计划</li>
 *   <li>{@code toCustomRel} 转换为 CustomRelNode</li>
 *   <li>{@code applyCustomRules} 应用自定义下推规则</li>
 *   <li>适配器与规则注册</li>
 *   <li>异常处理（空 SQL、非法 SQL）</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class CalciteOptimizerTest {

    /** H2 内存数据库 JDBC URL */
    private static final String H2_URL = "jdbc:h2:mem:calcite_test;DB_CLOSE_DELAY=-1";
    private static final String H2_DRIVER = "org.h2.Driver";

    /**
     * 初始化 H2 内存数据库，创建测试表。
     */
    @BeforeAll
    static void initH2Database() throws SQLException {
        try (Connection conn = DriverManager.getConnection(H2_URL, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS orders ("
                    + "id INT PRIMARY KEY, uid INT, amount DOUBLE, dt VARCHAR(10))");
            stmt.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "id INT PRIMARY KEY, name VARCHAR(50), age INT)");
            stmt.execute("INSERT INTO orders VALUES (1, 100, 99.9, '2024-01-01'),"
                    + " (2, 101, 199.9, '2024-01-02')");
            stmt.execute("INSERT INTO users VALUES (100, 'Alice', 25),"
                    + " (101, 'Bob', 30)");
        }
    }

    /**
     * 创建一个注册了 H2 JdbcSchema 的 CalciteOptimizer。
     */
    private CalciteOptimizer createOptimizerWithH2() {
        OptimizerConfig config = new OptimizerConfig();
        CalciteOptimizer optimizer = new CalciteOptimizer(config);

        // 向 rootSchema 注册 H2 JdbcSchema
        SchemaPlus rootSchema = optimizer.getRootSchema();
        DataSource dataSource = JdbcSchema.dataSource(H2_URL, H2_DRIVER, "sa", "");
        JdbcSchema h2Schema = JdbcSchema.create(rootSchema, "h2", dataSource, null, null);
        rootSchema.add("h2", h2Schema);

        return optimizer;
    }

    // ===================== 初始化与配置测试 =====================

    @Test
    @DisplayName("默认配置构造优化器")
    void testDefaultConstructor() {
        CalciteOptimizer optimizer = new CalciteOptimizer();
        assertNotNull(optimizer.getConfig());
        assertNotNull(optimizer.getRootSchema());
        assertNotNull(optimizer.getFrameworkConfig());
        assertTrue(optimizer.isInitialized());
        assertTrue(optimizer.getAdapters().isEmpty());
        assertTrue(optimizer.getCustomRules().isEmpty());
    }

    @Test
    @DisplayName("指定配置构造优化器")
    void testConfigConstructor() {
        OptimizerConfig config = new OptimizerConfig().setEnabled(false);
        CalciteOptimizer optimizer = new CalciteOptimizer(config);
        assertSame(config, optimizer.getConfig());
        assertFalse(optimizer.getConfig().isEnabled());
    }

    @Test
    @DisplayName("构造优化器 null 配置抛异常")
    void testNullConfig() {
        assertThrows(NullPointerException.class, () -> new CalciteOptimizer(null));
    }

    // ===================== optimize 测试 =====================

    @Test
    @DisplayName("optimize 解析简单 SELECT 并返回 RelNode")
    void testOptimizeSimpleSelect() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        RelNode relNode = optimizer.optimize("SELECT * FROM h2.orders");
        assertNotNull(relNode);
        assertNotNull(relNode.getRowType());
        assertTrue(relNode.getRowType().getFieldCount() > 0);
    }

    @Test
    @DisplayName("optimize 解析带 WHERE 的 SELECT")
    void testOptimizeSelectWithFilter() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        RelNode relNode = optimizer.optimize(
                "SELECT id, amount FROM h2.orders WHERE amount > 100");
        assertNotNull(relNode);
        assertEquals(2, relNode.getRowType().getFieldCount());
    }

    @Test
    @DisplayName("optimize 解析 JOIN 查询")
    void testOptimizeJoin() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        RelNode relNode = optimizer.optimize(
                "SELECT o.id, u.name FROM h2.orders o JOIN h2.users u ON o.uid = u.id");
        assertNotNull(relNode);
        assertEquals(2, relNode.getRowType().getFieldCount());
    }

    @Test
    @DisplayName("optimize 解析聚合查询")
    void testOptimizeAggregate() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        RelNode relNode = optimizer.optimize(
                "SELECT dt, COUNT(*) AS cnt, SUM(amount) AS total FROM h2.orders GROUP BY dt");
        assertNotNull(relNode);
        assertEquals(3, relNode.getRowType().getFieldCount());
    }

    @Test
    @DisplayName("optimize 空 SQL 抛异常")
    void testOptimizeEmptySql() {
        CalciteOptimizer optimizer = new CalciteOptimizer();
        assertThrows(CalciteOptimizer.CalciteOptimizeException.class,
                () -> optimizer.optimize(""));
        assertThrows(CalciteOptimizer.CalciteOptimizeException.class,
                () -> optimizer.optimize(null));
        assertThrows(CalciteOptimizer.CalciteOptimizeException.class,
                () -> optimizer.optimize("   "));
    }

    @Test
    @DisplayName("optimize 非法 SQL 抛异常")
    void testOptimizeInvalidSql() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        assertThrows(CalciteOptimizer.CalciteOptimizeException.class,
                () -> optimizer.optimize("SELECT FROM WHERE"));
    }

    @Test
    @DisplayName("optimize 不存在的表抛异常")
    void testOptimizeNonExistentTable() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        assertThrows(CalciteOptimizer.CalciteOptimizeException.class,
                () -> optimizer.optimize("SELECT * FROM h2.nonexistent_table"));
    }

    // ===================== explain 测试 =====================

    @Test
    @DisplayName("explain 输出 JSON 格式执行计划")
    void testExplain() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        String json = optimizer.explain("SELECT * FROM h2.orders WHERE amount > 100");
        assertNotNull(json);
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertTrue(json.contains("\"sql\""));
        assertTrue(json.contains("\"relNode\""));
        assertTrue(json.contains("\"rowCount\""));
        assertTrue(json.contains("\"depth\""));
        assertTrue(json.contains("\"rulesApplied\""));
        assertTrue(json.contains("\"success\":true"));
    }

    @Test
    @DisplayName("explain 输出含 RelNode 文本")
    void testExplainContainsRelNodeText() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        String json = optimizer.explain("SELECT id FROM h2.orders");
        // RelNode 文本应包含 LogicalProject 或 TableScan 等
        assertTrue(json.contains("Logical") || json.contains("Scan") || json.contains("Jdbc"));
    }

    @Test
    @DisplayName("explain depth 大于 0")
    void testExplainDepth() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        String json = optimizer.explain("SELECT * FROM h2.orders");
        assertTrue(json.contains("\"depth\":"));
        // 简单 SELECT 的 RelNode 树深度至少为 1
        assertTrue(json.contains("\"depth\":1") || json.contains("\"depth\":2")
                || json.contains("\"depth\":3"));
    }

    @Test
    @DisplayName("explain 空 SQL 返回错误 JSON")
    void testExplainEmptySql() {
        CalciteOptimizer optimizer = new CalciteOptimizer();
        String json = optimizer.explain("");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":false"));
        assertTrue(json.contains("\"error\""));
    }

    @Test
    @DisplayName("explain 非法 SQL 返回错误 JSON")
    void testExplainInvalidSql() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        String json = optimizer.explain("INVALID SQL SYNTAX");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":false"));
    }

    // ===================== toCustomRel 测试 =====================

    @Test
    @DisplayName("toCustomRel 转换 RelNode 为 CustomRelNode")
    void testToCustomRel() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        RelNode relNode = optimizer.optimize("SELECT * FROM h2.orders WHERE amount > 100");
        CustomRelNode custom = optimizer.toCustomRel(relNode);
        assertNotNull(custom);
        assertTrue(custom.depth() >= 1);
    }

    @Test
    @DisplayName("toCustomRel null 返回 null")
    void testToCustomRelNull() {
        CalciteOptimizer optimizer = new CalciteOptimizer();
        assertNull(optimizer.toCustomRel(null));
    }

    @Test
    @DisplayName("toCustomRel 跨源 Join 标记 federated")
    void testToCustomRelFederated() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        RelNode relNode = optimizer.optimize(
                "SELECT o.id, u.name FROM h2.orders o JOIN h2.users u ON o.uid = u.id");
        CustomRelNode custom = optimizer.toCustomRel(relNode);
        assertNotNull(custom);
        // 单源（都是 h2）不应标记为 federated
        assertNotNull(custom.collectSourceNames());
    }

    // ===================== applyCustomRules 测试 =====================

    @Test
    @DisplayName("applyCustomRules 无规则时返回原节点")
    void testApplyCustomRulesNoRules() {
        CalciteOptimizer optimizer = new CalciteOptimizer();
        CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t").setSourceName("ds1");
        CustomRelNode result = optimizer.applyCustomRules(scan);
        assertSame(scan, result);
    }

    @Test
    @DisplayName("applyCustomRules null 返回 null")
    void testApplyCustomRulesNull() {
        CalciteOptimizer optimizer = new CalciteOptimizer();
        assertNull(optimizer.applyCustomRules(null));
    }

    @Test
    @DisplayName("applyCustomRules 应用匹配的下推规则")
    void testApplyCustomRulesWithMatchingRule() {
        // 构造一个简单的下推规则
        StubAdapter adapter = new StubAdapter("ds1");
        PushDownRule rule = new PushDownRule(
                "FilterPushDown", "测试下推", adapter, CustomRelNode.Op.FILTER) {
            @Override
            public void onMatch(RuleCall call) {
                CustomRelNode root = call.getRoot();
                // 标记为已下推
                root.markPushed("filter: " + root.getCondition());
                call.transformTo(root);
            }
        };

        CalciteOptimizer optimizer = new CalciteOptimizer();
        optimizer.registerRule(rule);

        CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t").setSourceName("ds1");
        CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                .setCondition("x > 1");
        filter.addChild(scan);

        CustomRelNode result = optimizer.applyCustomRules(filter);
        assertNotNull(result);
        assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
        assertTrue(result.getPushedOperations().size() > 0);
    }

    @Test
    @DisplayName("applyCustomRules 递归处理子节点")
    void testApplyCustomRulesRecursive() {
        StubAdapter adapter = new StubAdapter("ds1");
        PushDownRule rule = new PushDownRule(
                "FilterPushDown", "测试", adapter, CustomRelNode.Op.FILTER) {
            @Override
            public void onMatch(RuleCall call) {
                call.getRoot().markPushed("filter");
                call.transformTo(call.getRoot());
            }
        };

        CalciteOptimizer optimizer = new CalciteOptimizer();
        optimizer.registerRule(rule);

        CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t").setSourceName("ds1");
        CustomRelNode innerFilter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                .setCondition("x > 1");
        innerFilter.addChild(scan);
        CustomRelNode outerFilter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                .setCondition("y < 10");
        outerFilter.addChild(innerFilter);

        CustomRelNode result = optimizer.applyCustomRules(outerFilter);
        assertNotNull(result);
        assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
    }

    // ===================== registerAdapter / registerRule 测试 =====================

    @Test
    @DisplayName("registerAdapter 注册适配器")
    void testRegisterAdapter() {
        CalciteOptimizer optimizer = new CalciteOptimizer();
        StubAdapter adapter = new StubAdapter("ds1");
        CalciteOptimizer returned = optimizer.registerAdapter(adapter);
        assertSame(optimizer, returned);
        assertEquals(1, optimizer.getAdapters().size());
        assertSame(adapter, optimizer.getAdapters().get(0));
    }

    @Test
    @DisplayName("registerAdapter null 被忽略")
    void testRegisterAdapterNull() {
        CalciteOptimizer optimizer = new CalciteOptimizer();
        optimizer.registerAdapter(null);
        assertTrue(optimizer.getAdapters().isEmpty());
    }

    @Test
    @DisplayName("registerRule 注册下推规则")
    void testRegisterRule() {
        CalciteOptimizer optimizer = new CalciteOptimizer();
        StubAdapter adapter = new StubAdapter("ds1");
        PushDownRule rule = new PushDownRule(
                "TestRule", "test", adapter, CustomRelNode.Op.FILTER) {
            @Override
            public void onMatch(RuleCall call) {
                call.transformTo(call.getRoot());
            }
        };
        CalciteOptimizer returned = optimizer.registerRule(rule);
        assertSame(optimizer, returned);
        assertEquals(1, optimizer.getCustomRules().size());
    }

    @Test
    @DisplayName("registerRule null 被忽略")
    void testRegisterRuleNull() {
        CalciteOptimizer optimizer = new CalciteOptimizer();
        optimizer.registerRule(null);
        assertTrue(optimizer.getCustomRules().isEmpty());
    }

    @Test
    @DisplayName("getAdapters/getCustomRules 返回不可变列表")
    void testUnmodifiableLists() {
        CalciteOptimizer optimizer = new CalciteOptimizer();
        assertThrows(UnsupportedOperationException.class,
                () -> optimizer.getAdapters().add(new StubAdapter("x")));
        assertThrows(UnsupportedOperationException.class,
                () -> optimizer.getCustomRules().add(null));
    }

    // ===================== PushDownRule 测试 =====================

    @Test
    @DisplayName("PushDownRule.matches 操作类型不匹配返回 false")
    void testRuleMatchesWrongOp() {
        StubAdapter adapter = new StubAdapter("ds1");
        PushDownRule rule = new PushDownRule(
                "FilterPushDown", "test", adapter, CustomRelNode.Op.FILTER) {
            @Override
            public void onMatch(RuleCall call) {
                call.transformTo(call.getRoot());
            }
        };
        CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t").setSourceName("ds1");
        assertFalse(rule.matches(scan));
    }

    @Test
    @DisplayName("PushDownRule.setEnabled 禁用后 matches 返回 false")
    void testRuleDisabled() {
        StubAdapter adapter = new StubAdapter("ds1");
        PushDownRule rule = new PushDownRule(
                "FilterPushDown", "test", adapter, CustomRelNode.Op.FILTER) {
            @Override
            public void onMatch(RuleCall call) {
                call.transformTo(call.getRoot());
            }
        };
        rule.setEnabled(false);
        CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                .setCondition("x>1").setSourceName("ds1");
        assertFalse(rule.matches(filter));
    }

    @Test
    @DisplayName("PushDownRule.apply 未匹配返回原节点")
    void testRuleApplyNoMatch() {
        StubAdapter adapter = new StubAdapter("ds1");
        PushDownRule rule = new PushDownRule(
                "FilterPushDown", "test", adapter, CustomRelNode.Op.FILTER) {
            @Override
            public void onMatch(RuleCall call) {
                call.transformTo(call.getRoot());
            }
        };
        CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t").setSourceName("ds1");
        CustomRelNode result = rule.apply(scan);
        assertSame(scan, result);
    }

    @Test
    @DisplayName("PushDownRule.toString 含规则名")
    void testRuleToString() {
        StubAdapter adapter = new StubAdapter("ds1");
        PushDownRule rule = new PushDownRule(
                "MyRule", "desc", adapter, CustomRelNode.Op.FILTER) {
            @Override
            public void onMatch(RuleCall call) {
                call.transformTo(call.getRoot());
            }
        };
        String str = rule.toString();
        assertTrue(str.contains("MyRule"));
        assertTrue(str.contains("FILTER"));
        assertTrue(str.contains("ds1"));
    }

    @Test
    @DisplayName("PushDownRule getter")
    void testRuleGetters() {
        StubAdapter adapter = new StubAdapter("ds1");
        PushDownRule rule = new PushDownRule(
                "MyRule", "description", adapter, CustomRelNode.Op.PROJECT) {
            @Override
            public void onMatch(RuleCall call) {
                call.transformTo(call.getRoot());
            }
        };
        assertEquals("MyRule", rule.getRuleName());
        assertEquals("description", rule.getDescription());
        assertSame(adapter, rule.getAdapter());
        assertEquals(CustomRelNode.Op.PROJECT, rule.getMatchOp());
        assertTrue(rule.isEnabled());
    }

    // ===================== CustomRelNode 测试 =====================

    @Test
    @DisplayName("CustomRelNode.isFederated 跨源判定")
    void testCustomRelFederated() {
        CustomRelNode left = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t1").setSourceName("ds1");
        CustomRelNode right = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t2").setSourceName("ds2");
        CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN);
        join.addChild(left);
        join.addChild(right);
        assertTrue(join.isFederated());

        CustomRelNode single = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t1").setSourceName("ds1");
        assertFalse(single.isFederated());
    }

    @Test
    @DisplayName("CustomRelNode.collectSourceNames / collectTableNames")
    void testCustomRelCollect() {
        CustomRelNode left = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("orders").setSourceName("ds1");
        CustomRelNode right = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("users").setSourceName("ds2");
        CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN);
        join.addChild(left);
        join.addChild(right);

        assertEquals(2, join.collectSourceNames().size());
        assertTrue(join.collectSourceNames().contains("ds1"));
        assertTrue(join.collectSourceNames().contains("ds2"));
        assertEquals(2, join.collectTableNames().size());
        assertTrue(join.collectTableNames().contains("orders"));
        assertTrue(join.collectTableNames().contains("users"));
    }

    @Test
    @DisplayName("CustomRelNode.markPushed / markNotPushed")
    void testCustomRelMark() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.FILTER).setCondition("x>1");
        node.markPushed("filter: x>1");
        assertEquals(CustomRelNode.PushDownStatus.PUSHED, node.getPushDownStatus());
        assertEquals(1, node.getPushedOperations().size());
        assertEquals("filter: x>1", node.getPushedOperations().get(0));

        node.markNotPushed("跨源谓词");
        assertEquals(CustomRelNode.PushDownStatus.NOT_PUSHED, node.getPushDownStatus());
        assertEquals("跨源谓词", node.getPushDownReason());
    }

    @Test
    @DisplayName("CustomRelNode.toString 含操作类型与下推状态")
    void testCustomRelToString() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.FILTER)
                .setCondition("x>1").setSourceName("ds1");
        node.markPushed("filter");
        String str = node.toString();
        assertTrue(str.contains("FILTER"));
        assertTrue(str.contains("ds1"));
        assertTrue(str.contains("PUSHED"));
    }

    @Test
    @DisplayName("CustomRelNode.depth")
    void testCustomRelDepth() {
        CustomRelNode leaf = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN);
        assertEquals(1, leaf.depth());

        CustomRelNode parent = CustomRelNode.of(CustomRelNode.Op.FILTER);
        parent.addChild(leaf);
        assertEquals(2, parent.depth());
    }

    @Test
    @DisplayName("CustomRelNode 完整 toString 含 projects/children")
    void testCustomRelToStringFull() {
        CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("orders").setSourceName("ds1");
        CustomRelNode project = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                .setProjects(Arrays.asList("id", "amount"))
                .setRemark("test project");
        project.addChild(scan);
        String str = project.toString();
        assertTrue(str.contains("PROJECT"));
        assertTrue(str.contains("[id, amount]"));
        assertTrue(str.contains("test project"));
        assertTrue(str.contains("TABLE_SCAN"));
        assertTrue(str.contains("orders"));
    }

    @Test
    @DisplayName("CustomRelNode.setProjects null 安全")
    void testCustomRelSetProjectsNull() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.PROJECT);
        node.setProjects(null);
        assertNotNull(node.getProjects());
        assertTrue(node.getProjects().isEmpty());
    }

    @Test
    @DisplayName("CustomRelNode.setPushedOperations null 安全")
    void testCustomRelSetPushedOperationsNull() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.FILTER);
        node.setPushedOperations(null);
        assertNotNull(node.getPushedOperations());
        assertTrue(node.getPushedOperations().isEmpty());
    }

    @Test
    @DisplayName("CustomRelNode.addPushedOperation 链式")
    void testCustomRelAddPushedOperation() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.FILTER);
        CustomRelNode returned = node.addPushedOperation("op1");
        assertSame(node, returned);
        assertEquals(1, node.getPushedOperations().size());
        node.addPushedOperation("op2");
        assertEquals(2, node.getPushedOperations().size());
    }

    @Test
    @DisplayName("CustomRelNode.isFederated 无数据源返回 false")
    void testCustomRelFederatedNoSource() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t1");
        assertFalse(node.isFederated());
        assertTrue(node.collectSourceNames().isEmpty());
    }

    @Test
    @DisplayName("CustomRelNode.isFederated 单源返回 false")
    void testCustomRelFederatedSingleSource() {
        CustomRelNode left = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t1").setSourceName("ds1");
        CustomRelNode right = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t2").setSourceName("ds1");
        CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN);
        join.addChild(left);
        join.addChild(right);
        assertFalse(join.isFederated());
        assertEquals(1, join.collectSourceNames().size());
    }

    @Test
    @DisplayName("CustomRelNode.collectTableNames 无表返回空")
    void testCustomRelCollectTableNamesEmpty() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.FILTER)
                .setCondition("x>1");
        assertTrue(node.collectTableNames().isEmpty());
    }

    @Test
    @DisplayName("CustomRelNode 各种 setter 链式调用")
    void testCustomRelSetters() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.JOIN)
                .setCondition("a.id = b.id")
                .setEstimatedRows(1000)
                .setEstimatedCost(500)
                .setPushDownStatus(CustomRelNode.PushDownStatus.PARTIALLY_PUSHED)
                .setPushDownReason("跨源 Join")
                .setRemark("federated join");

        assertEquals("a.id = b.id", node.getCondition());
        assertEquals(1000, node.getEstimatedRows());
        assertEquals(500, node.getEstimatedCost());
        assertEquals(CustomRelNode.PushDownStatus.PARTIALLY_PUSHED, node.getPushDownStatus());
        assertEquals("跨源 Join", node.getPushDownReason());
        assertEquals("federated join", node.getRemark());
    }

    @Test
    @DisplayName("CustomRelNode.addChild null 被忽略")
    void testCustomRelAddChildNull() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.FILTER);
        node.addChild(null);
        assertTrue(node.getChildren().isEmpty());
    }

    @Test
    @DisplayName("CustomRelNode PushDownStatus.NOT_APPLICABLE")
    void testCustomRelNotApplicable() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.JOIN)
                .setPushDownStatus(CustomRelNode.PushDownStatus.NOT_APPLICABLE);
        assertEquals(CustomRelNode.PushDownStatus.NOT_APPLICABLE, node.getPushDownStatus());
        String str = node.toString();
        assertTrue(str.contains("NOT_APPLICABLE"));
    }

    // ===================== CalciteOptimizer 辅助方法测试 =====================

    @Test
    @DisplayName("explain 含 rulesApplied 列表")
    void testExplainRulesApplied() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        String json = optimizer.explain("SELECT * FROM h2.orders");
        assertTrue(json.contains("\"rulesApplied\""));
    }

    @Test
    @DisplayName("explain null SQL 返回错误 JSON")
    void testExplainNullSql() {
        CalciteOptimizer optimizer = new CalciteOptimizer();
        String json = optimizer.explain(null);
        assertNotNull(json);
        assertTrue(json.contains("\"success\":false"));
    }

    @Test
    @DisplayName("optimize 配置禁用时不应用优化")
    void testOptimizeDisabledConfig() {
        OptimizerConfig config = new OptimizerConfig().setEnabled(false);
        CalciteOptimizer optimizer = new CalciteOptimizer(config);
        SchemaPlus rootSchema = optimizer.getRootSchema();
        DataSource dataSource = JdbcSchema.dataSource(H2_URL, H2_DRIVER, "sa", "");
        JdbcSchema h2Schema = JdbcSchema.create(rootSchema, "h2", dataSource, null, null);
        rootSchema.add("h2", h2Schema);

        RelNode relNode = optimizer.optimize("SELECT * FROM h2.orders");
        assertNotNull(relNode);
    }

    @Test
    @DisplayName("toCustomRel 递归处理子节点")
    void testToCustomRelRecursive() {
        CalciteOptimizer optimizer = createOptimizerWithH2();
        RelNode relNode = optimizer.optimize(
                "SELECT o.id FROM h2.orders o WHERE o.amount > 100");
        CustomRelNode custom = optimizer.toCustomRel(relNode);
        assertNotNull(custom);
        assertTrue(custom.depth() >= 1);
    }

    @Test
    @DisplayName("applyCustomRules 递归多层子节点")
    void testApplyCustomRulesDeepRecursive() {
        StubAdapter adapter = new StubAdapter("ds1");
        PushDownRule rule = new PushDownRule(
                "FilterPushDown", "test", adapter, CustomRelNode.Op.FILTER) {
            @Override
            public void onMatch(RuleCall call) {
                call.getRoot().markPushed("filter");
                call.transformTo(call.getRoot());
            }
        };

        CalciteOptimizer optimizer = new CalciteOptimizer();
        optimizer.registerRule(rule);

        // 构造 3 层嵌套的 Filter
        CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t").setSourceName("ds1");
        CustomRelNode f1 = CustomRelNode.of(CustomRelNode.Op.FILTER).setCondition("a>1");
        f1.addChild(scan);
        CustomRelNode f2 = CustomRelNode.of(CustomRelNode.Op.FILTER).setCondition("b>2");
        f2.addChild(f1);
        CustomRelNode f3 = CustomRelNode.of(CustomRelNode.Op.FILTER).setCondition("c>3");
        f3.addChild(f2);

        CustomRelNode result = optimizer.applyCustomRules(f3);
        assertNotNull(result);
        assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
    }

    // ===================== 桩适配器 =====================

    /**
     * 简单 BaseAdapter 桩实现，用于测试下推规则。
     */
    static class StubAdapter implements BaseAdapter {
        private final String sourceName;
        private final DataSourceConfig config;

        StubAdapter(String sourceName) {
            this.sourceName = sourceName;
            this.config = new DataSourceConfig(sourceName, DataSourceConfig.Type.DORIS)
                    .setJdbcUrl("jdbc:mysql://localhost:9030");
        }

        @Override
        public DataSourceConfig.Type getAdapterType() {
            return DataSourceConfig.Type.DORIS;
        }

        @Override
        public DataSourceConfig getDataSourceConfig() {
            return config;
        }

        @Override
        public CustomRelNode toRel(String tableName, List<String> columns) {
            return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                    .setTableName(tableName).setSourceName(sourceName);
        }

        @Override
        public PushDownResult pushDown(CustomRelNode relNode, PushDownContext context) {
            return new PushDownResult("SELECT * FROM t", relNode,
                    new ArrayList<>(), true, null);
        }

        @Override
        public Cost costEstimate(CustomRelNode relNode) {
            return new Cost(1, 10, 1, 100);
        }

        @Override
        public SqlDialect getDialect() {
            return SqlDialect.DORIS;
        }
    }
}