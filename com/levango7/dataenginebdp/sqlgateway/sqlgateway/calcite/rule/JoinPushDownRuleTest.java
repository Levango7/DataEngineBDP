package com.shuqing.bigdata.sqlgateway.calcite.rule;

import com.shuqing.bigdata.sqlgateway.calcite.adapter.BaseAdapter;
import com.shuqing.bigdata.sqlgateway.calcite.adapter.DorisAdapter;
import com.shuqing.bigdata.sqlgateway.calcite.adapter.ElasticsearchAdapter;
import com.shuqing.bigdata.sqlgateway.calcite.adapter.IcebergAdapter;
import com.shuqing.bigdata.sqlgateway.calcite.adapter.IoTDBAdapter;
import com.shuqing.bigdata.sqlgateway.calcite.adapter.TrinoAdapter;
import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;
import com.shuqing.bigdata.sqlgateway.calcite.rel.CustomRelNode;
import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JoinPushDownRule} Join 下推规则单元测试。
 *
 * <p>测试覆盖 5 种数据源（Iceberg/Doris/Trino/IoTDB/ES）各 ≥ 5 个用例，共 ≥ 25 个用例，
 * 验证：</p>
 * <ul>
 *   <li>同源 Join 自动下推到数据源执行</li>
 *   <li>跨源 Join 识别后保留在联邦层</li>
 *   <li>BroadcastJoin 策略：小表（&lt;100MB）自动 Broadcast</li>
 *   <li>Join 重排序基于 Cost 估算选择最优顺序</li>
 *   <li>下推率 ≥ 70%</li>
 *   <li>查询语义等价</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class JoinPushDownRuleTest {

    // ===================== 公共桩适配器 =====================

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
            return new PushDownResult("SELECT * FROM iceberg_t JOIN iceberg_t2 ON ...",
                    r, new ArrayList<>(), true, null);
        }
        @Override public Cost costEstimate(CustomRelNode r) { return new Cost(8, 80, 10, 2000); }
        @Override public SqlDialect getDialect() { return SqlDialect.HIVE; }
        @Override public List<String> prunePartitions(String t, String f) { return Arrays.asList("p1", "p2"); }
        @Override public long selectSnapshot(String t, Long s, Long ts) { return s != null ? s : 1L; }
        @Override public boolean isPartitionColumn(String t, String c) { return "dt".equals(c); }
        @Override public int getSchemaVersion(String t) { return 1; }
    }

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
            return new PushDownResult("SELECT * FROM doris_t JOIN doris_t2 ON ...",
                    r, new ArrayList<>(), true, null);
        }
        @Override public Cost costEstimate(CustomRelNode r) { return new Cost(10, 100, 5, 1000); }
        @Override public SqlDialect getDialect() { return SqlDialect.DORIS; }
        @Override public String routeMaterializedView(String t, List<String> g, List<String> a) { return t + "_mv"; }
        @Override public boolean canColocateJoin(String l, String r) { return true; }
        @Override public int getTabletCount(String t) { return 64; }
        @Override public long getEstimatedRowCount(String t) { return 1_000_000L; }
    }

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
            return new PushDownResult("SELECT * FROM trino_t JOIN trino_t2 ON ...",
                    r, new ArrayList<>(), true, null);
        }
        @Override public Cost costEstimate(CustomRelNode r) { return new Cost(5, 50, 20, 500); }
        @Override public SqlDialect getDialect() { return SqlDialect.TRINO; }
        @Override public String getConnectorName(String t) { return "hive"; }
        @Override public boolean supportsDynamicFiltering(String c) { return true; }
        @Override public String inlineCte(String s) { return s; }
        @Override public int getWorkerCount() { return 10; }
    }

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
            return new PushDownResult("SELECT * FROM root.sg.d1 JOIN root.sg.d2 ON ...",
                    r, new ArrayList<>(), true, null);
        }
        @Override public Cost costEstimate(CustomRelNode r) { return new Cost(3, 30, 2, 5000); }
        @Override public SqlDialect getDialect() { return SqlDialect.ANSI; }
        @Override public String pushDownTimeRange(String f) { return "2024-01-01,2024-02-01"; }
        @Override public String pushDownDownsampling(String a, String t, String i) { return a + "(" + t + ")"; }
        @Override public String toQueryPath(String f) { return "root.sg.d1.*"; }
        @Override public boolean supportsDownsampling(String a) { return "mean".equals(a); }
    }

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
            return new PushDownResult("GET /_search JOIN ...",
                    r, new ArrayList<>(), true, null);
        }
        @Override public Cost costEstimate(CustomRelNode r) { return new Cost(2, 20, 1, 10000); }
        @Override public SqlDialect getDialect() { return SqlDialect.ANSI; }
        @Override public String toQueryDsl(String p) { return "{\"query\":{}}"; }
        @Override public String toAggregationDsl(List<String> g, List<String> a) { return "{\"aggs\":{}}"; }
        @Override public String toSortDsl(List<String> s) { return "{\"sort\":[]}"; }
        @Override public String toPaginationDsl(long l, long o) { return "{\"from\":0,\"size\":100}"; }
        @Override public boolean isIndexAvailable(String i) { return "es_orders".equals(i); }
    }

    // ===================== 公共桩统计信息 =====================

    /** 表统计信息桩：可配置每表的行数与平均行大小 */
    static class StubTableStatistics implements BroadcastJoinStrategy.TableStatistics {
        final Map<String, Long> rowCounts = new HashMap<>();
        final Map<String, Long> avgRowSizes = new HashMap<>();

        StubTableStatistics set(String table, long rows, long avgRowSize) {
            rowCounts.put(table, rows);
            avgRowSizes.put(table, avgRowSize);
            return this;
        }

        @Override public long getRowCount(String tableName) {
            return rowCounts.getOrDefault(tableName, -1L);
        }

        @Override public long getAvgRowSize(String tableName) {
            return avgRowSizes.getOrDefault(tableName, -1L);
        }
    }

    /** Join 统计信息桩：可配置每表的行数与列 NDV */
    static class StubJoinStatistics implements JoinReorderOptimizer.JoinStatistics {
        final Map<String, Long> rowCounts = new HashMap<>();
        final Map<String, Map<String, Long>> ndvs = new HashMap<>();

        StubJoinStatistics setRows(String table, long rows) {
            rowCounts.put(table, rows);
            return this;
        }

        StubJoinStatistics setNdv(String table, String column, long ndv) {
            ndvs.computeIfAbsent(table, k -> new HashMap<>()).put(column, ndv);
            return this;
        }

        @Override public long getRowCount(String tableName) {
            return rowCounts.getOrDefault(tableName, -1L);
        }

        @Override public long getNdv(String tableName, String column) {
            Map<String, Long> cols = ndvs.get(tableName);
            if (cols == null) {
                return -1;
            }
            return cols.getOrDefault(column, -1L);
        }
    }

    // ===================== 公共辅助方法 =====================

    /** 构造 TableScan 节点 */
    private CustomRelNode scan(BaseAdapter adapter, String tableName) {
        return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName(tableName)
                .setSourceName(adapter.getDataSourceConfig().getName());
    }

    /** 构造同源 Join：两个 TableScan 来自同一适配器 */
    private CustomRelNode sameSourceJoin(BaseAdapter adapter, String leftTable, String rightTable,
                                         String condition) {
        CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN).setCondition(condition);
        join.addChild(scan(adapter, leftTable));
        join.addChild(scan(adapter, rightTable));
        return join;
    }

    /** 构造跨源 Join：两个 TableScan 来自不同适配器 */
    private CustomRelNode crossSourceJoin(BaseAdapter leftAdapter, String leftTable,
                                          BaseAdapter rightAdapter, String rightTable,
                                          String condition) {
        CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN).setCondition(condition);
        join.addChild(scan(leftAdapter, leftTable));
        join.addChild(scan(rightAdapter, rightTable));
        return join;
    }

    /** 构造 JoinPushDownRule 实例 */
    private JoinPushDownRule buildRule(List<BaseAdapter> adapters,
                                       StubTableStatistics tableStats,
                                       StubJoinStatistics joinStats) {
        CrossSourceJoinDetector detector = new CrossSourceJoinDetector(adapters);
        BroadcastJoinStrategy broadcastStrategy = new BroadcastJoinStrategy(tableStats);
        JoinReorderOptimizer reorderOptimizer = new JoinReorderOptimizer(joinStats);
        return new JoinPushDownRule(adapters.get(0), detector, broadcastStrategy, reorderOptimizer);
    }

    /** 断言下推率 ≥ 阈值 */
    private void assertPushDownRate(JoinPushDownRule.JoinPushDownStatistics stats,
                                    double threshold, String msg) {
        double rate = stats.getPushDownRate();
        assertTrue(rate >= threshold,
                msg + " — 下推率 " + String.format("%.2f%%", rate * 100)
                        + " < 阈值 " + String.format("%.2f%%", threshold * 100));
    }

    // ===================== Iceberg 适配器测试（5 用例） =====================

    @Nested
    @DisplayName("Iceberg 适配器 Join 下推")
    class IcebergJoinPushDownTest {

        private StubIcebergAdapter adapter = new StubIcebergAdapter();
        private List<BaseAdapter> adapters = List.of(adapter);
        private StubTableStatistics tableStats = new StubTableStatistics();
        private StubJoinStatistics joinStats = new StubJoinStatistics();

        @Test
        @DisplayName("Iceberg-1: 同源 Join 自动下推到 Iceberg 数据源")
        void testSameSourceJoinPushDown() {
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);
            CustomRelNode join = sameSourceJoin(adapter, "iceberg.orders", "iceberg.items",
                    "orders.item_id = items.id");

            CustomRelNode result = rule.apply(join);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            assertTrue(result.getPushedOperations().stream().anyMatch(o -> o.contains("join")));
            assertEquals(1, rule.getStatistics().getSameSourceJoins());
            assertEquals(1, rule.getStatistics().getSameSourcePushed());
        }

        @Test
        @DisplayName("Iceberg-2: 跨源 Join（Iceberg ⋈ Doris）保留联邦层")
        void testCrossSourceJoinKeepFederated() {
            StubDorisAdapter dorisAdapter = new StubDorisAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, dorisAdapter);
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "iceberg.fact",
                    dorisAdapter, "doris.dim", "fact.dim_id = dim.id");

            CustomRelNode result = rule.apply(join);

            assertEquals(CustomRelNode.PushDownStatus.NOT_APPLICABLE, result.getPushDownStatus());
            assertEquals(1, rule.getStatistics().getCrossSourceJoins());
            assertEquals(0, rule.getStatistics().getSameSourceJoins());
        }

        @Test
        @DisplayName("Iceberg-3: 小表 Broadcast — dim 表 50MB < 100MB 阈值")
        void testSmallTableBroadcast() {
            StubDorisAdapter dorisAdapter = new StubDorisAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, dorisAdapter);
            // dim 表 50MB（50万行 × 100字节）
            tableStats.set("iceberg.fact", 10_000_000L, 200L);   // 2GB
            tableStats.set("doris.dim", 500_000L, 100L);          // 50MB
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "iceberg.fact",
                    dorisAdapter, "doris.dim", "fact.dim_id = dim.id");

            CustomRelNode result = rule.apply(join);

            assertEquals(CustomRelNode.PushDownStatus.NOT_APPLICABLE, result.getPushDownStatus());
            assertTrue(rule.getStatistics().getBroadcastCount() >= 1,
                    "小表应触发 BROADCAST 策略");
        }

        @Test
        @DisplayName("Iceberg-4: 大表 Shuffle — 两表均 > 100MB")
        void testLargeTableShuffle() {
            StubDorisAdapter dorisAdapter = new StubDorisAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, dorisAdapter);
            // 两表均 1GB
            tableStats.set("iceberg.fact1", 10_000_000L, 100L);
            tableStats.set("doris.fact2", 10_000_000L, 100L);
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "iceberg.fact1",
                    dorisAdapter, "doris.fact2", "fact1.k = fact2.k");

            CustomRelNode result = rule.apply(join);

            assertEquals(CustomRelNode.PushDownStatus.NOT_APPLICABLE, result.getPushDownStatus());
            assertTrue(rule.getStatistics().getShuffleCount() >= 1,
                    "大表应触发 SHUFFLE 策略");
        }

        @Test
        @DisplayName("Iceberg-5: 下推率验证 — 5 个同源 Join 全部下推")
        void testPushDownRate() {
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);
            for (int i = 1; i <= 5; i++) {
                CustomRelNode join = sameSourceJoin(adapter,
                        "iceberg.t" + i, "iceberg.t" + (i + 1),
                        "t" + i + ".id = t" + (i + 1) + ".id");
                rule.apply(join);
            }

            assertPushDownRate(rule.getStatistics(), 0.7, "Iceberg-5");
            assertEquals(5, rule.getStatistics().getSameSourcePushed());
        }
    }

    // ===================== Doris 适配器测试（5 用例） =====================

    @Nested
    @DisplayName("Doris 适配器 Join 下推")
    class DorisJoinPushDownTest {

        private StubDorisAdapter adapter = new StubDorisAdapter();
        private List<BaseAdapter> adapters = List.of(adapter);
        private StubTableStatistics tableStats = new StubTableStatistics();
        private StubJoinStatistics joinStats = new StubJoinStatistics();

        @Test
        @DisplayName("Doris-1: 同源 Join 自动下推到 Doris 数据源")
        void testSameSourceJoinPushDown() {
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);
            CustomRelNode join = sameSourceJoin(adapter, "doris.orders", "doris.users",
                    "orders.user_id = users.id");

            CustomRelNode result = rule.apply(join);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            assertEquals(1, rule.getStatistics().getSameSourcePushed());
        }

        @Test
        @DisplayName("Doris-2: 跨源 Join（Doris ⋈ Trino）保留联邦层")
        void testCrossSourceJoinKeepFederated() {
            StubTrinoAdapter trinoAdapter = new StubTrinoAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, trinoAdapter);
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "doris.events",
                    trinoAdapter, "trino.logs", "events.id = logs.event_id");

            CustomRelNode result = rule.apply(join);

            assertEquals(CustomRelNode.PushDownStatus.NOT_APPLICABLE, result.getPushDownStatus());
            assertEquals(1, rule.getStatistics().getCrossSourceJoins());
        }

        @Test
        @DisplayName("Doris-3: 小表 Broadcast — 维度表 20MB")
        void testSmallTableBroadcast() {
            StubTrinoAdapter trinoAdapter = new StubTrinoAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, trinoAdapter);
            tableStats.set("doris.fact", 100_000_000L, 100L);  // 10GB
            tableStats.set("trino.dim", 200_000L, 100L);        // 20MB
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "doris.fact",
                    trinoAdapter, "trino.dim", "fact.dim_id = dim.id");

            rule.apply(join);

            assertTrue(rule.getStatistics().getBroadcastCount() >= 1);
        }

        @Test
        @DisplayName("Doris-4: 极小表 Replicated — 配置表 5MB < 10MB")
        void testTinyTableReplicated() {
            StubTrinoAdapter trinoAdapter = new StubTrinoAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, trinoAdapter);
            tableStats.set("doris.fact", 100_000_000L, 100L);  // 10GB
            tableStats.set("trino.config", 50_000L, 100L);      // 5MB
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "doris.fact",
                    trinoAdapter, "trino.config", "fact.cfg_id = config.id");

            rule.apply(join);

            assertTrue(rule.getStatistics().getReplicatedCount() >= 1,
                    "极小表应触发 REPLICATED 策略");
        }

        @Test
        @DisplayName("Doris-5: 下推率验证 — 5 个同源 Join 全部下推")
        void testPushDownRate() {
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);
            for (int i = 1; i <= 5; i++) {
                CustomRelNode join = sameSourceJoin(adapter,
                        "doris.t" + i, "doris.t" + (i + 1),
                        "t" + i + ".id = t" + (i + 1) + ".id");
                rule.apply(join);
            }

            assertPushDownRate(rule.getStatistics(), 0.7, "Doris-5");
        }
    }

    // ===================== Trino 适配器测试（5 用例） =====================

    @Nested
    @DisplayName("Trino 适配器 Join 下推")
    class TrinoJoinPushDownTest {

        private StubTrinoAdapter adapter = new StubTrinoAdapter();
        private List<BaseAdapter> adapters = List.of(adapter);
        private StubTableStatistics tableStats = new StubTableStatistics();
        private StubJoinStatistics joinStats = new StubJoinStatistics();

        @Test
        @DisplayName("Trino-1: 同源 Join 自动下推到 Trino 数据源")
        void testSameSourceJoinPushDown() {
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);
            CustomRelNode join = sameSourceJoin(adapter, "trino.hive.t1", "trino.hive.t2",
                    "t1.id = t2.id");

            CustomRelNode result = rule.apply(join);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
        }

        @Test
        @DisplayName("Trino-2: 跨源 Join（Trino ⋈ Iceberg）保留联邦层")
        void testCrossSourceJoinKeepFederated() {
            StubIcebergAdapter icebergAdapter = new StubIcebergAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, icebergAdapter);
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "trino.hive.t1",
                    icebergAdapter, "iceberg.t2", "t1.id = t2.id");

            CustomRelNode result = rule.apply(join);

            assertEquals(CustomRelNode.PushDownStatus.NOT_APPLICABLE, result.getPushDownStatus());
        }

        @Test
        @DisplayName("Trino-3: 小表 Broadcast — 80MB < 100MB")
        void testSmallTableBroadcast() {
            StubIcebergAdapter icebergAdapter = new StubIcebergAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, icebergAdapter);
            tableStats.set("trino.hive.fact", 50_000_000L, 200L);  // 10GB
            tableStats.set("iceberg.dim", 800_000L, 100L);          // 80MB
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "trino.hive.fact",
                    icebergAdapter, "iceberg.dim", "fact.dim_id = dim.id");

            rule.apply(join);

            assertTrue(rule.getStatistics().getBroadcastCount() >= 1);
        }

        @Test
        @DisplayName("Trino-4: 大表 Shuffle — 两表均 500MB")
        void testLargeTableShuffle() {
            StubIcebergAdapter icebergAdapter = new StubIcebergAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, icebergAdapter);
            tableStats.set("trino.hive.big1", 5_000_000L, 100L);
            tableStats.set("iceberg.big2", 5_000_000L, 100L);
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "trino.hive.big1",
                    icebergAdapter, "iceberg.big2", "big1.k = big2.k");

            rule.apply(join);

            assertTrue(rule.getStatistics().getShuffleCount() >= 1);
        }

        @Test
        @DisplayName("Trino-5: 下推率验证 — 5 个同源 Join 全部下推")
        void testPushDownRate() {
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);
            for (int i = 1; i <= 5; i++) {
                CustomRelNode join = sameSourceJoin(adapter,
                        "trino.t" + i, "trino.t" + (i + 1),
                        "t" + i + ".id = t" + (i + 1) + ".id");
                rule.apply(join);
            }

            assertPushDownRate(rule.getStatistics(), 0.7, "Trino-5");
        }
    }

    // ===================== IoTDB 适配器测试（5 用例） =====================

    @Nested
    @DisplayName("IoTDB 适配器 Join 下推")
    class IoTDBJoinPushDownTest {

        private StubIoTDBAdapter adapter = new StubIoTDBAdapter();
        private List<BaseAdapter> adapters = List.of(adapter);
        private StubTableStatistics tableStats = new StubTableStatistics();
        private StubJoinStatistics joinStats = new StubJoinStatistics();

        @Test
        @DisplayName("IoTDB-1: 同源 Join 自动下推到 IoTDB 数据源")
        void testSameSourceJoinPushDown() {
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);
            CustomRelNode join = sameSourceJoin(adapter, "root.sg.d1", "root.sg.d2",
                    "d1.time = d2.time");

            CustomRelNode result = rule.apply(join);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
        }

        @Test
        @DisplayName("IoTDB-2: 跨源 Join（IoTDB ⋈ ES）保留联邦层")
        void testCrossSourceJoinKeepFederated() {
            StubElasticsearchAdapter esAdapter = new StubElasticsearchAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, esAdapter);
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "root.sg.d1",
                    esAdapter, "es_metrics", "d1.time = es_metrics.timestamp");

            CustomRelNode result = rule.apply(join);

            assertEquals(CustomRelNode.PushDownStatus.NOT_APPLICABLE, result.getPushDownStatus());
        }

        @Test
        @DisplayName("IoTDB-3: 小表 Broadcast — ES 索引 30MB")
        void testSmallTableBroadcast() {
            StubElasticsearchAdapter esAdapter = new StubElasticsearchAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, esAdapter);
            tableStats.set("root.sg.d1", 100_000_000L, 50L);   // 5GB
            tableStats.set("es_dim", 300_000L, 100L);           // 30MB
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "root.sg.d1",
                    esAdapter, "es_dim", "d1.dim_id = es_dim.id");

            rule.apply(join);

            assertTrue(rule.getStatistics().getBroadcastCount() >= 1);
        }

        @Test
        @DisplayName("IoTDB-4: 大表 Shuffle — 两时序表均 200MB")
        void testLargeTableShuffle() {
            StubElasticsearchAdapter esAdapter = new StubElasticsearchAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, esAdapter);
            tableStats.set("root.sg.d1", 2_000_000L, 100L);
            tableStats.set("es_logs", 2_000_000L, 100L);
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "root.sg.d1",
                    esAdapter, "es_logs", "d1.k = es_logs.k");

            rule.apply(join);

            assertTrue(rule.getStatistics().getShuffleCount() >= 1);
        }

        @Test
        @DisplayName("IoTDB-5: 下推率验证 — 5 个同源 Join 全部下推")
        void testPushDownRate() {
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);
            for (int i = 1; i <= 5; i++) {
                CustomRelNode join = sameSourceJoin(adapter,
                        "root.sg.d" + i, "root.sg.d" + (i + 1),
                        "d" + i + ".time = d" + (i + 1) + ".time");
                rule.apply(join);
            }

            assertPushDownRate(rule.getStatistics(), 0.7, "IoTDB-5");
        }
    }

    // ===================== ES 适配器测试（5 用例） =====================

    @Nested
    @DisplayName("ES 适配器 Join 下推")
    class ElasticsearchJoinPushDownTest {

        private StubElasticsearchAdapter adapter = new StubElasticsearchAdapter();
        private List<BaseAdapter> adapters = List.of(adapter);
        private StubTableStatistics tableStats = new StubTableStatistics();
        private StubJoinStatistics joinStats = new StubJoinStatistics();

        @Test
        @DisplayName("ES-1: 同源 Join 自动下推到 ES 数据源")
        void testSameSourceJoinPushDown() {
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);
            CustomRelNode join = sameSourceJoin(adapter, "es_orders", "es_users",
                    "orders.user_id = users.id");

            CustomRelNode result = rule.apply(join);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
        }

        @Test
        @DisplayName("ES-2: 跨源 Join（ES ⋈ Doris）保留联邦层")
        void testCrossSourceJoinKeepFederated() {
            StubDorisAdapter dorisAdapter = new StubDorisAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, dorisAdapter);
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "es_orders",
                    dorisAdapter, "doris.products", "orders.product_id = products.id");

            CustomRelNode result = rule.apply(join);

            assertEquals(CustomRelNode.PushDownStatus.NOT_APPLICABLE, result.getPushDownStatus());
        }

        @Test
        @DisplayName("ES-3: 小表 Broadcast — Doris 维度表 40MB")
        void testSmallTableBroadcast() {
            StubDorisAdapter dorisAdapter = new StubDorisAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, dorisAdapter);
            tableStats.set("es_logs", 100_000_000L, 100L);  // 10GB
            tableStats.set("doris.dim", 400_000L, 100L);     // 40MB
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "es_logs",
                    dorisAdapter, "doris.dim", "logs.dim_id = dim.id");

            rule.apply(join);

            assertTrue(rule.getStatistics().getBroadcastCount() >= 1);
        }

        @Test
        @DisplayName("ES-4: 大表 Shuffle — 两索引均 300MB")
        void testLargeTableShuffle() {
            StubDorisAdapter dorisAdapter = new StubDorisAdapter();
            List<BaseAdapter> allAdapters = List.of(adapter, dorisAdapter);
            tableStats.set("es_index1", 3_000_000L, 100L);
            tableStats.set("doris_table2", 3_000_000L, 100L);
            JoinPushDownRule rule = buildRule(allAdapters, tableStats, joinStats);
            CustomRelNode join = crossSourceJoin(adapter, "es_index1",
                    dorisAdapter, "doris_table2", "index1.k = table2.k");

            rule.apply(join);

            assertTrue(rule.getStatistics().getShuffleCount() >= 1);
        }

        @Test
        @DisplayName("ES-5: 下推率验证 — 5 个同源 Join 全部下推")
        void testPushDownRate() {
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);
            for (int i = 1; i <= 5; i++) {
                CustomRelNode join = sameSourceJoin(adapter,
                        "es_t" + i, "es_t" + (i + 1),
                        "t" + i + ".id = t" + (i + 1) + ".id");
                rule.apply(join);
            }

            assertPushDownRate(rule.getStatistics(), 0.7, "ES-5");
        }
    }

    // ===================== CrossSourceJoinDetector 单元测试 =====================

    @Nested
    @DisplayName("CrossSourceJoinDetector 跨源识别器")
    class CrossSourceDetectorTest {

        @Test
        @DisplayName("Detector-1: 同源 Join 识别为 SAME_SOURCE")
        void testSameSourceDetection() {
            StubIcebergAdapter adapter = new StubIcebergAdapter();
            CrossSourceJoinDetector detector = new CrossSourceJoinDetector(List.of(adapter));
            CustomRelNode join = sameSourceJoin(adapter, "t1", "t2", "t1.id = t2.id");

            CrossSourceJoinDetector.DetectionResult result = detector.detect(join);

            assertEquals(CrossSourceJoinDetector.JoinType.SAME_SOURCE, result.getType());
            assertEquals("iceberg_lake", result.getSource());
            assertTrue(result.isPushable());
        }

        @Test
        @DisplayName("Detector-2: 跨源 Join 识别为 CROSS_SOURCE")
        void testCrossSourceDetection() {
            StubIcebergAdapter a1 = new StubIcebergAdapter();
            StubDorisAdapter a2 = new StubDorisAdapter();
            CrossSourceJoinDetector detector = new CrossSourceJoinDetector(List.of(a1, a2));
            CustomRelNode join = crossSourceJoin(a1, "t1", a2, "t2", "t1.id = t2.id");

            CrossSourceJoinDetector.DetectionResult result = detector.detect(join);

            assertEquals(CrossSourceJoinDetector.JoinType.CROSS_SOURCE, result.getType());
            assertEquals("iceberg_lake", result.getLeftSource());
            assertEquals("doris_olap", result.getRightSource());
            assertFalse(result.isPushable());
        }

        @Test
        @DisplayName("Detector-3: null 节点返回 UNKNOWN")
        void testNullNode() {
            StubIcebergAdapter adapter = new StubIcebergAdapter();
            CrossSourceJoinDetector detector = new CrossSourceJoinDetector(List.of(adapter));

            CrossSourceJoinDetector.DetectionResult result = detector.detect(null);

            assertEquals(CrossSourceJoinDetector.JoinType.UNKNOWN, result.getType());
        }

        @Test
        @DisplayName("Detector-4: 非 JOIN 节点返回 UNKNOWN")
        void testNonJoinNode() {
            StubIcebergAdapter adapter = new StubIcebergAdapter();
            CrossSourceJoinDetector detector = new CrossSourceJoinDetector(List.of(adapter));
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER);

            CrossSourceJoinDetector.DetectionResult result = detector.detect(filter);

            assertEquals(CrossSourceJoinDetector.JoinType.UNKNOWN, result.getType());
        }

        @Test
        @DisplayName("Detector-5: 子节点不足 2 个返回 UNKNOWN")
        void testInsufficientChildren() {
            StubIcebergAdapter adapter = new StubIcebergAdapter();
            CrossSourceJoinDetector detector = new CrossSourceJoinDetector(List.of(adapter));
            CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN);
            join.addChild(scan(adapter, "t1"));  // 只有 1 个子节点

            CrossSourceJoinDetector.DetectionResult result = detector.detect(join);

            assertEquals(CrossSourceJoinDetector.JoinType.UNKNOWN, result.getType());
        }

        @Test
        @DisplayName("Detector-6: isSameSource 工具方法")
        void testIsSameSource() {
            StubIcebergAdapter adapter = new StubIcebergAdapter();
            CrossSourceJoinDetector detector = new CrossSourceJoinDetector(List.of(adapter));
            CustomRelNode s1 = scan(adapter, "t1");
            CustomRelNode s2 = scan(adapter, "t2");

            assertTrue(detector.isSameSource(s1, s2));
        }

        @Test
        @DisplayName("Detector-7: 统计器记录正确")
        void testStatistics() {
            StubIcebergAdapter a1 = new StubIcebergAdapter();
            StubDorisAdapter a2 = new StubDorisAdapter();
            CrossSourceJoinDetector detector = new CrossSourceJoinDetector(List.of(a1, a2));

            detector.detect(sameSourceJoin(a1, "t1", "t2", "t1.id=t2.id"));
            detector.detect(crossSourceJoin(a1, "t1", a2, "t2", "t1.id=t2.id"));

            CrossSourceJoinDetector.DetectorStatistics stats = detector.getStatistics();
            assertEquals(2, stats.getTotalJoins());
            assertEquals(1, stats.getSameSourceJoins());
            assertEquals(1, stats.getCrossSourceJoins());
            assertEquals(0.5, stats.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("Detector-8: detectAll 批量识别")
        void testDetectAll() {
            StubIcebergAdapter a1 = new StubIcebergAdapter();
            StubDorisAdapter a2 = new StubDorisAdapter();
            CrossSourceJoinDetector detector = new CrossSourceJoinDetector(List.of(a1, a2));

            List<CustomRelNode> joins = Arrays.asList(
                    sameSourceJoin(a1, "t1", "t2", "t1.id=t2.id"),
                    crossSourceJoin(a1, "t1", a2, "t2", "t1.id=t2.id"));

            List<CrossSourceJoinDetector.DetectionResult> results = detector.detectAll(joins);

            assertEquals(2, results.size());
            assertEquals(CrossSourceJoinDetector.JoinType.SAME_SOURCE, results.get(0).getType());
            assertEquals(CrossSourceJoinDetector.JoinType.CROSS_SOURCE, results.get(1).getType());
        }

        @Test
        @DisplayName("Detector-9: findAdapter 查找适配器")
        void testFindAdapter() {
            StubIcebergAdapter a1 = new StubIcebergAdapter();
            StubDorisAdapter a2 = new StubDorisAdapter();
            CrossSourceJoinDetector detector = new CrossSourceJoinDetector(List.of(a1, a2));

            assertEquals(a1, detector.findAdapter("iceberg_lake"));
            assertEquals(a2, detector.findAdapter("doris_olap"));
            assertNull(detector.findAdapter("unknown"));
            assertNull(detector.findAdapter(null));
        }
    }

    // ===================== BroadcastJoinStrategy 单元测试 =====================

    @Nested
    @DisplayName("BroadcastJoinStrategy 广播策略")
    class BroadcastStrategyTest {

        @Test
        @DisplayName("Strategy-1: 小表 < 100MB 触发 BROADCAST")
        void testSmallTableBroadcast() {
            StubTableStatistics stats = new StubTableStatistics()
                    .set("big", 10_000_000L, 200L)    // 2GB
                    .set("small", 500_000L, 100L);    // 50MB
            BroadcastJoinStrategy strategy = new BroadcastJoinStrategy(stats);

            CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN).setCondition("a=b");
            join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName("big"));
            join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName("small"));

            BroadcastJoinStrategy.StrategyResult result = strategy.chooseStrategy(join);

            assertEquals(BroadcastJoinStrategy.JoinStrategy.BROADCAST, result.getStrategy());
            assertEquals(BroadcastJoinStrategy.BroadcastSide.RIGHT, result.getBroadcastSide());
        }

        @Test
        @DisplayName("Strategy-2: 极小表 < 10MB 触发 REPLICATED")
        void testTinyTableReplicated() {
            StubTableStatistics stats = new StubTableStatistics()
                    .set("big", 10_000_000L, 200L)    // 2GB
                    .set("tiny", 50_000L, 100L);      // 5MB
            BroadcastJoinStrategy strategy = new BroadcastJoinStrategy(stats);

            CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN).setCondition("a=b");
            join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName("big"));
            join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName("tiny"));

            BroadcastJoinStrategy.StrategyResult result = strategy.chooseStrategy(join);

            assertEquals(BroadcastJoinStrategy.JoinStrategy.REPLICATED, result.getStrategy());
        }

        @Test
        @DisplayName("Strategy-3: 大表均 > 100MB 触发 SHUFFLE")
        void testLargeTableShuffle() {
            StubTableStatistics stats = new StubTableStatistics()
                    .set("big1", 10_000_000L, 200L)   // 2GB
                    .set("big2", 20_000_000L, 200L);  // 4GB
            BroadcastJoinStrategy strategy = new BroadcastJoinStrategy(stats);

            CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN).setCondition("a=b");
            join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName("big1"));
            join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName("big2"));

            BroadcastJoinStrategy.StrategyResult result = strategy.chooseStrategy(join);

            assertEquals(BroadcastJoinStrategy.JoinStrategy.SHUFFLE, result.getStrategy());
            assertEquals(BroadcastJoinStrategy.BroadcastSide.NONE, result.getBroadcastSide());
        }

        @Test
        @DisplayName("Strategy-4: 自定义阈值 50MB")
        void testCustomThreshold() {
            StubTableStatistics stats = new StubTableStatistics()
                    .set("t1", 1_000_000L, 100L)   // 100MB
                    .set("t2", 600_000L, 100L);    // 60MB
            // 阈值 50MB → 60MB > 50MB → SHUFFLE
            BroadcastJoinStrategy strategy = new BroadcastJoinStrategy(stats, 50L * 1024 * 1024);

            CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN).setCondition("a=b");
            join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName("t1"));
            join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName("t2"));

            BroadcastJoinStrategy.StrategyResult result = strategy.chooseStrategy(join);

            assertEquals(BroadcastJoinStrategy.JoinStrategy.SHUFFLE, result.getStrategy());
        }

        @Test
        @DisplayName("Strategy-5: 统计信息缺失默认 SHUFFLE")
        void testMissingStatistics() {
            StubTableStatistics stats = new StubTableStatistics();
            BroadcastJoinStrategy strategy = new BroadcastJoinStrategy(stats);

            CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN).setCondition("a=b");
            join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName("unknown1"));
            join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName("unknown2"));

            BroadcastJoinStrategy.StrategyResult result = strategy.chooseStrategy(join);

            assertEquals(BroadcastJoinStrategy.JoinStrategy.SHUFFLE, result.getStrategy());
        }

        @Test
        @DisplayName("Strategy-6: isBroadcastable 判断")
        void testIsBroadcastable() {
            StubTableStatistics stats = new StubTableStatistics()
                    .set("small", 100_000L, 100L)    // 10MB
                    .set("big", 10_000_000L, 200L);  // 2GB
            BroadcastJoinStrategy strategy = new BroadcastJoinStrategy(stats);

            assertTrue(strategy.isBroadcastable("small"));
            assertFalse(strategy.isBroadcastable("big"));
            assertFalse(strategy.isBroadcastable("unknown"));
        }

        @Test
        @DisplayName("Strategy-7: estimateTableSize 估算表大小")
        void testEstimateTableSize() {
            StubTableStatistics stats = new StubTableStatistics()
                    .set("t", 1000L, 200L);
            BroadcastJoinStrategy strategy = new BroadcastJoinStrategy(stats);

            assertEquals(200_000L, strategy.estimateTableSize("t"));
            assertEquals(-1, strategy.estimateTableSize("unknown"));
        }

        @Test
        @DisplayName("Strategy-8: null 节点返回失败")
        void testNullNode() {
            StubTableStatistics stats = new StubTableStatistics();
            BroadcastJoinStrategy strategy = new BroadcastJoinStrategy(stats);

            BroadcastJoinStrategy.StrategyResult result = strategy.chooseStrategy(null);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Strategy-9: 统计器记录正确")
        void testStatistics() {
            StubTableStatistics stats = new StubTableStatistics()
                    .set("big", 10_000_000L, 200L)
                    .set("small", 500_000L, 100L);
            BroadcastJoinStrategy strategy = new BroadcastJoinStrategy(stats);

            CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN).setCondition("a=b");
            join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName("big"));
            join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN).setTableName("small"));

            strategy.chooseStrategy(join);

            BroadcastJoinStrategy.StrategyStatistics s = strategy.getStatistics();
            assertEquals(1, s.getTotalDecisions());
            assertEquals(1, s.getBroadcastCount());
        }
    }

    // ===================== JoinReorderOptimizer 单元测试 =====================

    @Nested
    @DisplayName("JoinReorderOptimizer 重排序优化")
    class JoinReorderTest {

        @Test
        @DisplayName("Reorder-1: 2 表无需重排序")
        void testTwoTablesNoReorder() {
            StubJoinStatistics stats = new StubJoinStatistics()
                    .setRows("t1", 1000L)
                    .setRows("t2", 2000L);
            JoinReorderOptimizer optimizer = new JoinReorderOptimizer(stats);

            JoinReorderOptimizer.ReorderResult result = optimizer.reorder(Arrays.asList("t1", "t2"));

            assertFalse(result.isReordered());
            assertEquals("Identity", result.getAlgorithm());
        }

        @Test
        @DisplayName("Reorder-2: 3 表动态规划选择最优顺序")
        void testThreeTablesDp() {
            StubJoinStatistics stats = new StubJoinStatistics()
                    .setRows("big", 10_000_000L)
                    .setRows("mid", 100_000L)
                    .setRows("small", 1000L);
            JoinReorderOptimizer optimizer = new JoinReorderOptimizer(stats);

            JoinReorderOptimizer.ReorderResult result =
                    optimizer.reorder(Arrays.asList("big", "mid", "small"));

            assertTrue(result.isReordered());
            assertEquals("DynamicProgramming", result.getAlgorithm());
            // 最优顺序应让大表 big 不在第一位（小表/中表先 Join）
            assertNotEquals("big", result.getOptimalOrder().get(0),
                    "最优顺序不应让大表 big 在第一位");
            // 最优 Cost 应 ≤ 原始 Cost
            assertTrue(result.getOptimalCost() <= result.getOriginalCost(),
                    "最优 Cost 应 ≤ 原始 Cost");
            // 顺序应包含所有 3 个表
            assertEquals(3, result.getOptimalOrder().size());
            assertTrue(result.getOptimalOrder().contains("big"));
            assertTrue(result.getOptimalOrder().contains("mid"));
            assertTrue(result.getOptimalOrder().contains("small"));
        }

        @Test
        @DisplayName("Reorder-3: 10 表使用贪心算法")
        void testTenTablesGreedy() {
            StubJoinStatistics stats = new StubJoinStatistics();
            for (int i = 1; i <= 10; i++) {
                stats.setRows("t" + i, (long) (1_000_000 * i));
            }
            JoinReorderOptimizer optimizer = new JoinReorderOptimizer(stats);

            List<String> tables = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                tables.add("t" + i);
            }
            JoinReorderOptimizer.ReorderResult result = optimizer.reorder(tables);

            assertTrue(result.isReordered());
            assertEquals("Greedy", result.getAlgorithm());
            assertEquals(10, result.getOptimalOrder().size());
            // 贪心起点应是最小表 t1
            assertEquals("t1", result.getOptimalOrder().get(0));
        }

        @Test
        @DisplayName("Reorder-4: Cost 改善比例计算")
        void testImprovement() {
            StubJoinStatistics stats = new StubJoinStatistics()
                    .setRows("big", 10_000_000L)
                    .setRows("mid", 100_000L)
                    .setRows("small", 1000L);
            JoinReorderOptimizer optimizer = new JoinReorderOptimizer(stats);

            // 原始顺序：big 在前（差顺序）
            JoinReorderOptimizer.ReorderResult result =
                    optimizer.reorder(Arrays.asList("big", "mid", "small"));

            assertTrue(result.getImprovement() >= 0, "改善比例应非负");
            assertTrue(result.getOriginalCost() >= result.getOptimalCost(),
                    "最优 Cost 应 ≤ 原始 Cost");
        }

        @Test
        @DisplayName("Reorder-5: estimateSelectivity 选择率估算")
        void testEstimateSelectivity() {
            StubJoinStatistics stats = new StubJoinStatistics()
                    .setNdv("t1", "id", 1000L)
                    .setNdv("t2", "id", 2000L);
            JoinReorderOptimizer optimizer = new JoinReorderOptimizer(stats);

            double sel = optimizer.estimateSelectivity("t1", "id", "t2", "id");
            // 1 / max(1000, 2000) = 0.0005
            assertEquals(0.0005, sel, 0.0001);
        }

        @Test
        @DisplayName("Reorder-6: 缺失统计信息使用默认值")
        void testMissingStatsDefault() {
            StubJoinStatistics stats = new StubJoinStatistics();
            JoinReorderOptimizer optimizer = new JoinReorderOptimizer(stats);

            long ndv = optimizer.getNdv("unknown", "col");
            assertEquals(JoinReorderOptimizer.DEFAULT_NDV, ndv);
        }

        @Test
        @DisplayName("Reorder-7: 单表无需重排序")
        void testSingleTable() {
            StubJoinStatistics stats = new StubJoinStatistics().setRows("t1", 1000L);
            JoinReorderOptimizer optimizer = new JoinReorderOptimizer(stats);

            JoinReorderOptimizer.ReorderResult result = optimizer.reorder(List.of("t1"));

            assertFalse(result.isReordered());
            assertEquals(1, result.getOptimalOrder().size());
        }

        @Test
        @DisplayName("Reorder-8: 统计器记录正确")
        void testStatistics() {
            StubJoinStatistics stats = new StubJoinStatistics()
                    .setRows("t1", 1000L)
                    .setRows("t2", 2000L)
                    .setRows("t3", 3000L);
            JoinReorderOptimizer optimizer = new JoinReorderOptimizer(stats);

            optimizer.reorder(Arrays.asList("t1", "t2", "t3"));

            JoinReorderOptimizer.ReorderStatistics s = optimizer.getStatistics();
            assertEquals(1, s.getTotalReorders());
            assertEquals(1, s.getDpCount());
        }
    }

    // ===================== JoinPushDownRule 综合测试 =====================

    @Nested
    @DisplayName("JoinPushDownRule 综合场景")
    class JoinPushDownRuleIntegrationTest {

        @Test
        @DisplayName("Integration-1: 混合 5 个同源 + 2 个跨源，下推率 ≥ 70%")
        void testMixedJoinPushDownRate() {
            StubIcebergAdapter iceberg = new StubIcebergAdapter();
            StubDorisAdapter doris = new StubDorisAdapter();
            List<BaseAdapter> adapters = List.of(iceberg, doris);
            StubTableStatistics tableStats = new StubTableStatistics();
            StubJoinStatistics joinStats = new StubJoinStatistics();
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);

            // 5 个同源 Join
            for (int i = 1; i <= 5; i++) {
                rule.apply(sameSourceJoin(iceberg, "t" + i, "t" + (i + 1), "t" + i + ".id=t" + (i + 1) + ".id"));
            }
            // 2 个跨源 Join
            rule.apply(crossSourceJoin(iceberg, "it", doris, "dt", "it.id=dt.id"));
            rule.apply(crossSourceJoin(iceberg, "it2", doris, "dt2", "it2.id=dt2.id"));

            // 同源 5 个全部下推，跨源 2 个保留 → 下推率 = 5/7 ≈ 71.4%
            assertPushDownRate(rule.getStatistics(), 0.7, "Integration-1");
            assertEquals(5, rule.getStatistics().getSameSourcePushed());
            assertEquals(2, rule.getStatistics().getCrossSourceJoins());
        }

        @Test
        @DisplayName("Integration-2: 5 种数据源各 1 个同源 Join 全部下推")
        void testAllFiveSourcesSameSource() {
            StubIcebergAdapter iceberg = new StubIcebergAdapter();
            StubDorisAdapter doris = new StubDorisAdapter();
            StubTrinoAdapter trino = new StubTrinoAdapter();
            StubIoTDBAdapter iotdb = new StubIoTDBAdapter();
            StubElasticsearchAdapter es = new StubElasticsearchAdapter();
            List<BaseAdapter> adapters = List.of(iceberg, doris, trino, iotdb, es);
            StubTableStatistics tableStats = new StubTableStatistics();
            StubJoinStatistics joinStats = new StubJoinStatistics();

            // 为每种数据源构造规则并测试
            for (BaseAdapter adapter : adapters) {
                JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);
                CustomRelNode join = sameSourceJoin(adapter, "t1", "t2", "t1.id=t2.id");
                CustomRelNode result = rule.apply(join);
                assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus(),
                        adapter.getAdapterType() + " 同源 Join 应下推");
            }
        }

        @Test
        @DisplayName("Integration-3: 跨源 Join 标注策略 remark")
        void testCrossSourceJoinRemark() {
            StubIcebergAdapter iceberg = new StubIcebergAdapter();
            StubDorisAdapter doris = new StubDorisAdapter();
            List<BaseAdapter> adapters = List.of(iceberg, doris);
            StubTableStatistics tableStats = new StubTableStatistics()
                    .set("it", 10_000_000L, 200L)
                    .set("dt", 500_000L, 100L);
            StubJoinStatistics joinStats = new StubJoinStatistics();
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);

            CustomRelNode join = crossSourceJoin(iceberg, "it", doris, "dt", "it.id=dt.id");
            CustomRelNode result = rule.apply(join);

            assertNotNull(result.getRemark());
            assertTrue(result.getRemark().contains("BROADCAST") || result.getRemark().contains("SHUFFLE"),
                    "remark 应包含策略信息: " + result.getRemark());
        }

        @Test
        @DisplayName("Integration-4: collectJoinTables 收集表名")
        void testCollectJoinTables() {
            StubIcebergAdapter iceberg = new StubIcebergAdapter();
            StubDorisAdapter doris = new StubDorisAdapter();
            List<BaseAdapter> adapters = List.of(iceberg, doris);
            StubTableStatistics tableStats = new StubTableStatistics();
            StubJoinStatistics joinStats = new StubJoinStatistics();
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);

            CustomRelNode join = crossSourceJoin(iceberg, "t1", doris, "t2", "t1.id=t2.id");
            List<String> tables = rule.collectJoinTables(join);

            assertEquals(2, tables.size());
            assertTrue(tables.contains("t1"));
            assertTrue(tables.contains("t2"));
        }

        @Test
        @DisplayName("Integration-5: reorderJoinOrder 重排序")
        void testReorderJoinOrder() {
            StubIcebergAdapter iceberg = new StubIcebergAdapter();
            List<BaseAdapter> adapters = List.of(iceberg);
            StubTableStatistics tableStats = new StubTableStatistics();
            StubJoinStatistics joinStats = new StubJoinStatistics()
                    .setRows("big", 10_000_000L)
                    .setRows("mid", 100_000L)
                    .setRows("small", 1000L);
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);

            // 构造 3 表 Join 树
            CustomRelNode inner = sameSourceJoin(iceberg, "big", "mid", "big.id=mid.id");
            CustomRelNode outer = CustomRelNode.of(CustomRelNode.Op.JOIN).setCondition("inner.id=small.id");
            outer.addChild(inner);
            outer.addChild(scan(iceberg, "small"));

            JoinReorderOptimizer.ReorderResult result = rule.reorderJoinOrder(outer);

            assertEquals(3, result.getOptimalOrder().size());
            // 最优顺序应让大表 big 不在第一位
            assertNotEquals("big", result.getOptimalOrder().get(0),
                    "最优顺序不应让大表 big 在第一位");
            // 最优 Cost 应 ≤ 原始 Cost
            assertTrue(result.getOptimalCost() <= result.getOriginalCost(),
                    "最优 Cost 应 ≤ 原始 Cost");
        }

        @Test
        @DisplayName("Integration-6: getActiveSourceTypes 活跃数据源")
        void testGetActiveSourceTypes() {
            StubIcebergAdapter iceberg = new StubIcebergAdapter();
            StubDorisAdapter doris = new StubDorisAdapter();
            List<BaseAdapter> adapters = List.of(iceberg, doris);
            StubTableStatistics tableStats = new StubTableStatistics();
            StubJoinStatistics joinStats = new StubJoinStatistics();
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);

            rule.apply(sameSourceJoin(iceberg, "t1", "t2", "t1.id=t2.id"));
            rule.apply(sameSourceJoin(doris, "t3", "t4", "t3.id=t4.id"));

            Set<DataSourceConfig.Type> active = rule.getActiveSourceTypes();
            assertTrue(active.contains(DataSourceConfig.Type.ICEBERG));
            assertTrue(active.contains(DataSourceConfig.Type.DORIS));
        }

        @Test
        @DisplayName("Integration-7: 统计器 summary 输出")
        void testStatisticsSummary() {
            StubIcebergAdapter iceberg = new StubIcebergAdapter();
            List<BaseAdapter> adapters = List.of(iceberg);
            StubTableStatistics tableStats = new StubTableStatistics();
            StubJoinStatistics joinStats = new StubJoinStatistics();
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);

            rule.apply(sameSourceJoin(iceberg, "t1", "t2", "t1.id=t2.id"));

            String summary = rule.getStatistics().summary();
            assertTrue(summary.contains("JoinPushDownStatistics"));
            assertTrue(summary.contains("ICEBERG"));
        }
    }

    // ===================== 边界与异常测试 =====================

    @Nested
    @DisplayName("边界与异常场景")
    class EdgeCaseTest {

        @Test
        @DisplayName("Edge-1: 空适配器列表构造不报错")
        void testEmptyAdapters() {
            assertDoesNotThrow(() -> new CrossSourceJoinDetector(Collections.emptyList()));
        }

        @Test
        @DisplayName("Edge-2: null 适配器被跳过")
        void testNullAdapterSkipped() {
            StubIcebergAdapter adapter = new StubIcebergAdapter();
            CrossSourceJoinDetector detector = new CrossSourceJoinDetector(
                    Arrays.asList(null, adapter, null));
            assertEquals(1, detector.getAdapterRegistry().size());
        }

        @Test
        @DisplayName("Edge-3: Broadcast 阈值为负数抛异常")
        void testNegativeThreshold() {
            StubTableStatistics stats = new StubTableStatistics();
            assertThrows(IllegalArgumentException.class,
                    () -> new BroadcastJoinStrategy(stats, -1));
        }

        @Test
        @DisplayName("Edge-4: Broadcast 阈值为 0 抛异常")
        void testZeroThreshold() {
            StubTableStatistics stats = new StubTableStatistics();
            assertThrows(IllegalArgumentException.class,
                    () -> new BroadcastJoinStrategy(stats, 0));
        }

        @Test
        @DisplayName("Edge-5: JoinPushDownStatistics reset 重置")
        void testStatisticsReset() {
            JoinPushDownRule.JoinPushDownStatistics stats = new JoinPushDownRule.JoinPushDownStatistics();
            stats.recordSameSource(DataSourceConfig.Type.ICEBERG, "iceberg", true);
            stats.recordCrossSource(null, null, "a", "b", null, false);

            stats.reset();

            assertEquals(0, stats.getTotalJoins());
            assertEquals(0.0, stats.getPushDownRate());
        }

        @Test
        @DisplayName("Edge-6: DetectorStatistics reset 重置")
        void testDetectorStatisticsReset() {
            CrossSourceJoinDetector.DetectorStatistics stats = new CrossSourceJoinDetector.DetectorStatistics();
            stats.recordSameSource("iceberg");
            stats.recordCrossSource("a", "b", "reason");

            stats.reset();

            assertEquals(0, stats.getTotalJoins());
            assertEquals(0.0, stats.getPushDownRate());
        }

        @Test
        @DisplayName("Edge-7: StrategyStatistics reset 重置")
        void testStrategyStatisticsReset() {
            BroadcastJoinStrategy.StrategyStatistics stats = new BroadcastJoinStrategy.StrategyStatistics();
            stats.recordStrategy(BroadcastJoinStrategy.JoinStrategy.BROADCAST,
                    BroadcastJoinStrategy.BroadcastSide.RIGHT, 100, 50);

            stats.reset();

            assertEquals(0, stats.getTotalDecisions());
        }

        @Test
        @DisplayName("Edge-8: ReorderStatistics reset 重置")
        void testReorderStatisticsReset() {
            JoinReorderOptimizer.ReorderStatistics stats = new JoinReorderOptimizer.ReorderStatistics();
            stats.recordReorder(3, "DynamicProgramming", 1000, 500, 0.5);

            stats.reset();

            assertEquals(0, stats.getTotalReorders());
        }

        @Test
        @DisplayName("Edge-9: 禁用规则后不匹配")
        void testDisabledRule() {
            StubIcebergAdapter adapter = new StubIcebergAdapter();
            List<BaseAdapter> adapters = List.of(adapter);
            StubTableStatistics tableStats = new StubTableStatistics();
            StubJoinStatistics joinStats = new StubJoinStatistics();
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);
            rule.setEnabled(false);

            CustomRelNode join = sameSourceJoin(adapter, "t1", "t2", "t1.id=t2.id");
            CustomRelNode result = rule.apply(join);

            // 禁用后返回原节点，未改写
            assertEquals(CustomRelNode.PushDownStatus.NOT_PUSHED, result.getPushDownStatus());
        }

        @Test
        @DisplayName("Edge-10: 非 JOIN 节点不匹配")
        void testNonJoinNodeNotMatch() {
            StubIcebergAdapter adapter = new StubIcebergAdapter();
            List<BaseAdapter> adapters = List.of(adapter);
            StubTableStatistics tableStats = new StubTableStatistics();
            StubJoinStatistics joinStats = new StubJoinStatistics();
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);

            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER).setCondition("x>1");
            CustomRelNode result = rule.apply(filter);

            // 非 JOIN 不匹配，返回原节点
            assertSame(filter, result);
        }

        @Test
        @DisplayName("Edge-11: JoinType 枚举 isPushable")
        void testJoinTypeIsPushable() {
            assertTrue(CrossSourceJoinDetector.JoinType.SAME_SOURCE.isPushable());
            assertFalse(CrossSourceJoinDetector.JoinType.CROSS_SOURCE.isPushable());
            assertFalse(CrossSourceJoinDetector.JoinType.UNKNOWN.isPushable());
        }

        @Test
        @DisplayName("Edge-12: JoinStrategy isBroadcastLike")
        void testJoinStrategyIsBroadcastLike() {
            assertTrue(BroadcastJoinStrategy.JoinStrategy.BROADCAST.isBroadcastLike());
            assertTrue(BroadcastJoinStrategy.JoinStrategy.REPLICATED.isBroadcastLike());
            assertFalse(BroadcastJoinStrategy.JoinStrategy.SHUFFLE.isBroadcastLike());
        }

        @Test
        @DisplayName("Edge-13: 同源 Join 但适配器未注册 → 下推失败")
        void testSameSourceAdapterNotRegistered() {
            StubIcebergAdapter adapter = new StubIcebergAdapter();
            // detector 只注册了 adapter，但 rule 的基类 adapter 也是它
            // 构造一个数据源名不在 registry 的 Join
            CrossSourceJoinDetector detector = new CrossSourceJoinDetector(List.of(adapter));
            BroadcastJoinStrategy broadcastStrategy = new BroadcastJoinStrategy(new StubTableStatistics());
            JoinReorderOptimizer reorderOptimizer = new JoinReorderOptimizer(new StubJoinStatistics());
            JoinPushDownRule rule = new JoinPushDownRule(adapter, detector, broadcastStrategy, reorderOptimizer);

            // 构造一个未注册数据源的 Join
            CustomRelNode scan1 = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                    .setTableName("t1").setSourceName("unregistered_source");
            CustomRelNode scan2 = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                    .setTableName("t2").setSourceName("unregistered_source");
            CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN).setCondition("t1.id=t2.id");
            join.addChild(scan1);
            join.addChild(scan2);

            CustomRelNode result = rule.apply(join);

            // 同源但适配器未注册 → 下推失败
            assertEquals(CustomRelNode.PushDownStatus.NOT_PUSHED, result.getPushDownStatus());
        }

        @Test
        @DisplayName("Edge-14: JoinPushDownStatistics 各 getter")
        void testStatisticsGetters() {
            JoinPushDownRule.JoinPushDownStatistics stats = new JoinPushDownRule.JoinPushDownStatistics();
            stats.recordSameSource(DataSourceConfig.Type.ICEBERG, "iceberg", true);
            stats.recordSameSource(DataSourceConfig.Type.DORIS, "doris", false);
            stats.recordCrossSource(DataSourceConfig.Type.ICEBERG, DataSourceConfig.Type.DORIS,
                    "iceberg", "doris", BroadcastJoinStrategy.JoinStrategy.BROADCAST, true);
            stats.recordCrossSource(DataSourceConfig.Type.ICEBERG, DataSourceConfig.Type.DORIS,
                    "iceberg", "doris", BroadcastJoinStrategy.JoinStrategy.SHUFFLE, true);
            stats.recordUnknown("unknown reason");

            assertEquals(5, stats.getTotalJoins());
            assertEquals(2, stats.getSameSourceJoins());
            assertEquals(1, stats.getSameSourcePushed());
            assertEquals(2, stats.getCrossSourceJoins());
            assertEquals(1, stats.getUnknownJoins());
            assertEquals(1, stats.getBroadcastCount());
            assertEquals(1, stats.getShuffleCount());
            assertEquals(0, stats.getReplicatedCount());
            assertTrue(stats.getSameSourcePushDownRate() > 0);
            assertTrue(stats.getBroadcastRate() > 0);
            assertTrue(stats.getPushDownRate(DataSourceConfig.Type.ICEBERG) > 0);
            assertFalse(stats.getCrossSourceReasons().isEmpty());
            assertFalse(stats.getPushedDescriptions().isEmpty());
            assertNotNull(stats.getSourceStats());
            assertNotNull(stats.toString());
        }

        @Test
        @DisplayName("Edge-15: DetectionResult 各工厂方法与 getter")
        void testDetectionResultFactoryMethods() {
            CrossSourceJoinDetector.DetectionResult same =
                    CrossSourceJoinDetector.DetectionResult.sameSource("iceberg",
                            Set.of("iceberg"), Set.of("iceberg"));
            assertTrue(same.isPushable());
            assertFalse(same.isCrossSource());
            assertEquals("iceberg", same.getSource());

            CrossSourceJoinDetector.DetectionResult cross =
                    CrossSourceJoinDetector.DetectionResult.crossSource("iceberg", "doris", "跨源");
            assertFalse(cross.isPushable());
            assertTrue(cross.isCrossSource());
            assertNull(cross.getSource());

            CrossSourceJoinDetector.DetectionResult unknown =
                    CrossSourceJoinDetector.DetectionResult.unknown("未知");
            assertFalse(unknown.isPushable());
            assertNull(unknown.getSource());

            assertNotNull(same.toString());
            assertNotNull(cross.toString());
            assertNotNull(unknown.toString());
        }

        @Test
        @DisplayName("Edge-16: StrategyResult 失败工厂方法")
        void testStrategyResultFailure() {
            BroadcastJoinStrategy.StrategyResult failure =
                    BroadcastJoinStrategy.StrategyResult.failure("test failure");
            assertFalse(failure.isSuccess());
            assertFalse(failure.isBroadcast());
            assertNotNull(failure.toString());
        }

        @Test
        @DisplayName("Edge-17: ReorderResult identity 工厂方法")
        void testReorderResultIdentity() {
            JoinReorderOptimizer.ReorderResult identity =
                    JoinReorderOptimizer.ReorderResult.identity(Arrays.asList("t1", "t2"));
            assertFalse(identity.isReordered());
            assertEquals("Identity", identity.getAlgorithm());
            assertEquals(2, identity.getOptimalOrder().size());

            JoinReorderOptimizer.ReorderResult nullIdentity =
                    JoinReorderOptimizer.ReorderResult.identity(null);
            assertEquals(0, nullIdentity.getOptimalOrder().size());
        }

        @Test
        @DisplayName("Edge-18: JoinType description 与 JoinStrategy description")
        void testEnumDescriptions() {
            assertNotNull(CrossSourceJoinDetector.JoinType.SAME_SOURCE.description());
            assertNotNull(CrossSourceJoinDetector.JoinType.CROSS_SOURCE.description());
            assertNotNull(CrossSourceJoinDetector.JoinType.UNKNOWN.description());
            assertNotNull(BroadcastJoinStrategy.JoinStrategy.BROADCAST.description());
            assertNotNull(BroadcastJoinStrategy.JoinStrategy.SHUFFLE.description());
            assertNotNull(BroadcastJoinStrategy.JoinStrategy.REPLICATED.description());
        }

        @Test
        @DisplayName("Edge-19: TableStat unknown 与 estimatedSize")
        void testTableStatUnknown() {
            BroadcastJoinStrategy.TableStat unknown = BroadcastJoinStrategy.TableStat.unknown();
            assertTrue(unknown.isUnknown());
            assertEquals(Long.MAX_VALUE, unknown.estimatedSize());

            BroadcastJoinStrategy.TableStat normal = new BroadcastJoinStrategy.TableStat("t", 1000, 100);
            assertFalse(normal.isUnknown());
            assertEquals(100_000L, normal.estimatedSize());
            assertNotNull(normal.toString());
        }

        @Test
        @DisplayName("Edge-20: DetectorStatistics 各 getter")
        void testDetectorStatisticsGetters() {
            CrossSourceJoinDetector.DetectorStatistics stats = new CrossSourceJoinDetector.DetectorStatistics();
            stats.recordSameSource("iceberg");
            stats.recordCrossSource("iceberg", "doris", "跨源原因");
            stats.recordUnknown("未知原因");

            assertEquals(3, stats.getTotalJoins());
            assertEquals(1, stats.getSameSourceJoins());
            assertEquals(1, stats.getCrossSourceJoins());
            assertEquals(1, stats.getUnknownJoins());
            assertFalse(stats.getSameSourceByAdapter().isEmpty());
            assertFalse(stats.getCrossSourceReasons().isEmpty());
            assertNotNull(stats.toString());
        }

        @Test
        @DisplayName("Edge-21: StrategyStatistics 各 getter")
        void testStrategyStatisticsGetters() {
            BroadcastJoinStrategy.StrategyStatistics stats = new BroadcastJoinStrategy.StrategyStatistics();
            stats.recordStrategy(BroadcastJoinStrategy.JoinStrategy.BROADCAST,
                    BroadcastJoinStrategy.BroadcastSide.RIGHT, 100, 50);
            stats.recordStrategy(BroadcastJoinStrategy.JoinStrategy.SHUFFLE,
                    BroadcastJoinStrategy.BroadcastSide.NONE, 200, 150);
            stats.recordStrategy(BroadcastJoinStrategy.JoinStrategy.REPLICATED,
                    BroadcastJoinStrategy.BroadcastSide.LEFT, 30, 10);

            assertEquals(3, stats.getTotalDecisions());
            assertEquals(1, stats.getBroadcastCount());
            assertEquals(1, stats.getShuffleCount());
            assertEquals(1, stats.getReplicatedCount());
            assertTrue(stats.getBroadcastRate() > 0);
            assertFalse(stats.getStrategyCount().isEmpty());
            assertNotNull(stats.toString());
        }

        @Test
        @DisplayName("Edge-22: ReorderStatistics 各 getter")
        void testReorderStatisticsGetters() {
            JoinReorderOptimizer.ReorderStatistics stats = new JoinReorderOptimizer.ReorderStatistics();
            stats.recordReorder(5, "DynamicProgramming", 1000, 500, 0.5);
            stats.recordReorder(10, "Greedy", 2000, 1500, 0.25);

            assertEquals(2, stats.getTotalReorders());
            assertEquals(1, stats.getDpCount());
            assertEquals(1, stats.getGreedyCount());
            assertTrue(stats.getAverageImprovement() > 0);
            assertTrue(stats.getBestImprovement() > 0);
            assertEquals(10, stats.getMaxTableCount());
            assertNotNull(stats.toString());
        }

        @Test
        @DisplayName("Edge-23: Join 子节点不足 2 个时不匹配")
        void testJoinInsufficientChildren() {
            StubIcebergAdapter adapter = new StubIcebergAdapter();
            List<BaseAdapter> adapters = List.of(adapter);
            StubTableStatistics tableStats = new StubTableStatistics();
            StubJoinStatistics joinStats = new StubJoinStatistics();
            JoinPushDownRule rule = buildRule(adapters, tableStats, joinStats);

            CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN).setCondition("a=b");
            join.addChild(scan(adapter, "t1"));  // 只有 1 个子节点

            CustomRelNode result = rule.apply(join);

            // 子节点不足，不匹配，返回原节点
            assertSame(join, result);
        }

        @Test
        @DisplayName("Edge-24: collectSources 收集数据源")
        void testCollectSources() {
            StubIcebergAdapter adapter = new StubIcebergAdapter();
            CrossSourceJoinDetector detector = new CrossSourceJoinDetector(List.of(adapter));

            Set<String> sources = detector.collectSources(scan(adapter, "t1"));
            assertEquals(1, sources.size());
            assertTrue(sources.contains("iceberg_lake"));

            assertTrue(detector.collectSources(null).isEmpty());
        }

        @Test
        @DisplayName("Edge-25: BroadcastJoinStrategy getReplicatedThreshold")
        void testReplicatedThreshold() {
            StubTableStatistics stats = new StubTableStatistics();
            BroadcastJoinStrategy strategy = new BroadcastJoinStrategy(stats, 100L * 1024 * 1024);

            assertEquals(10L * 1024 * 1024, strategy.getReplicatedThreshold());
            assertEquals(100L * 1024 * 1024, strategy.getBroadcastThreshold());
        }
    }
}