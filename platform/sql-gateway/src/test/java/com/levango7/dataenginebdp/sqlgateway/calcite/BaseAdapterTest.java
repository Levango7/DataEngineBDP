package com.levango7.dataenginebdp.sqlgateway.calcite;

import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.BaseAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.DorisAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.ElasticsearchAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.IcebergAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.IoTDBAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.TrinoAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BaseAdapter} 及 5 种数据源适配器接口的单元测试。
 *
 * <p>通过 {@link StubDorisAdapter} 等桩实现验证：</p>
 * <ul>
 *   <li>{@code toRel} 构造 TableScan RelNode</li>
 *   <li>{@code pushDown} 下推 filter/project 并返回下推 SQL</li>
 *   <li>{@code costEstimate} 返回 CPU/IO/Network Cost</li>
 *   <li>{@code getDialect} 返回数据源方言</li>
 *   <li>{@code canPushDown} 跨源判定</li>
 *   <li>5 种适配器接口的特有方法</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class BaseAdapterTest {

    // ===================== BaseAdapter 核心方法测试 =====================

    @Test
    @DisplayName("toRel 构造 TableScan CustomRelNode")
    void testToRel() {
        StubDorisAdapter adapter = new StubDorisAdapter();
        CustomRelNode rel = adapter.toRel("db.orders", Arrays.asList("id", "amount"));
        assertNotNull(rel);
        assertEquals(CustomRelNode.Op.TABLE_SCAN, rel.getOp());
        assertEquals("db.orders", rel.getTableName());
        assertEquals("doris_olap", rel.getSourceName());
    }

    @Test
    @DisplayName("toRel 空列列表 → 全表扫描")
    void testToRelEmptyColumns() {
        StubDorisAdapter adapter = new StubDorisAdapter();
        CustomRelNode rel = adapter.toRel("db.users", Collections.emptyList());
        assertNotNull(rel);
        assertEquals(CustomRelNode.Op.TABLE_SCAN, rel.getOp());
    }

    @Test
    @DisplayName("pushDown 下推 filter 到数据源")
    void testPushDownFilter() {
        StubDorisAdapter adapter = new StubDorisAdapter();
        CustomRelNode scan = adapter.toRel("db.orders", null);
        CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                .setCondition("amount > 100");
        filter.addChild(scan);

        BaseAdapter.PushDownContext ctx = new BaseAdapter.PushDownContext(
                new ArrayList<>(), SqlDialect.DORIS, 1.0, 10.0, 100.0);
        BaseAdapter.PushDownResult result = adapter.pushDown(filter, ctx);

        assertTrue(result.isSuccess());
        assertNotNull(result.getPushedSql());
        assertTrue(result.getPushedSql().contains("orders"));
        assertTrue(result.getPushedSql().contains("amount > 100"));
    }

    @Test
    @DisplayName("pushDown 跨源节点返回失败")
    void testPushDownFederated() {
        StubDorisAdapter adapter = new StubDorisAdapter();
        // 构造跨源节点：左源 doris_olap，右源 trino_hive
        CustomRelNode left = adapter.toRel("doris_olap.orders", null);
        CustomRelNode right = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("trino_hive.users")
                .setSourceName("trino_hive");
        CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN).setCondition("o.uid = u.id");
        join.addChild(left);
        join.addChild(right);

        BaseAdapter.PushDownContext ctx = new BaseAdapter.PushDownContext(
                new ArrayList<>(), SqlDialect.ANSI, 1.0, 10.0, 100.0);
        BaseAdapter.PushDownResult result = adapter.pushDown(join, ctx);
        assertFalse(result.isSuccess());
        assertNotNull(result.getFailureReason());
    }

    @Test
    @DisplayName("costEstimate 返回 CPU/IO/Network Cost")
    void testCostEstimate() {
        StubDorisAdapter adapter = new StubDorisAdapter();
        CustomRelNode scan = adapter.toRel("db.orders", Arrays.asList("id", "amount"));
        BaseAdapter.Cost cost = adapter.costEstimate(scan);
        assertTrue(cost.getCpuCost() >= 0);
        assertTrue(cost.getIoCost() >= 0);
        assertTrue(cost.getNetworkCost() >= 0);
        assertTrue(cost.getRows() > 0);
        assertTrue(cost.total() > 0);
    }

    @Test
    @DisplayName("costEstimate 加权总 Cost")
    void testCostWeightedTotal() {
        StubDorisAdapter adapter = new StubDorisAdapter();
        CustomRelNode scan = adapter.toRel("db.orders", null);
        BaseAdapter.Cost cost = adapter.costEstimate(scan);
        double weighted = cost.weightedTotal(1.0, 10.0, 100.0);
        assertTrue(weighted >= cost.total());
    }

    @Test
    @DisplayName("Cost.zero 零 Cost")
    void testCostZero() {
        BaseAdapter.Cost zero = BaseAdapter.Cost.zero();
        assertEquals(0, zero.getCpuCost());
        assertEquals(0, zero.getIoCost());
        assertEquals(0, zero.getNetworkCost());
        assertEquals(0, zero.getRows());
        assertEquals(0, zero.total());
    }

    @Test
    @DisplayName("getDialect 返回数据源方言")
    void testGetDialect() {
        StubDorisAdapter adapter = new StubDorisAdapter();
        assertEquals(SqlDialect.DORIS, adapter.getDialect());
    }

    @Test
    @DisplayName("canPushDown 同源可下推")
    void testCanPushDownSameSource() {
        StubDorisAdapter adapter = new StubDorisAdapter();
        CustomRelNode scan = adapter.toRel("doris_olap.orders", null);
        CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                .setCondition("x > 1");
        filter.addChild(scan);
        assertTrue(adapter.canPushDown(filter));
    }

    @Test
    @DisplayName("canPushDown 跨源不可下推")
    void testCanPushDownCrossSource() {
        StubDorisAdapter adapter = new StubDorisAdapter();
        CustomRelNode left = adapter.toRel("doris_olap.orders", null);
        CustomRelNode right = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("trino.users").setSourceName("trino");
        CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN);
        join.addChild(left);
        join.addChild(right);
        assertFalse(adapter.canPushDown(join));
    }

    @Test
    @DisplayName("canPushDown null 返回 false")
    void testCanPushDownNull() {
        StubDorisAdapter adapter = new StubDorisAdapter();
        assertFalse(adapter.canPushDown(null));
    }

    @Test
    @DisplayName("PushDownResult.failure 构造失败结果")
    void testPushDownResultFailure() {
        BaseAdapter.PushDownResult result = BaseAdapter.PushDownResult.failure("test reason");
        assertFalse(result.isSuccess());
        assertEquals("test reason", result.getFailureReason());
        assertNull(result.getPushedSql());
        assertNull(result.getRemainingRel());
    }

    @Test
    @DisplayName("getAdapterType 返回正确类型")
    void testGetAdapterType() {
        assertEquals(DataSourceConfig.Type.DORIS, new StubDorisAdapter().getAdapterType());
        assertEquals(DataSourceConfig.Type.TRINO, new StubTrinoAdapter().getAdapterType());
        assertEquals(DataSourceConfig.Type.ICEBERG, new StubIcebergAdapter().getAdapterType());
        assertEquals(DataSourceConfig.Type.IOTDB, new StubIoTDBAdapter().getAdapterType());
        assertEquals(DataSourceConfig.Type.ELASTICSEARCH,
                new StubElasticsearchAdapter().getAdapterType());
    }

    // ===================== IcebergAdapter 特有方法测试 =====================

    @Test
    @DisplayName("IcebergAdapter 分区裁剪 + 快照选择 + 分区列判定 + schema 版本")
    void testIcebergAdapterSpecific() {
        StubIcebergAdapter adapter = new StubIcebergAdapter();
        List<String> partitions = adapter.prunePartitions("iceberg.orders", "dt >= '2024-01-01'");
        assertNotNull(partitions);
        assertTrue(partitions.size() > 0);

        long snapshot = adapter.selectSnapshot("iceberg.orders", 123L, null);
        assertEquals(123L, snapshot);

        assertTrue(adapter.isPartitionColumn("iceberg.orders", "dt"));
        assertFalse(adapter.isPartitionColumn("iceberg.orders", "amount"));

        assertTrue(adapter.getSchemaVersion("iceberg.orders") >= 1);
    }

    // ===================== DorisAdapter 特有方法测试 =====================

    @Test
    @DisplayName("DorisAdapter 物化视图路由 + Colocate Join + Tablet/行数统计")
    void testDorisAdapterSpecific() {
        StubDorisAdapter adapter = new StubDorisAdapter();
        String mv = adapter.routeMaterializedView("doris_olap.orders",
                Arrays.asList("dt"), Arrays.asList("sum(amount)"));
        assertNotNull(mv);

        assertTrue(adapter.canColocateJoin("doris_olap.t1", "doris_olap.t2"));
        assertFalse(adapter.canColocateJoin("doris_olap.t1", "trino.t2"));

        assertTrue(adapter.getTabletCount("doris_olap.orders") > 0);
        assertTrue(adapter.getEstimatedRowCount("doris_olap.orders") > 0);
    }

    // ===================== TrinoAdapter 特有方法测试 =====================

    @Test
    @DisplayName("TrinoAdapter Connector 路由 + 动态过滤 + CTE 内联 + Worker 数")
    void testTrinoAdapterSpecific() {
        StubTrinoAdapter adapter = new StubTrinoAdapter();
        assertEquals("hive", adapter.getConnectorName("hive.db.table"));
        assertEquals("iceberg", adapter.getConnectorName("iceberg.db.table"));

        assertTrue(adapter.supportsDynamicFiltering("hive"));
        assertFalse(adapter.supportsDynamicFiltering("mysql"));

        String inlined = adapter.inlineCte("WITH t AS (SELECT 1) SELECT * FROM t");
        assertNotNull(inlined);
        assertFalse(inlined.contains("WITH"));

        assertTrue(adapter.getWorkerCount() > 0);
    }

    // ===================== IoTDBAdapter 特有方法测试 =====================

    @Test
    @DisplayName("IoTDBAdapter 时间范围下推 + 降采样 + 查询路径 + 降采样支持")
    void testIoTDBAdapterSpecific() {
        StubIoTDBAdapter adapter = new StubIoTDBAdapter();
        String timeRange = adapter.pushDownTimeRange("time >= '2024-01-01' AND time < '2024-02-01'");
        assertNotNull(timeRange);

        String downsampling = adapter.pushDownDownsampling("mean", "time", "1h");
        assertNotNull(downsampling);

        String path = adapter.toQueryPath("device = 'root.sg.d1'");
        assertNotNull(path);
        assertTrue(path.contains("root.sg.d1"));

        assertTrue(adapter.supportsDownsampling("mean"));
        assertTrue(adapter.supportsDownsampling("max"));
        assertFalse(adapter.supportsDownsampling("unknown"));
    }

    // ===================== ElasticsearchAdapter 特有方法测试 =====================

    @Test
    @DisplayName("ESAdapter Query DSL + Aggregation DSL + Sort DSL + 分页 DSL + 索引可用性")
    void testElasticsearchAdapterSpecific() {
        StubElasticsearchAdapter adapter = new StubElasticsearchAdapter();
        String queryDsl = adapter.toQueryDsl("age > 18");
        assertNotNull(queryDsl);

        String aggDsl = adapter.toAggregationDsl(
                Arrays.asList("category"), Arrays.asList("sum(amount)"));
        assertNotNull(aggDsl);

        String sortDsl = adapter.toSortDsl(Arrays.asList("age DESC"));
        assertNotNull(sortDsl);

        String pageDsl = adapter.toPaginationDsl(100, 0);
        assertNotNull(pageDsl);

        assertTrue(adapter.isIndexAvailable("es_orders"));
        assertFalse(adapter.isIndexAvailable("nonexistent"));
    }

    // ===================== 桩实现 =====================

    /** Doris 适配器桩实现 */
    static class StubDorisAdapter implements DorisAdapter {
        private final DataSourceConfig config = new DataSourceConfig("doris_olap",
                DataSourceConfig.Type.DORIS)
                .setJdbcUrl("jdbc:mysql://localhost:9030")
                .setDialect(SqlDialect.DORIS);

        @Override
        public DataSourceConfig getDataSourceConfig() {
            return config;
        }

        @Override
        public CustomRelNode toRel(String tableName, List<String> columns) {
            return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                    .setTableName(tableName)
                    .setSourceName(config.getName());
        }

        @Override
        public PushDownResult pushDown(CustomRelNode relNode, PushDownContext context) {
            if (!canPushDown(relNode)) {
                return PushDownResult.failure("跨源节点不可下推");
            }
            List<String> tables = relNode.collectTableNames();
            String table = tables.isEmpty() ? "unknown" : tables.get(0);
            String sql = "SELECT * FROM " + table;
            if (relNode.getCondition() != null) {
                sql += " WHERE " + relNode.getCondition();
            }
            List<String> pushed = new ArrayList<>(context.getPushedOperations());
            pushed.add("pushDown:" + relNode.getOp());
            return new PushDownResult(sql, relNode, pushed, true, null);
        }

        @Override
        public Cost costEstimate(CustomRelNode relNode) {
            return new Cost(10, 100, 5, 1000);
        }

        @Override
        public SqlDialect getDialect() {
            return SqlDialect.DORIS;
        }

        @Override
        public String routeMaterializedView(String tableName, List<String> groupBy,
                                            List<String> aggFuncs) {
            return tableName + "_mv";
        }

        @Override
        public boolean canColocateJoin(String leftTable, String rightTable) {
            return leftTable.startsWith("doris_olap") && rightTable.startsWith("doris_olap");
        }

        @Override
        public int getTabletCount(String tableName) {
            return 64;
        }

        @Override
        public long getEstimatedRowCount(String tableName) {
            return 1_000_000L;
        }
    }

    /** Trino 适配器桩实现 */
    static class StubTrinoAdapter implements TrinoAdapter {
        private final DataSourceConfig config = new DataSourceConfig("trino_hive",
                DataSourceConfig.Type.TRINO)
                .setJdbcUrl("jdbc:trino://localhost:8080")
                .setDialect(SqlDialect.TRINO);

        @Override
        public DataSourceConfig getDataSourceConfig() {
            return config;
        }

        @Override
        public CustomRelNode toRel(String tableName, List<String> columns) {
            return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                    .setTableName(tableName).setSourceName(config.getName());
        }

        @Override
        public PushDownResult pushDown(CustomRelNode relNode, PushDownContext context) {
            return new PushDownResult("SELECT * FROM t", relNode,
                    new ArrayList<>(), true, null);
        }

        @Override
        public Cost costEstimate(CustomRelNode relNode) {
            return new Cost(5, 50, 20, 500);
        }

        @Override
        public SqlDialect getDialect() {
            return SqlDialect.TRINO;
        }

        @Override
        public String getConnectorName(String tableName) {
            if (tableName.startsWith("hive.")) {
                return "hive";
            }
            if (tableName.startsWith("iceberg.")) {
                return "iceberg";
            }
            return "default";
        }

        @Override
        public boolean supportsDynamicFiltering(String connectorName) {
            return "hive".equals(connectorName) || "iceberg".equals(connectorName);
        }

        @Override
        public String inlineCte(String sql) {
            return sql.replaceFirst("(?is)WITH\\s+\\w+\\s+AS\\s*\\([^)]+\\)\\s*", "");
        }

        @Override
        public int getWorkerCount() {
            return 10;
        }
    }

    /** Iceberg 适配器桩实现 */
    static class StubIcebergAdapter implements IcebergAdapter {
        private final DataSourceConfig config = new DataSourceConfig("iceberg_lake",
                DataSourceConfig.Type.ICEBERG)
                .setJdbcUrl("jdbc:hive2://localhost:10000")
                .setDialect(SqlDialect.HIVE);

        @Override
        public DataSourceConfig getDataSourceConfig() {
            return config;
        }

        @Override
        public CustomRelNode toRel(String tableName, List<String> columns) {
            return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                    .setTableName(tableName).setSourceName(config.getName());
        }

        @Override
        public PushDownResult pushDown(CustomRelNode relNode, PushDownContext context) {
            return new PushDownResult("SELECT * FROM t", relNode,
                    new ArrayList<>(), true, null);
        }

        @Override
        public Cost costEstimate(CustomRelNode relNode) {
            return new Cost(8, 80, 10, 2000);
        }

        @Override
        public SqlDialect getDialect() {
            return SqlDialect.HIVE;
        }

        @Override
        public List<String> prunePartitions(String tableName, String partitionFilter) {
            return Arrays.asList("2024-01-01", "2024-01-02", "2024-01-03");
        }

        @Override
        public long selectSnapshot(String tableName, Long snapshotId, Long asOfTimestamp) {
            if (snapshotId != null) {
                return snapshotId;
            }
            return 999L;
        }

        @Override
        public boolean isPartitionColumn(String tableName, String column) {
            return "dt".equals(column);
        }

        @Override
        public int getSchemaVersion(String tableName) {
            return 3;
        }
    }

    /** IoTDB 适配器桩实现 */
    static class StubIoTDBAdapter implements IoTDBAdapter {
        private final DataSourceConfig config = new DataSourceConfig("iotdb_ts",
                DataSourceConfig.Type.IOTDB)
                .setEndpoint("http://iotdb:18080")
                .setDialect(SqlDialect.ANSI);

        @Override
        public DataSourceConfig getDataSourceConfig() {
            return config;
        }

        @Override
        public CustomRelNode toRel(String tableName, List<String> columns) {
            return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                    .setTableName(tableName).setSourceName(config.getName());
        }

        @Override
        public PushDownResult pushDown(CustomRelNode relNode, PushDownContext context) {
            return new PushDownResult("SELECT * FROM root.sg", relNode,
                    new ArrayList<>(), true, null);
        }

        @Override
        public Cost costEstimate(CustomRelNode relNode) {
            return new Cost(3, 30, 2, 5000);
        }

        @Override
        public SqlDialect getDialect() {
            return SqlDialect.ANSI;
        }

        @Override
        public String pushDownTimeRange(String timeFilter) {
            return "2024-01-01,2024-02-01";
        }

        @Override
        public String pushDownDownsampling(String aggFunc, String timeColumn, String interval) {
            return aggFunc + "(" + timeColumn + ") GROUP BY interval(" + timeColumn + ", " + interval + ")";
        }

        @Override
        public String toQueryPath(String deviceFilter) {
            if (deviceFilter != null && deviceFilter.contains("root.sg.d1")) {
                return "root.sg.d1.*";
            }
            return "root.**";
        }

        @Override
        public boolean supportsDownsampling(String aggFunc) {
            return "mean".equals(aggFunc) || "max".equals(aggFunc) || "min".equals(aggFunc)
                    || "sum".equals(aggFunc) || "count".equals(aggFunc);
        }
    }

    /** Elasticsearch 适配器桩实现 */
    static class StubElasticsearchAdapter implements ElasticsearchAdapter {
        private final DataSourceConfig config = new DataSourceConfig("es_search",
                DataSourceConfig.Type.ELASTICSEARCH)
                .setEndpoint("http://es:9200")
                .setDialect(SqlDialect.ANSI);

        @Override
        public DataSourceConfig getDataSourceConfig() {
            return config;
        }

        @Override
        public CustomRelNode toRel(String tableName, List<String> columns) {
            return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                    .setTableName(tableName).setSourceName(config.getName());
        }

        @Override
        public PushDownResult pushDown(CustomRelNode relNode, PushDownContext context) {
            return new PushDownResult("GET /_search", relNode,
                    new ArrayList<>(), true, null);
        }

        @Override
        public Cost costEstimate(CustomRelNode relNode) {
            return new Cost(2, 20, 1, 10000);
        }

        @Override
        public SqlDialect getDialect() {
            return SqlDialect.ANSI;
        }

        @Override
        public String toQueryDsl(String predicate) {
            return "{\"query\":{\"match_all\":{}}}";
        }

        @Override
        public String toAggregationDsl(List<String> groupBy, List<String> aggFuncs) {
            return "{\"aggs\":{}}";
        }

        @Override
        public String toSortDsl(List<String> sortKeys) {
            return "{\"sort\":[]}";
        }

        @Override
        public String toPaginationDsl(long limit, long offset) {
            return "{\"from\":" + offset + ",\"size\":" + limit + "}";
        }

        @Override
        public boolean isIndexAvailable(String indexName) {
            return "es_orders".equals(indexName);
        }
    }
}