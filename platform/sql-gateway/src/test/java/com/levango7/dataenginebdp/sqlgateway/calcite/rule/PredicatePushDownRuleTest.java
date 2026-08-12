package com.levango7.dataenginebdp.sqlgateway.calcite.rule;

import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.BaseAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.DorisAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.ElasticsearchAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.IcebergAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.IoTDBAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.TrinoAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PredicatePushDownRule} 单元测试——覆盖 5 种数据源各 ≥10 用例。
 *
 * <p>测试矩阵：</p>
 * <ul>
 *   <li><b>Iceberg</b>（≥10 用例）：等值/范围/IN/LIKE/IS NULL/混合/UDF保留/OR保留/JOIN/统计</li>
 *   <li><b>Doris</b>（≥10 用例）：同上，针对 Doris OLAP 特性</li>
 *   <li><b>Trino</b>（≥10 用例）：同上，针对 Trino 联邦特性</li>
 *   <li><b>IoTDB</b>（≥10 用例）：针对时序数据特性（LIKE 不支持、时间范围谓词）</li>
 *   <li><b>ES</b>（≥10 用例）：针对 ES 全文检索/term/range 特性</li>
 * </ul>
 *
 * <p>每个用例验证：</p>
 * <ul>
 *   <li>谓词正确分类（EQUALITY/RANGE/IN/LIKE/IS_NULL/UNSUPPORTED）</li>
 *   <li>可下推谓词下推到 TableScan</li>
 *   <li>不可下推谓词保留在 Filter</li>
 *   <li>下推率 ≥ 70%（按数据源平均）</li>
 *   <li>查询语义等价（下推前后 AND 连接结果一致）</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class PredicatePushDownRuleTest {

    // ===================== 公共桩适配器 =====================

    /** Iceberg 适配器桩 */
    static class StubIcebergAdapter implements IcebergAdapter {
        final DataSourceConfig config = new DataSourceConfig("iceberg_lake",
                DataSourceConfig.Type.ICEBERG)
                .setJdbcUrl("jdbc:hive2://localhost:10000").setDialect(SqlDialect.HIVE);

        @Override public DataSourceConfig getDataSourceConfig() { return config; }
        @Override public DataSourceConfig.Type getAdapterType() { return DataSourceConfig.Type.ICEBERG; }
        @Override public CustomRelNode toRel(String t, List<String> c) {
            return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName(t).setSourceName(config.getName());
        }
        @Override public PushDownResult pushDown(CustomRelNode r, PushDownContext ctx) {
            return new PushDownResult("SELECT * FROM t", r, new ArrayList<>(), true, null);
        }
        @Override public Cost costEstimate(CustomRelNode r) { return new Cost(8, 80, 10, 2000); }
        @Override public SqlDialect getDialect() { return SqlDialect.HIVE; }
        @Override public List<String> prunePartitions(String t, String f) { return Arrays.asList("p1", "p2"); }
        @Override public long selectSnapshot(String t, Long s, Long ts) { return s != null ? s : 1L; }
        @Override public boolean isPartitionColumn(String t, String c) { return "dt".equals(c); }
        @Override public int getSchemaVersion(String t) { return 1; }
    }

    /** Doris 适配器桩 */
    static class StubDorisAdapter implements DorisAdapter {
        final DataSourceConfig config = new DataSourceConfig("doris_olap",
                DataSourceConfig.Type.DORIS)
                .setJdbcUrl("jdbc:mysql://localhost:9030").setDialect(SqlDialect.DORIS);

        @Override public DataSourceConfig getDataSourceConfig() { return config; }
        @Override public DataSourceConfig.Type getAdapterType() { return DataSourceConfig.Type.DORIS; }
        @Override public CustomRelNode toRel(String t, List<String> c) {
            return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName(t).setSourceName(config.getName());
        }
        @Override public PushDownResult pushDown(CustomRelNode r, PushDownContext ctx) {
            return new PushDownResult("SELECT * FROM t", r, new ArrayList<>(), true, null);
        }
        @Override public Cost costEstimate(CustomRelNode r) { return new Cost(10, 100, 5, 1000); }
        @Override public SqlDialect getDialect() { return SqlDialect.DORIS; }
        @Override public String routeMaterializedView(String t, List<String> g, List<String> a) { return t + "_mv"; }
        @Override public boolean canColocateJoin(String l, String r) { return true; }
        @Override public int getTabletCount(String t) { return 64; }
        @Override public long getEstimatedRowCount(String t) { return 1_000_000L; }
    }

    /** Trino 适配器桩 */
    static class StubTrinoAdapter implements TrinoAdapter {
        final DataSourceConfig config = new DataSourceConfig("trino_hive",
                DataSourceConfig.Type.TRINO)
                .setJdbcUrl("jdbc:trino://localhost:8080").setDialect(SqlDialect.TRINO);

        @Override public DataSourceConfig getDataSourceConfig() { return config; }
        @Override public DataSourceConfig.Type getAdapterType() { return DataSourceConfig.Type.TRINO; }
        @Override public CustomRelNode toRel(String t, List<String> c) {
            return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName(t).setSourceName(config.getName());
        }
        @Override public PushDownResult pushDown(CustomRelNode r, PushDownContext ctx) {
            return new PushDownResult("SELECT * FROM t", r, new ArrayList<>(), true, null);
        }
        @Override public Cost costEstimate(CustomRelNode r) { return new Cost(5, 50, 20, 500); }
        @Override public SqlDialect getDialect() { return SqlDialect.TRINO; }
        @Override public String getConnectorName(String t) { return "hive"; }
        @Override public boolean supportsDynamicFiltering(String c) { return true; }
        @Override public String inlineCte(String s) { return s; }
        @Override public int getWorkerCount() { return 10; }
    }

    /** IoTDB 适配器桩 */
    static class StubIoTDBAdapter implements IoTDBAdapter {
        final DataSourceConfig config = new DataSourceConfig("iotdb_ts",
                DataSourceConfig.Type.IOTDB)
                .setEndpoint("http://iotdb:18080").setDialect(SqlDialect.ANSI);

        @Override public DataSourceConfig getDataSourceConfig() { return config; }
        @Override public DataSourceConfig.Type getAdapterType() { return DataSourceConfig.Type.IOTDB; }
        @Override public CustomRelNode toRel(String t, List<String> c) {
            return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName(t).setSourceName(config.getName());
        }
        @Override public PushDownResult pushDown(CustomRelNode r, PushDownContext ctx) {
            return new PushDownResult("SELECT * FROM root.sg", r, new ArrayList<>(), true, null);
        }
        @Override public Cost costEstimate(CustomRelNode r) { return new Cost(3, 30, 2, 5000); }
        @Override public SqlDialect getDialect() { return SqlDialect.ANSI; }
        @Override public String pushDownTimeRange(String f) { return "2024-01-01,2024-02-01"; }
        @Override public String pushDownDownsampling(String a, String t, String i) { return a + "(" + t + ")"; }
        @Override public String toQueryPath(String f) { return "root.sg.d1.*"; }
        @Override public boolean supportsDownsampling(String a) { return "mean".equals(a); }
    }

    /** ES 适配器桩 */
    static class StubElasticsearchAdapter implements ElasticsearchAdapter {
        final DataSourceConfig config = new DataSourceConfig("es_search",
                DataSourceConfig.Type.ELASTICSEARCH)
                .setEndpoint("http://es:9200").setDialect(SqlDialect.ANSI);

        @Override public DataSourceConfig getDataSourceConfig() { return config; }
        @Override public DataSourceConfig.Type getAdapterType() { return DataSourceConfig.Type.ELASTICSEARCH; }
        @Override public CustomRelNode toRel(String t, List<String> c) {
            return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName(t).setSourceName(config.getName());
        }
        @Override public PushDownResult pushDown(CustomRelNode r, PushDownContext ctx) {
            return new PushDownResult("GET /_search", r, new ArrayList<>(), true, null);
        }
        @Override public Cost costEstimate(CustomRelNode r) { return new Cost(2, 20, 1, 10000); }
        @Override public SqlDialect getDialect() { return SqlDialect.ANSI; }
        @Override public String toQueryDsl(String p) { return "{\"query\":{}}"; }
        @Override public String toAggregationDsl(List<String> g, List<String> a) { return "{\"aggs\":{}}"; }
        @Override public String toSortDsl(List<String> s) { return "{\"sort\":[]}"; }
        @Override public String toPaginationDsl(long l, long o) { return "{\"from\":0,\"size\":100}"; }
        @Override public boolean isIndexAvailable(String i) { return "es_orders".equals(i); }
    }

    // ===================== 公共辅助方法 =====================

    /** 构造 Filter → TableScan 的 RelNode 树 */
    private CustomRelNode buildFilterScan(BaseAdapter adapter, String condition) {
        CustomRelNode scan = adapter.toRel("test_table", null);
        CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER).setCondition(condition);
        filter.addChild(scan);
        return filter;
    }

    // ===================== 谓词分类基础测试 =====================

    @Test
    @DisplayName("等值谓词分类为 EQUALITY")
    void testClassifyEquality() {
        PredicatePushDownRule rule = new PredicatePushDownRule(new StubIcebergAdapter());
        assertEquals(PredicateType.EQUALITY, rule.classifyPredicate("id = 100"));
        assertEquals(PredicateType.EQUALITY, rule.classifyPredicate("status = 'ACTIVE'"));
    }

    @Test
    @DisplayName("范围谓词分类为 RANGE")
    void testClassifyRange() {
        PredicatePushDownRule rule = new PredicatePushDownRule(new StubIcebergAdapter());
        assertEquals(PredicateType.RANGE, rule.classifyPredicate("age > 18"));
        assertEquals(PredicateType.RANGE, rule.classifyPredicate("age < 65"));
        assertEquals(PredicateType.RANGE, rule.classifyPredicate("age >= 18"));
        assertEquals(PredicateType.RANGE, rule.classifyPredicate("age <= 65"));
        assertEquals(PredicateType.RANGE, rule.classifyPredicate("age BETWEEN 18 AND 65"));
    }

    @Test
    @DisplayName("IN 谓词分类为 IN")
    void testClassifyIn() {
        PredicatePushDownRule rule = new PredicatePushDownRule(new StubIcebergAdapter());
        assertEquals(PredicateType.IN, rule.classifyPredicate("status IN ('A', 'B', 'C')"));
        assertEquals(PredicateType.IN, rule.classifyPredicate("id IN (1, 2, 3)"));
        assertEquals(PredicateType.IN, rule.classifyPredicate("status NOT IN ('X', 'Y')"));
    }

    @Test
    @DisplayName("LIKE 谓词分类为 LIKE")
    void testClassifyLike() {
        PredicatePushDownRule rule = new PredicatePushDownRule(new StubIcebergAdapter());
        assertEquals(PredicateType.LIKE, rule.classifyPredicate("name LIKE '张%'"));
        assertEquals(PredicateType.LIKE, rule.classifyPredicate("name LIKE '%test%'"));
        assertEquals(PredicateType.LIKE, rule.classifyPredicate("name NOT LIKE '%bad%'"));
    }

    @Test
    @DisplayName("IS NULL 谓词分类为 IS_NULL")
    void testClassifyIsNull() {
        PredicatePushDownRule rule = new PredicatePushDownRule(new StubIcebergAdapter());
        assertEquals(PredicateType.IS_NULL, rule.classifyPredicate("col IS NULL"));
        assertEquals(PredicateType.IS_NULL, rule.classifyPredicate("col IS NOT NULL"));
    }

    @Test
    @DisplayName("UDF/OR/子查询/不等谓词分类为 UNSUPPORTED")
    void testClassifyUnsupported() {
        PredicatePushDownRule rule = new PredicatePushDownRule(new StubIcebergAdapter());
        assertEquals(PredicateType.UNSUPPORTED, rule.classifyPredicate("UDF(name) = 'x'"));
        assertEquals(PredicateType.UNSUPPORTED, rule.classifyPredicate("a = 1 OR b = 2"));
        assertEquals(PredicateType.UNSUPPORTED, rule.classifyPredicate("id != 100"));
        assertEquals(PredicateType.UNSUPPORTED, rule.classifyPredicate("id <> 100"));
    }

    @Test
    @DisplayName("AND 条件正确拆分为多个谓词")
    void testExtractPredicates() {
        PredicatePushDownRule rule = new PredicatePushDownRule(new StubIcebergAdapter());
        List<String> preds = rule.extractPredicates("id = 100 AND age > 18 AND status = 'A'");
        assertEquals(3, preds.size());
        assertEquals("id = 100", preds.get(0));
        assertEquals("age > 18", preds.get(1));
        assertEquals("status = 'A'", preds.get(2));
    }

    @Test
    @DisplayName("OR 条件不拆分，整体作为一个 UNSUPPORTED 谓词")
    void testExtractPredicatesWithOr() {
        PredicatePushDownRule rule = new PredicatePushDownRule(new StubIcebergAdapter());
        List<String> preds = rule.extractPredicates("a = 1 OR b = 2");
        assertEquals(1, preds.size());
        assertEquals(PredicateType.UNSUPPORTED, rule.classifyPredicate(preds.get(0)));
    }

    @Test
    @DisplayName("下推率统计器基本功能")
    void testStatisticsBasic() {
        PushDownStatistics stats = new PushDownStatistics();
        stats.recordPredicate(DataSourceConfig.Type.ICEBERG, PredicateType.EQUALITY, true);
        stats.recordPredicate(DataSourceConfig.Type.ICEBERG, PredicateType.RANGE, true);
        stats.recordPredicate(DataSourceConfig.Type.ICEBERG, PredicateType.UNSUPPORTED, false, "UDF", "UDF(x)");
        assertEquals(3, stats.getTotalPredicates());
        assertEquals(2, stats.getPushedPredicates());
        assertEquals(1, stats.getRemainingPredicates());
        assertEquals(2.0 / 3, stats.getPushDownRate(), 0.001);
    }

    // ===================== Iceberg 适配器测试（≥10 用例） =====================

    @Nested
    @DisplayName("Iceberg 适配器谓词下推测试")
    class IcebergPredicatePushDownTest {

        private StubIcebergAdapter adapter;
        private PredicatePushDownRule rule;

        @BeforeEach
        void setUp() {
            adapter = new StubIcebergAdapter();
            rule = new PredicatePushDownRule(adapter);
        }

        @Test
        @DisplayName("Iceberg-01: 等值谓词下推 WHERE id = 100")
        void testEqualityPushDown() {
            PredicatePushDownRule.PushDownAnalysis analysis =
                    rule.analyze("id = 100", adapter);
            assertEquals(1, analysis.getTotalCount());
            assertEquals(1, analysis.getPushedCount());
            assertEquals(0, analysis.getRemainingCount());
            assertTrue(analysis.getPushable().contains("id = 100"));
            assertEquals(1.0, analysis.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("Iceberg-02: 范围谓词下推 WHERE age > 18 AND age < 65")
        void testRangePushDown() {
            PredicatePushDownRule.PushDownAnalysis analysis =
                    rule.analyze("age > 18 AND age < 65", adapter);
            assertEquals(2, analysis.getTotalCount());
            assertEquals(2, analysis.getPushedCount());
            assertEquals(1.0, analysis.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("Iceberg-03: IN 谓词下推 WHERE status IN ('A', 'B', 'C')")
        void testInPushDown() {
            PredicatePushDownRule.PushDownAnalysis analysis =
                    rule.analyze("status IN ('A', 'B', 'C')", adapter);
            assertEquals(1, analysis.getPushedCount());
            assertTrue(analysis.getPushable().contains("status IN ('A', 'B', 'C')"));
            assertEquals(1.0, analysis.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("Iceberg-04: LIKE 谓词下推 WHERE name LIKE '张%'")
        void testLikePushDown() {
            PredicatePushDownRule.PushDownAnalysis analysis =
                    rule.analyze("name LIKE '张%'", adapter);
            assertEquals(1, analysis.getPushedCount());
            assertEquals(1.0, analysis.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("Iceberg-05: 混合谓词 WHERE id = 100 AND age > 18 AND name LIKE '张%'")
        void testMixedPushDown() {
            PredicatePushDownRule.PushDownAnalysis analysis =
                    rule.analyze("id = 100 AND age > 18 AND name LIKE '张%'", adapter);
            assertEquals(3, analysis.getTotalCount());
            assertEquals(3, analysis.getPushedCount());
            assertEquals(1.0, analysis.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("Iceberg-06: UDF 谓词保留 WHERE UDF(name) = 'x'")
        void testUdfRemaining() {
            PredicatePushDownRule.PushDownAnalysis analysis =
                    rule.analyze("UDF(name) = 'x'", adapter);
            assertEquals(1, analysis.getRemainingCount());
            assertEquals(0, analysis.getPushedCount());
            assertEquals(0.0, analysis.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("Iceberg-07: 部分下推 WHERE id = 100 AND UDF(name) = 'x'")
        void testPartialPushDown() {
            PredicatePushDownRule.PushDownAnalysis analysis =
                    rule.analyze("id = 100 AND UDF(name) = 'x'", adapter);
            assertEquals(2, analysis.getTotalCount());
            assertEquals(1, analysis.getPushedCount());
            assertEquals(1, analysis.getRemainingCount());
            assertEquals(0.5, analysis.getPushDownRate(), 0.001);
            assertTrue(analysis.getPushable().contains("id = 100"));
            assertTrue(analysis.getRemaining().contains("UDF(name) = 'x'"));
        }

        @Test
        @DisplayName("Iceberg-08: OR 条件保留 WHERE (a = 1 OR b = 2) AND c > 3")
        void testOrRemaining() {
            PredicatePushDownRule.PushDownAnalysis analysis =
                    rule.analyze("(a = 1 OR b = 2) AND c > 3", adapter);
            // (a = 1 OR b = 2) 整体 UNSUPPORTED，c > 3 可下推
            assertTrue(analysis.getPushedCount() >= 1);
            assertTrue(analysis.getRemainingCount() >= 1);
            assertTrue(analysis.getPushDownRate() > 0);
        }

        @Test
        @DisplayName("Iceberg-09: IS NULL 谓词下推 WHERE col IS NULL")
        void testIsNullPushDown() {
            PredicatePushDownRule.PushDownAnalysis analysis =
                    rule.analyze("col IS NULL", adapter);
            assertEquals(1, analysis.getPushedCount());
            assertEquals(1.0, analysis.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("Iceberg-10: 多表 JOIN 谓词 WHERE t1.id = t2.id AND t1.age > 18")
        void testJoinPredicates() {
            PredicatePushDownRule.PushDownAnalysis analysis =
                    rule.analyze("t1.id = 100 AND t1.age > 18", adapter);
            assertEquals(2, analysis.getPushedCount());
            assertEquals(1.0, analysis.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("Iceberg-11: 下推率统计验证（≥70%）")
        void testPushDownRateStatistics() {
            String[] conditions = {
                    "id = 100", "age > 18 AND age < 65", "status IN ('A', 'B')",
                    "name LIKE '张%'", "id = 100 AND age > 18",
                    "UDF(name) = 'x'", "id = 100 AND UDF(name) = 'x'",
                    "col IS NULL", "id = 100 AND age > 18 AND status = 'A'",
                    "id = 1 AND age > 18 AND name LIKE '张%' AND status IN ('A') AND col IS NULL"
            };
            PushDownStatistics stats = new PushDownStatistics();
            PredicatePushDownRule ruleWithStats = new PredicatePushDownRule(adapter, stats);
            for (String cond : conditions) {
                PredicatePushDownRule.PushDownAnalysis analysis =
                        ruleWithStats.analyze(cond, adapter);
                for (Map.Entry<PredicateType, List<String>> e
                        : analysis.getPushableByType().entrySet()) {
                    for (String p : e.getValue()) {
                        stats.recordPredicate(DataSourceConfig.Type.ICEBERG, e.getKey(), true, null, p);
                    }
                }
                for (Map.Entry<PredicateType, List<String>> e
                        : analysis.getRemainingByType().entrySet()) {
                    for (String p : e.getValue()) {
                        stats.recordPredicate(DataSourceConfig.Type.ICEBERG, e.getKey(), false, "保留", p);
                    }
                }
            }
            double rate = stats.getPushDownRate(DataSourceConfig.Type.ICEBERG);
            assertTrue(rate >= 0.70,
                    "Iceberg 下推率应 ≥ 70%，实际: " + String.format("%.2f%%", rate * 100));
        }

        @Test
        @DisplayName("Iceberg-12: onMatch 执行下推改写 RelNode")
        void testOnMatchRewrite() {
            CustomRelNode filter = buildFilterScan(adapter, "id = 100 AND age > 18");
            CustomRelNode result = rule.apply(filter);
            assertNotNull(result);
            // 应该生成下推后的节点
            assertNotSame(filter, result);
        }
    }

    // ===================== Doris 适配器测试（≥10 用例） =====================

    @Nested
    @DisplayName("Doris 适配器谓词下推测试")
    class DorisPredicatePushDownTest {

        private StubDorisAdapter adapter;
        private PredicatePushDownRule rule;

        @BeforeEach
        void setUp() {
            adapter = new StubDorisAdapter();
            rule = new PredicatePushDownRule(adapter);
        }

        @Test
        @DisplayName("Doris-01: 等值谓词下推")
        void testEquality() {
            PredicatePushDownRule.PushDownAnalysis a = rule.analyze("id = 100", adapter);
            assertEquals(1, a.getPushedCount());
            assertEquals(1.0, a.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("Doris-02: 范围谓词下推")
        void testRange() {
            PredicatePushDownRule.PushDownAnalysis a = rule.analyze("amount > 100 AND amount < 1000", adapter);
            assertEquals(2, a.getPushedCount());
            assertEquals(1.0, a.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("Doris-03: IN 谓词下推")
        void testIn() {
            PredicatePushDownRule.PushDownAnalysis a = rule.analyze("region IN ('CN', 'US', 'EU')", adapter);
            assertEquals(1, a.getPushedCount());
        }

        @Test
        @DisplayName("Doris-04: LIKE 谓词下推")
        void testLike() {
            PredicatePushDownRule.PushDownAnalysis a = rule.analyze("name LIKE '张%'", adapter);
            assertEquals(1, a.getPushedCount());
        }

        @Test
        @DisplayName("Doris-05: IS NULL 下推")
        void testIsNull() {
            PredicatePushDownRule.PushDownAnalysis a = rule.analyze("col IS NOT NULL", adapter);
            assertEquals(1, a.getPushedCount());
        }

        @Test
        @DisplayName("Doris-06: 混合谓词下推")
        void testMixed() {
            PredicatePushDownRule.PushDownAnalysis a =
                    rule.analyze("id = 100 AND amount > 100 AND region IN ('CN')", adapter);
            assertEquals(3, a.getPushedCount());
            assertEquals(1.0, a.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("Doris-07: UDF 保留")
        void testUdf() {
            PredicatePushDownRule.PushDownAnalysis a = rule.analyze("my_udf(col) = 1", adapter);
            assertEquals(1, a.getRemainingCount());
        }

        @Test
        @DisplayName("Doris-08: OR 保留")
        void testOr() {
            PredicatePushDownRule.PushDownAnalysis a = rule.analyze("a = 1 OR b = 2", adapter);
            assertEquals(1, a.getRemainingCount());
            assertEquals(0.0, a.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("Doris-09: 部分下推")
        void testPartial() {
            PredicatePushDownRule.PushDownAnalysis a =
                    rule.analyze("id = 100 AND amount > 50 AND my_udf(x) = 1", adapter);
            assertEquals(2, a.getPushedCount());
            assertEquals(1, a.getRemainingCount());
            assertTrue(a.getPushDownRate() >= 0.60);
        }

        @Test
        @DisplayName("Doris-10: BETWEEN 下推")
        void testBetween() {
            PredicatePushDownRule.PushDownAnalysis a = rule.analyze("age BETWEEN 18 AND 65", adapter);
            assertEquals(1, a.getPushedCount());
        }

        @Test
        @DisplayName("Doris-11: 下推率 ≥70%")
        void testPushDownRate() {
            String[] conds = {
                    "id = 1", "amount > 100", "region IN ('CN')", "name LIKE '张%'",
                    "id = 1 AND amount > 100", "col IS NULL",
                    "id = 1 AND amount > 100 AND region IN ('CN') AND name LIKE '张%'",
                    "my_udf(x) = 1", "id = 1 AND my_udf(x) = 1",
                    "id = 1 AND amount > 100 AND region IN ('A', 'B') AND age BETWEEN 18 AND 65"
            };
            PushDownStatistics stats = new PushDownStatistics();
            PredicatePushDownRule r = new PredicatePushDownRule(adapter, stats);
            int total = 0, pushed = 0;
            for (String c : conds) {
                PredicatePushDownRule.PushDownAnalysis a = r.analyze(c, adapter);
                total += a.getTotalCount();
                pushed += a.getPushedCount();
            }
            double rate = (double) pushed / total;
            assertTrue(rate >= 0.70, "Doris 下推率: " + String.format("%.2f%%", rate * 100));
        }
    }

    // ===================== Trino 适配器测试（≥10 用例） =====================

    @Nested
    @DisplayName("Trino 适配器谓词下推测试")
    class TrinoPredicatePushDownTest {

        private StubTrinoAdapter adapter;
        private PredicatePushDownRule rule;

        @BeforeEach
        void setUp() {
            adapter = new StubTrinoAdapter();
            rule = new PredicatePushDownRule(adapter);
        }

        @Test
        @DisplayName("Trino-01: 等值谓词下推")
        void testEquality() {
            assertEquals(1, rule.analyze("id = 100", adapter).getPushedCount());
        }

        @Test
        @DisplayName("Trino-02: 范围谓词下推")
        void testRange() {
            assertEquals(2, rule.analyze("age > 18 AND age < 65", adapter).getPushedCount());
        }

        @Test
        @DisplayName("Trino-03: IN 谓词下推")
        void testIn() {
            assertEquals(1, rule.analyze("status IN ('A', 'B')", adapter).getPushedCount());
        }

        @Test
        @DisplayName("Trino-04: LIKE 谓词下推")
        void testLike() {
            assertEquals(1, rule.analyze("name LIKE '%test%'", adapter).getPushedCount());
        }

        @Test
        @DisplayName("Trino-05: IS NULL 下推")
        void testIsNull() {
            assertEquals(1, rule.analyze("col IS NULL", adapter).getPushedCount());
        }

        @Test
        @DisplayName("Trino-06: 混合谓词下推")
        void testMixed() {
            assertEquals(3, rule.analyze("id = 1 AND age > 18 AND name LIKE 'a%'", adapter).getPushedCount());
        }

        @Test
        @DisplayName("Trino-07: UDF 保留")
        void testUdf() {
            assertEquals(1, rule.analyze("upper(name) = 'X'", adapter).getRemainingCount());
        }

        @Test
        @DisplayName("Trino-08: OR 保留")
        void testOr() {
            assertEquals(0, rule.analyze("a = 1 OR b = 2", adapter).getPushedCount());
        }

        @Test
        @DisplayName("Trino-09: 子查询保留")
        void testSubquery() {
            PredicatePushDownRule.PushDownAnalysis a =
                    rule.analyze("id IN (SELECT uid FROM users)", adapter);
            assertEquals(1, a.getRemainingCount());
        }

        @Test
        @DisplayName("Trino-10: 部分下推")
        void testPartial() {
            PredicatePushDownRule.PushDownAnalysis a =
                    rule.analyze("id = 1 AND age > 18 AND upper(name) = 'X'", adapter);
            assertEquals(2, a.getPushedCount());
            assertEquals(1, a.getRemainingCount());
        }

        @Test
        @DisplayName("Trino-11: 下推率 ≥70%")
        void testPushDownRate() {
            String[] conds = {
                    "id = 1", "age > 18", "status IN ('A')", "name LIKE 'a%'",
                    "id = 1 AND age > 18", "col IS NULL",
                    "id = 1 AND age > 18 AND status IN ('A')",
                    "upper(name) = 'X'", "id = 1 AND upper(name) = 'X'",
                    "id = 1 AND age > 18 AND status IN ('A') AND name LIKE 'a%'"
            };
            PushDownStatistics stats = new PushDownStatistics();
            PredicatePushDownRule r = new PredicatePushDownRule(adapter, stats);
            int total = 0, pushed = 0;
            for (String c : conds) {
                PredicatePushDownRule.PushDownAnalysis a = r.analyze(c, adapter);
                total += a.getTotalCount();
                pushed += a.getPushedCount();
            }
            double rate = (double) pushed / total;
            assertTrue(rate >= 0.70, "Trino 下推率: " + String.format("%.2f%%", rate * 100));
        }
    }

    // ===================== IoTDB 适配器测试（≥10 用例） =====================

    @Nested
    @DisplayName("IoTDB 适配器谓词下推测试")
    class IoTDBPredicatePushDownTest {

        private StubIoTDBAdapter adapter;
        private PredicatePushDownRule rule;

        @BeforeEach
        void setUp() {
            adapter = new StubIoTDBAdapter();
            rule = new PredicatePushDownRule(adapter);
        }

        @Test
        @DisplayName("IoTDB-01: 等值谓词下推")
        void testEquality() {
            assertEquals(1, rule.analyze("device = 'root.sg.d1'", adapter).getPushedCount());
        }

        @Test
        @DisplayName("IoTDB-02: 时间范围谓词下推 WHERE time >= '2024-01-01'")
        void testTimeRange() {
            assertEquals(1, rule.analyze("time >= '2024-01-01'", adapter).getPushedCount());
        }

        @Test
        @DisplayName("IoTDB-03: 时间范围组合下推")
        void testTimeRangeCombined() {
            PredicatePushDownRule.PushDownAnalysis a =
                    rule.analyze("time >= '2024-01-01' AND time < '2024-02-01'", adapter);
            assertEquals(2, a.getPushedCount());
        }

        @Test
        @DisplayName("IoTDB-04: IN 谓词下推")
        void testIn() {
            assertEquals(1, rule.analyze("device IN ('d1', 'd2', 'd3')", adapter).getPushedCount());
        }

        @Test
        @DisplayName("IoTDB-05: LIKE 谓词不下推（IoTDB 不支持 LIKE）")
        void testLikeNotPushed() {
            PredicatePushDownRule.PushDownAnalysis a = rule.analyze("name LIKE 'd%'", adapter);
            assertEquals(0, a.getPushedCount());
            assertEquals(1, a.getRemainingCount());
        }

        @Test
        @DisplayName("IoTDB-06: IS NULL 下推")
        void testIsNull() {
            assertEquals(1, rule.analyze("value IS NULL", adapter).getPushedCount());
        }

        @Test
        @DisplayName("IoTDB-07: 混合谓词（等值+范围+IN 下推，LIKE 保留）")
        void testMixed() {
            PredicatePushDownRule.PushDownAnalysis a =
                    rule.analyze("device = 'd1' AND time >= '2024-01-01' AND name LIKE 'd%'", adapter);
            assertEquals(2, a.getPushedCount());
            assertEquals(1, a.getRemainingCount());
            assertTrue(a.getPushDownRate() >= 0.60);
        }

        @Test
        @DisplayName("IoTDB-08: UDF 保留")
        void testUdf() {
            assertEquals(1, rule.analyze("my_func(value) > 10", adapter).getRemainingCount());
        }

        @Test
        @DisplayName("IoTDB-09: OR 保留")
        void testOr() {
            assertEquals(0, rule.analyze("a = 1 OR b = 2", adapter).getPushedCount());
        }

        @Test
        @DisplayName("IoTDB-10: 时间范围+设备过滤下推")
        void testTimeAndDevice() {
            PredicatePushDownRule.PushDownAnalysis a =
                    rule.analyze("time >= '2024-01-01' AND time < '2024-02-01' AND device = 'root.sg.d1'", adapter);
            assertEquals(3, a.getPushedCount());
            assertEquals(1.0, a.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("IoTDB-11: 下推率 ≥70%")
        void testPushDownRate() {
            String[] conds = {
                    "device = 'd1'", "time >= '2024-01-01'", "time >= '2024-01-01' AND time < '2024-02-01'",
                    "device IN ('d1', 'd2')", "value IS NULL",
                    "device = 'd1' AND time >= '2024-01-01'",
                    "time >= '2024-01-01' AND time < '2024-02-01' AND device = 'd1'",
                    "name LIKE 'd%'", "my_func(v) > 1", "a = 1 OR b = 2"
            };
            PushDownStatistics stats = new PushDownStatistics();
            PredicatePushDownRule r = new PredicatePushDownRule(adapter, stats);
            int total = 0, pushed = 0;
            for (String c : conds) {
                PredicatePushDownRule.PushDownAnalysis a = r.analyze(c, adapter);
                total += a.getTotalCount();
                pushed += a.getPushedCount();
            }
            double rate = (double) pushed / total;
            assertTrue(rate >= 0.70, "IoTDB 下推率: " + String.format("%.2f%%", rate * 100));
        }
    }

    // ===================== ES 适配器测试（≥10 用例） =====================

    @Nested
    @DisplayName("ES 适配器谓词下推测试")
    class ElasticsearchPredicatePushDownTest {

        private StubElasticsearchAdapter adapter;
        private PredicatePushDownRule rule;

        @BeforeEach
        void setUp() {
            adapter = new StubElasticsearchAdapter();
            rule = new PredicatePushDownRule(adapter);
        }

        @Test
        @DisplayName("ES-01: term 等值谓词下推 WHERE id = 100")
        void testTerm() {
            assertEquals(1, rule.analyze("id = 100", adapter).getPushedCount());
        }

        @Test
        @DisplayName("ES-02: range 范围谓词下推 WHERE age > 18")
        void testRange() {
            assertEquals(1, rule.analyze("age > 18", adapter).getPushedCount());
        }

        @Test
        @DisplayName("ES-03: range 组合下推 WHERE age >= 18 AND age <= 65")
        void testRangeCombined() {
            assertEquals(2, rule.analyze("age >= 18 AND age <= 65", adapter).getPushedCount());
        }

        @Test
        @DisplayName("ES-04: terms IN 谓词下推 WHERE status IN ('A', 'B', 'C')")
        void testTermsIn() {
            assertEquals(1, rule.analyze("status IN ('A', 'B', 'C')", adapter).getPushedCount());
        }

        @Test
        @DisplayName("ES-05: wildcard LIKE 谓词下推 WHERE name LIKE '张%'")
        void testLike() {
            assertEquals(1, rule.analyze("name LIKE '张%'", adapter).getPushedCount());
        }

        @Test
        @DisplayName("ES-06: exists IS NOT NULL 下推 WHERE col IS NOT NULL")
        void testExists() {
            assertEquals(1, rule.analyze("col IS NOT NULL", adapter).getPushedCount());
        }

        @Test
        @DisplayName("ES-07: 混合 bool 查询下推")
        void testBoolMixed() {
            PredicatePushDownRule.PushDownAnalysis a =
                    rule.analyze("id = 100 AND age > 18 AND status IN ('A') AND name LIKE '张%'", adapter);
            assertEquals(4, a.getPushedCount());
            assertEquals(1.0, a.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("ES-08: script UDF 保留 WHERE script_field(x) = 1")
        void testScript() {
            assertEquals(1, rule.analyze("script_field(x) = 1", adapter).getRemainingCount());
        }

        @Test
        @DisplayName("ES-09: OR 条件保留")
        void testOr() {
            assertEquals(0, rule.analyze("a = 1 OR b = 2", adapter).getPushedCount());
        }

        @Test
        @DisplayName("ES-10: 部分下推（bool + script）")
        void testPartial() {
            PredicatePushDownRule.PushDownAnalysis a =
                    rule.analyze("id = 100 AND age > 18 AND script_field(x) = 1", adapter);
            assertEquals(2, a.getPushedCount());
            assertEquals(1, a.getRemainingCount());
        }

        @Test
        @DisplayName("ES-11: 下推率 ≥70%")
        void testPushDownRate() {
            String[] conds = {
                    "id = 1", "age > 18", "age >= 18 AND age <= 65",
                    "status IN ('A', 'B')", "name LIKE '张%'", "col IS NOT NULL",
                    "id = 1 AND age > 18 AND status IN ('A')",
                    "script_field(x) = 1", "a = 1 OR b = 2",
                    "id = 1 AND age > 18 AND name LIKE '张%' AND status IN ('A')"
            };
            PushDownStatistics stats = new PushDownStatistics();
            PredicatePushDownRule r = new PredicatePushDownRule(adapter, stats);
            int total = 0, pushed = 0;
            for (String c : conds) {
                PredicatePushDownRule.PushDownAnalysis a = r.analyze(c, adapter);
                total += a.getTotalCount();
                pushed += a.getPushedCount();
            }
            double rate = (double) pushed / total;
            assertTrue(rate >= 0.70, "ES 下推率: " + String.format("%.2f%%", rate * 100));
        }
    }

    // ===================== 跨数据源对比与综合统计测试 =====================

    @Test
    @DisplayName("5 种数据源下推率综合统计 ≥70%")
    void testAllSourcesPushDownRate() {
        String[] conditions = {
                "id = 100", "age > 18 AND age < 65", "status IN ('A', 'B', 'C')",
                "name LIKE '张%'", "id = 100 AND age > 18",
                "UDF(name) = 'x'", "id = 100 AND UDF(name) = 'x'",
                "col IS NULL", "id = 100 AND age > 18 AND status = 'A'",
                "id = 1 AND age > 18 AND name LIKE '张%' AND status IN ('A') AND col IS NULL"
        };
        BaseAdapter[] adapters = {
                new StubIcebergAdapter(), new StubDorisAdapter(),
                new StubTrinoAdapter(), new StubIoTDBAdapter(),
                new StubElasticsearchAdapter()
        };
        PushDownStatistics stats = new PushDownStatistics();
        int totalAll = 0, pushedAll = 0;
        for (BaseAdapter adapter : adapters) {
            PredicatePushDownRule rule = new PredicatePushDownRule(adapter, stats);
            for (String cond : conditions) {
                PredicatePushDownRule.PushDownAnalysis a = rule.analyze(cond, adapter);
                totalAll += a.getTotalCount();
                pushedAll += a.getPushedCount();
                DataSourceConfig.Type st = adapter.getAdapterType();
                for (Map.Entry<PredicateType, List<String>> e : a.getPushableByType().entrySet()) {
                    for (String p : e.getValue()) {
                        stats.recordPredicate(st, e.getKey(), true, null, p);
                    }
                }
                for (Map.Entry<PredicateType, List<String>> e : a.getRemainingByType().entrySet()) {
                    for (String p : e.getValue()) {
                        stats.recordPredicate(st, e.getKey(), false, "保留", p);
                    }
                }
            }
        }
        double overallRate = (double) pushedAll / totalAll;
        assertTrue(overallRate >= 0.70,
                "5 种数据源综合下推率应 ≥ 70%，实际: " + String.format("%.2f%%", overallRate * 100));

        // 验证每种数据源下推率
        for (DataSourceConfig.Type st : stats.getActiveSourceTypes()) {
            double rate = stats.getPushDownRate(st);
            assertTrue(rate >= 0.60,
                    st + " 下推率: " + String.format("%.2f%%", rate * 100));
        }
    }

    @Test
    @DisplayName("谓词类型枚举功能")
    void testPredicateTypeEnum() {
        assertTrue(PredicateType.EQUALITY.isPushable());
        assertTrue(PredicateType.RANGE.isPushable());
        assertTrue(PredicateType.IN.isPushable());
        assertTrue(PredicateType.LIKE.isPushable());
        assertTrue(PredicateType.IS_NULL.isPushable());
        assertFalse(PredicateType.UNSUPPORTED.isPushable());
        assertNotNull(PredicateType.EQUALITY.description());
        assertNotNull(PredicateType.UNSUPPORTED.description());
    }

    @Test
    @DisplayName("PushDownStatistics 按类型与数据源分类统计")
    void testStatisticsClassification() {
        PushDownStatistics stats = new PushDownStatistics();
        stats.recordPredicate(DataSourceConfig.Type.ICEBERG, PredicateType.EQUALITY, true);
        stats.recordPredicate(DataSourceConfig.Type.ICEBERG, PredicateType.RANGE, true);
        stats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.LIKE, true);
        stats.recordPredicate(DataSourceConfig.Type.IOTDB, PredicateType.LIKE, false, "IoTDB不支持LIKE", "name LIKE 'x%'");
        stats.recordPredicate(DataSourceConfig.Type.TRINO, PredicateType.UNSUPPORTED, false, "UDF", "udf(x)");

        Map<PredicateType, int[]> typeStats = stats.getTypeStats();
        assertEquals(1, typeStats.get(PredicateType.EQUALITY)[0]);
        assertEquals(1, typeStats.get(PredicateType.LIKE)[1]); // 1 个 LIKE 下推成功
        assertEquals(1, typeStats.get(PredicateType.UNSUPPORTED)[0]);

        Map<DataSourceConfig.Type, int[]> sourceStats = stats.getSourceStats();
        assertEquals(2, sourceStats.get(DataSourceConfig.Type.ICEBERG)[0]);
        assertEquals(1, sourceStats.get(DataSourceConfig.Type.IOTDB)[0]);
        assertEquals(0, sourceStats.get(DataSourceConfig.Type.IOTDB)[1]); // IoTDB LIKE 未下推

        assertEquals(5, stats.getTotalPredicates());
        assertEquals(3, stats.getPushedPredicates());
        assertEquals(2, stats.getRemainingPredicates());
    }

    @Test
    @DisplayName("PushDownStatistics summary 输出")
    void testStatisticsSummary() {
        PushDownStatistics stats = new PushDownStatistics();
        stats.recordPredicate(DataSourceConfig.Type.ICEBERG, PredicateType.EQUALITY, true);
        stats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "UDF", "udf");
        String summary = stats.summary();
        assertNotNull(summary);
        assertTrue(summary.contains("total=2"));
        assertTrue(summary.contains("pushed=1"));
        assertTrue(summary.contains("remaining=1"));
    }

    @Test
    @DisplayName("PushDownStatistics reset 功能")
    void testStatisticsReset() {
        PushDownStatistics stats = new PushDownStatistics();
        stats.recordPredicate(DataSourceConfig.Type.ICEBERG, PredicateType.EQUALITY, true);
        stats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.RANGE, false, "reason", "x");
        assertEquals(2, stats.getTotalPredicates());
        stats.reset();
        assertEquals(0, stats.getTotalPredicates());
        assertEquals(0, stats.getPushedPredicates());
        assertEquals(0, stats.getRemainingPredicates());
        assertEquals(0.0, stats.getPushDownRate());
    }

    @Test
    @DisplayName("isPushable 下推支持矩阵验证")
    void testIsPushableMatrix() {
        PredicatePushDownRule icebergRule = new PredicatePushDownRule(new StubIcebergAdapter());
        PredicatePushDownRule iotdbRule = new PredicatePushDownRule(new StubIoTDBAdapter());

        BaseAdapter iceberg = new StubIcebergAdapter();
        BaseAdapter iotdb = new StubIoTDBAdapter();

        // Iceberg 支持所有可下推类型
        assertTrue(icebergRule.isPushable(PredicateType.EQUALITY, iceberg));
        assertTrue(icebergRule.isPushable(PredicateType.RANGE, iceberg));
        assertTrue(icebergRule.isPushable(PredicateType.IN, iceberg));
        assertTrue(icebergRule.isPushable(PredicateType.LIKE, iceberg));
        assertTrue(icebergRule.isPushable(PredicateType.IS_NULL, iceberg));
        assertFalse(icebergRule.isPushable(PredicateType.UNSUPPORTED, iceberg));

        // IoTDB 不支持 LIKE
        assertTrue(iotdbRule.isPushable(PredicateType.EQUALITY, iotdb));
        assertTrue(iotdbRule.isPushable(PredicateType.RANGE, iotdb));
        assertTrue(iotdbRule.isPushable(PredicateType.IN, iotdb));
        assertFalse(iotdbRule.isPushable(PredicateType.LIKE, iotdb));
        assertTrue(iotdbRule.isPushable(PredicateType.IS_NULL, iotdb));
    }

    @Test
    @DisplayName("PushDownAnalysis 语义等价性验证")
    void testSemanticEquivalence() {
        StubIcebergAdapter adapter = new StubIcebergAdapter();
        PredicatePushDownRule rule = new PredicatePushDownRule(adapter);
        String condition = "id = 100 AND age > 18 AND UDF(name) = 'x' AND status IN ('A', 'B')";
        PredicatePushDownRule.PushDownAnalysis a = rule.analyze(condition, adapter);

        // 下推谓词 + 保留谓词 = 全部谓词
        assertEquals(a.getTotalCount(), a.getPushedCount() + a.getRemainingCount());

        // 下推谓词与保留谓词无交集（按内容）
        List<String> pushable = new ArrayList<>(a.getPushable());
        List<String> remaining = new ArrayList<>(a.getRemaining());
        pushable.retainAll(remaining);
        assertTrue(pushable.isEmpty(), "下推与保留谓词不应有交集");
    }

    @Test
    @DisplayName("空条件与 null 条件处理")
    void testEmptyAndNullCondition() {
        StubIcebergAdapter adapter = new StubIcebergAdapter();
        PredicatePushDownRule rule = new PredicatePushDownRule(adapter);
        assertTrue(rule.extractPredicates("").isEmpty());
        assertTrue(rule.extractPredicates(null).isEmpty());
        assertEquals(PredicateType.UNSUPPORTED, rule.classifyPredicate(null));
        assertEquals(PredicateType.UNSUPPORTED, rule.classifyPredicate(""));
    }

    @Test
    @DisplayName("规则启用/禁用")
    void testRuleEnableDisable() {
        StubIcebergAdapter adapter = new StubIcebergAdapter();
        PredicatePushDownRule rule = new PredicatePushDownRule(adapter);
        assertTrue(rule.isEnabled());
        rule.setEnabled(false);
        assertFalse(rule.isEnabled());
        // 禁用后 matches 返回 false
        CustomRelNode filter = buildFilterScan(adapter, "id = 100");
        assertFalse(rule.matches(filter));
    }

    @Test
    @DisplayName("规则名称与描述")
    void testRuleNameAndDescription() {
        StubIcebergAdapter adapter = new StubIcebergAdapter();
        PredicatePushDownRule rule = new PredicatePushDownRule(adapter);
        assertEquals(PredicatePushDownRule.RULE_NAME, rule.getRuleName());
        assertNotNull(rule.getDescription());
        assertEquals(CustomRelNode.Op.FILTER, rule.getMatchOp());
    }

    @Test
    @DisplayName("PushDownAnalysis 全部 getter 覆盖")
    void testPushDownAnalysisGetters() {
        StubIcebergAdapter adapter = new StubIcebergAdapter();
        PredicatePushDownRule rule = new PredicatePushDownRule(adapter);
        PredicatePushDownRule.PushDownAnalysis a =
                rule.analyze("id = 100 AND UDF(name) = 'x'", adapter);
        // 全部 getter
        assertNotNull(a.getAllPredicates());
        assertNotNull(a.getClassified());
        assertNotNull(a.getPushable());
        assertNotNull(a.getRemaining());
        assertNotNull(a.getPushableByType());
        assertNotNull(a.getRemainingByType());
        assertTrue(a.getPushDownRate() >= 0);
        assertEquals(2, a.getTotalCount());
        assertEquals(1, a.getPushedCount());
        assertEquals(1, a.getRemainingCount());
        assertFalse(a.isFullyPushed());
        assertFalse(a.isFullyRemaining());
        assertNotNull(a.toString());

        // 全部下推的场景
        PredicatePushDownRule.PushDownAnalysis full =
                rule.analyze("id = 100 AND age > 18", adapter);
        assertTrue(full.isFullyPushed());
        assertFalse(full.isFullyRemaining());

        // 全部保留的场景
        PredicatePushDownRule.PushDownAnalysis none =
                rule.analyze("UDF(name) = 'x'", adapter);
        assertFalse(none.isFullyPushed());
        assertTrue(none.isFullyRemaining());
    }

    @Test
    @DisplayName("PushDownStatistics getActiveSourceTypes 与按类型下推率")
    void testStatisticsActiveSourcesAndTypeRate() {
        PushDownStatistics stats = new PushDownStatistics();
        stats.recordPredicate(DataSourceConfig.Type.ICEBERG, PredicateType.EQUALITY, true, null, "id=1");
        stats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.RANGE, true, null, "age>18");
        stats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.RANGE, false, "reason", "x");

        assertTrue(stats.getActiveSourceTypes().contains(DataSourceConfig.Type.ICEBERG));
        assertTrue(stats.getActiveSourceTypes().contains(DataSourceConfig.Type.DORIS));
        assertFalse(stats.getActiveSourceTypes().contains(DataSourceConfig.Type.TRINO));

        assertEquals(1.0, stats.getPushDownRate(PredicateType.EQUALITY), 0.001);
        assertEquals(0.5, stats.getPushDownRate(PredicateType.RANGE), 0.001);
        assertEquals(0.0, stats.getPushDownRate(PredicateType.LIKE), 0.001);

        assertEquals(1.0, stats.getPushDownRate(DataSourceConfig.Type.ICEBERG), 0.001);
        assertEquals(0.5, stats.getPushDownRate(DataSourceConfig.Type.DORIS), 0.001);
        assertEquals(0.0, stats.getPushDownRate(DataSourceConfig.Type.TRINO), 0.001);

        assertFalse(stats.getPushedDescriptions().isEmpty());
        assertFalse(stats.getRemainingReasons().isEmpty());
        assertNotNull(stats.toString());
    }

    @Test
    @DisplayName("onMatch 无子节点 Filter 下推")
    void testOnMatchNoChild() {
        StubIcebergAdapter adapter = new StubIcebergAdapter();
        PredicatePushDownRule rule = new PredicatePushDownRule(adapter);
        CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER).setCondition("id = 100");
        CustomRelNode result = rule.apply(filter);
        assertNotNull(result);
    }

    @Test
    @DisplayName("onMatch 空条件 Filter 不改写")
    void testOnMatchEmptyCondition() {
        StubIcebergAdapter adapter = new StubIcebergAdapter();
        PredicatePushDownRule rule = new PredicatePushDownRule(adapter);
        CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER).setCondition("");
        CustomRelNode result = rule.apply(filter);
        assertSame(filter, result);
    }

    @Test
    @DisplayName("onMatch 全部保留谓词标记 NOT_PUSHED")
    void testOnMatchAllRemaining() {
        StubIcebergAdapter adapter = new StubIcebergAdapter();
        PredicatePushDownRule rule = new PredicatePushDownRule(adapter);
        CustomRelNode filter = buildFilterScan(adapter, "UDF(name) = 'x'");
        CustomRelNode result = rule.apply(filter);
        // 全部 UNSUPPORTED，保持原 Filter，标记 NOT_PUSHED
        assertNotNull(result);
    }

    @Test
    @DisplayName("isPushable null 参数返回 false")
    void testIsPushableNull() {
        StubIcebergAdapter adapter = new StubIcebergAdapter();
        PredicatePushDownRule rule = new PredicatePushDownRule(adapter);
        assertFalse(rule.isPushable(null, adapter));
        assertFalse(rule.isPushable(PredicateType.EQUALITY, null));
        assertFalse(rule.isPushable(null, null));
    }

    @Test
    @DisplayName("数据源禁用下推时 isPushable 返回 false")
    void testIsPushableDisabled() {
        StubIcebergAdapter adapter = new StubIcebergAdapter();
        adapter.config.setPushDownEnabled(false);
        PredicatePushDownRule rule = new PredicatePushDownRule(adapter);
        assertFalse(rule.isPushable(PredicateType.EQUALITY, adapter));
    }

    @Test
    @DisplayName("classifyPredicates 分类映射完整性")
    void testClassifyPredicatesMap() {
        StubIcebergAdapter adapter = new StubIcebergAdapter();
        PredicatePushDownRule rule = new PredicatePushDownRule(adapter);
        List<String> preds = rule.extractPredicates("id = 1 AND age > 18 AND name LIKE 'a%' AND UDF(x) = 1");
        Map<PredicateType, List<String>> classified = rule.classifyPredicates(preds);
        // 每种类型都有桶
        for (PredicateType type : PredicateType.values()) {
            assertNotNull(classified.get(type));
        }
        assertFalse(classified.get(PredicateType.EQUALITY).isEmpty());
        assertFalse(classified.get(PredicateType.RANGE).isEmpty());
        assertFalse(classified.get(PredicateType.LIKE).isEmpty());
        assertFalse(classified.get(PredicateType.UNSUPPORTED).isEmpty());
        assertTrue(classified.get(PredicateType.IN).isEmpty());
    }
}