package com.levango7.dataenginebdp.sqlgateway.calcite.adapter;

import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.OptimizerConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 5 种数据源适配器实现的单元测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>{@link TableStatistics}：统计信息构造与选择率估算</li>
 *   <li>{@link AbstractBaseAdapter}：toRel/pushDown/costEstimate 通用流程</li>
 *   <li>{@link IcebergAdapterImpl}：HIVE 方言、分区裁剪、快照、Cost</li>
 *   <li>{@link DorisAdapterImpl}：DORIS 方言、物化视图路由、Colocate Join、Cost</li>
 *   <li>{@link TrinoAdapterImpl}：TRINO 方言、Connector 路由、动态过滤、CTE 内联、Cost</li>
 *   <li>{@link IoTDBAdapterImpl}：时序 SQL 方言、时间范围/降采样下推、Cost</li>
 *   <li>{@link ElasticsearchAdapterImpl}：DSL 转换、聚合/排序/分页下推、Cost</li>
 *   <li>{@link AdapterRegistry}：YAML 声明式注册</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class AdapterImplementationTest {

    // ===================== 测试辅助：构造配置 =====================

    private static DataSourceConfig icebergConfig() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("partition-column", "dt");
        props.put("stats.iceberg_lake.orders.rowCount", "1000000");
        props.put("stats.iceberg_lake.orders.partitionCount", "30");
        props.put("stats.iceberg_lake.orders.rowSizeBytes", "200");
        return new DataSourceConfig("iceberg_lake", DataSourceConfig.Type.ICEBERG)
                .setJdbcUrl("jdbc:hive2://hive:10000")
                .setDialect(SqlDialect.HIVE)
                .setProperties(props);
    }

    private static DataSourceConfig dorisConfig() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("stats.doris_olap.orders.rowCount", "5000000");
        props.put("stats.doris_olap.orders.tabletCount", "64");
        props.put("stats.doris_olap.orders.rowSizeBytes", "50");
        props.put("stats.doris_olap.orders.columnCardinalities", "id:5000000,dt:365,category:100");
        return new DataSourceConfig("doris_olap", DataSourceConfig.Type.DORIS)
                .setJdbcUrl("jdbc:mysql://doris-fe:9030")
                .setDialect(SqlDialect.DORIS)
                .setProperties(props);
    }

    private static DataSourceConfig trinoConfig() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("workerCount", "20");
        props.put("stats.trino_hive.orders.rowCount", "2000000");
        return new DataSourceConfig("trino_hive", DataSourceConfig.Type.TRINO)
                .setJdbcUrl("jdbc:trino://trino:8080")
                .setDialect(SqlDialect.TRINO)
                .setProperties(props);
    }

    private static DataSourceConfig iotdbConfig() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("stats.iotdb_ts.devices.deviceCount", "1000");
        props.put("stats.iotdb_ts.devices.sensorCount", "50");
        props.put("stats.iotdb_ts.devices.timePointCount", "100");
        return new DataSourceConfig("iotdb_ts", DataSourceConfig.Type.IOTDB)
                .setEndpoint("http://iotdb:18080")
                .setProperties(props);
    }

    private static DataSourceConfig esConfig() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("indices", "es_orders,es_users,es_products");
        props.put("stats.es_search.es_orders.rowCount", "100000");
        props.put("stats.es_search.es_orders.shardCount", "5");
        return new DataSourceConfig("es_search", DataSourceConfig.Type.ELASTICSEARCH)
                .setEndpoint("http://es:9200")
                .setProperties(props);
    }

    private static BaseAdapter.PushDownContext defaultCtx(SqlDialect dialect) {
        return new BaseAdapter.PushDownContext(
                new ArrayList<>(), dialect, 1.0, 10.0, 100.0);
    }

    // ===================== TableStatistics 测试 =====================

    @Nested
    @DisplayName("TableStatistics 表统计信息")
    class TableStatisticsTest {

        @Test
        @DisplayName("构造统计信息 + 默认值")
        void testConstruction() {
            Map<String, Long> cards = new LinkedHashMap<>();
            cards.put("id", 1000L);
            cards.put("name", 100L);
            TableStatistics stats = new TableStatistics(10000, cards, 200, 10);

            assertEquals(10000, stats.getRowCount());
            assertEquals(200, stats.getAverageRowSizeBytes());
            assertEquals(10, stats.getPartitionCount());
            assertEquals(1000, stats.getColumnCardinality("id"));
            assertEquals(100, stats.getColumnCardinality("name"));
        }

        @Test
        @DisplayName("非法参数回退默认值")
        void testDefaultFallback() {
            TableStatistics stats = new TableStatistics(0, null, 0, 0);
            assertEquals(TableStatistics.DEFAULT_ROW_COUNT, stats.getRowCount());
            assertEquals(TableStatistics.DEFAULT_ROW_SIZE_BYTES, stats.getAverageRowSizeBytes());
            assertEquals(1, stats.getPartitionCount());
        }

        @Test
        @DisplayName("defaultStats 静态工厂")
        void testDefaultStats() {
            TableStatistics stats = TableStatistics.defaultStats();
            assertEquals(TableStatistics.DEFAULT_ROW_COUNT, stats.getRowCount());
            assertEquals(TableStatistics.DEFAULT_ROW_SIZE_BYTES, stats.getAverageRowSizeBytes());
        }

        @Test
        @DisplayName("ofRows 静态工厂")
        void testOfRows() {
            TableStatistics stats = TableStatistics.ofRows(50000);
            assertEquals(50000, stats.getRowCount());
        }

        @Test
        @DisplayName("等值选择率 = 1/列基数")
        void testEqualitySelectivity() {
            Map<String, Long> cards = new LinkedHashMap<>();
            cards.put("id", 1000L);
            TableStatistics stats = TableStatistics.of(10000, cards);
            assertEquals(0.001, stats.equalitySelectivity("id"), 0.0001);
            assertEquals(1.0 / TableStatistics.DEFAULT_COLUMN_CARDINALITY,
                    stats.equalitySelectivity("unknown"), 0.0001);
        }

        @Test
        @DisplayName("范围选择率默认 0.1")
        void testRangeSelectivity() {
            TableStatistics stats = TableStatistics.defaultStats();
            assertEquals(0.1, stats.rangeSelectivity(), 0.001);
        }

        @Test
        @DisplayName("总数据大小 = 行数 × 行大小")
        void testTotalSizeBytes() {
            TableStatistics stats = new TableStatistics(1000, null, 100, 1);
            assertEquals(100000, stats.totalSizeBytes());
        }

        @Test
        @DisplayName("equals 与 hashCode")
        void testEqualsHashCode() {
            Map<String, Long> cards = new LinkedHashMap<>();
            cards.put("id", 100L);
            TableStatistics s1 = new TableStatistics(1000, cards, 100, 1);
            TableStatistics s2 = new TableStatistics(1000, cards, 100, 1);
            TableStatistics s3 = new TableStatistics(2000, cards, 100, 1);

            assertEquals(s1, s2);
            assertEquals(s1.hashCode(), s2.hashCode());
            assertNotEquals(s1, s3);
        }
    }

    // ===================== IcebergAdapterImpl 测试 =====================

    @Nested
    @DisplayName("IcebergAdapterImpl Iceberg 适配器")
    class IcebergAdapterImplTest {

        @Test
        @DisplayName("getAdapterType 返回 ICEBERG")
        void testAdapterType() {
            assertEquals(DataSourceConfig.Type.ICEBERG,
                    new IcebergAdapterImpl(icebergConfig()).getAdapterType());
        }

        @Test
        @DisplayName("getDialect 返回 HIVE")
        void testDialect() {
            assertEquals(SqlDialect.HIVE,
                    new IcebergAdapterImpl(icebergConfig()).getDialect());
        }

        @Test
        @DisplayName("toRel 构造 TABLE_SCAN")
        void testToRel() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            CustomRelNode rel = adapter.toRel("iceberg_lake.orders",
                    Arrays.asList("id", "amount"));
            assertEquals(CustomRelNode.Op.TABLE_SCAN, rel.getOp());
            assertEquals("iceberg_lake.orders", rel.getTableName());
            assertEquals("iceberg_lake", rel.getSourceName());
            assertEquals(Arrays.asList("id", "amount"), rel.getProjects());
        }

        @Test
        @DisplayName("toRel 空列 → 全表扫描")
        void testToRelEmptyColumns() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            CustomRelNode rel = adapter.toRel("iceberg_lake.orders", null);
            assertTrue(rel.getProjects().isEmpty());
        }

        @Test
        @DisplayName("pushDown 生成 HIVE SQL 含表名与谓词")
        void testPushDown() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            CustomRelNode scan = adapter.toRel("iceberg_lake.orders", null);
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition("amount > 100");
            filter.addChild(scan);

            BaseAdapter.PushDownResult result = adapter.pushDown(filter, defaultCtx(SqlDialect.HIVE));
            assertTrue(result.isSuccess());
            assertNotNull(result.getPushedSql());
            assertTrue(result.getPushedSql().contains("iceberg_lake.orders"));
            assertTrue(result.getPushedSql().contains("amount > 100"));
            assertTrue(result.getPushedSql().startsWith("SELECT"));
        }

        @Test
        @DisplayName("pushDown 跨源返回失败")
        void testPushDownFederated() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            CustomRelNode left = adapter.toRel("iceberg_lake.orders", null);
            CustomRelNode right = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                    .setTableName("doris_olap.users")
                    .setSourceName("doris_olap");
            CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN);
            join.addChild(left);
            join.addChild(right);

            BaseAdapter.PushDownResult result = adapter.pushDown(join, defaultCtx(SqlDialect.HIVE));
            assertFalse(result.isSuccess());
            assertNotNull(result.getFailureReason());
        }

        @Test
        @DisplayName("pushDown 禁用下推返回失败")
        void testPushDownDisabled() {
            DataSourceConfig config = icebergConfig().setPushDownEnabled(false);
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(config);
            CustomRelNode scan = adapter.toRel("iceberg_lake.orders", null);

            BaseAdapter.PushDownResult result = adapter.pushDown(scan, defaultCtx(SqlDialect.HIVE));
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("costEstimate 基于 Iceberg 表统计")
        void testCostEstimate() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            CustomRelNode scan = adapter.toRel("iceberg_lake.orders", null);
            BaseAdapter.Cost cost = adapter.costEstimate(scan);

            assertTrue(cost.getRows() > 0);
            assertTrue(cost.getCpuCost() > 0);
            assertTrue(cost.getIoCost() > 0);
            assertTrue(cost.getNetworkCost() > 0);
            // Iceberg IO Cost 因子(8.0)高于 CPU Cost 因子(0.5)，反映数据湖 IO 昂贵
            // IO Cost = (rows × rowSize / 64KB) × 8.0，CPU Cost = rows × 0.5
            assertTrue(cost.total() > 0);
        }

        @Test
        @DisplayName("costEstimate 含 Filter 选择率降低行数")
        void testCostEstimateWithFilter() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            CustomRelNode scan = adapter.toRel("iceberg_lake.orders", null);
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition("amount > 100");
            filter.addChild(scan);

            BaseAdapter.Cost scanCost = adapter.costEstimate(scan);
            BaseAdapter.Cost filterCost = adapter.costEstimate(filter);
            // 范围谓词选择率 0.1，过滤后行数应减少
            assertTrue(filterCost.getRows() < scanCost.getRows());
        }

        @Test
        @DisplayName("costEstimate null 返回零 Cost")
        void testCostEstimateNull() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            BaseAdapter.Cost cost = adapter.costEstimate(null);
            assertEquals(0, cost.total());
        }

        @Test
        @DisplayName("prunePartitions 范围谓词展开为日期列表")
        void testPrunePartitionsRange() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            List<String> partitions = adapter.prunePartitions("iceberg_lake.orders",
                    "dt >= '2024-01-01' AND dt < '2024-01-04'");
            assertEquals(3, partitions.size());
            assertEquals("2024-01-01", partitions.get(0));
            assertEquals("2024-01-02", partitions.get(1));
            assertEquals("2024-01-03", partitions.get(2));
        }

        @Test
        @DisplayName("prunePartitions 等值谓词")
        void testPrunePartitionsEquality() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            List<String> partitions = adapter.prunePartitions("iceberg_lake.orders",
                    "dt = '2024-01-15'");
            assertEquals(1, partitions.size());
            assertEquals("2024-01-15", partitions.get(0));
        }

        @Test
        @DisplayName("prunePartitions 空过滤返回空列表")
        void testPrunePartitionsEmpty() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            assertTrue(adapter.prunePartitions("iceberg_lake.orders", null).isEmpty());
            assertTrue(adapter.prunePartitions("iceberg_lake.orders", "").isEmpty());
        }

        @Test
        @DisplayName("selectSnapshot 优先 snapshotId")
        void testSelectSnapshot() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            assertEquals(123L, adapter.selectSnapshot("t", 123L, null));
            assertEquals(456L, adapter.selectSnapshot("t", null, 456L));
            assertEquals(0L, adapter.selectSnapshot("t", null, null));
        }

        @Test
        @DisplayName("isPartitionColumn 默认分区列 dt")
        void testIsPartitionColumn() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            assertTrue(adapter.isPartitionColumn("t", "dt"));
            assertFalse(adapter.isPartitionColumn("t", "amount"));
            assertFalse(adapter.isPartitionColumn("t", null));
        }

        @Test
        @DisplayName("isPartitionColumn 自定义分区列")
        void testIsPartitionColumnCustom() {
            Map<String, String> props = new LinkedHashMap<>();
            props.put("partition-column", "event_date");
            DataSourceConfig config = new DataSourceConfig("iceberg", DataSourceConfig.Type.ICEBERG)
                    .setJdbcUrl("jdbc:hive2://h:10000")
                    .setProperties(props);
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(config);
            assertTrue(adapter.isPartitionColumn("t", "event_date"));
            assertFalse(adapter.isPartitionColumn("t", "dt"));
        }

        @Test
        @DisplayName("declarePartitionColumns + isPartitionColumn")
        void testDeclarePartitionColumns() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            adapter.declarePartitionColumns("t", new java.util.LinkedHashSet<>(Arrays.asList("dt", "region")));
            assertTrue(adapter.isPartitionColumn("t", "dt"));
            assertTrue(adapter.isPartitionColumn("t", "region"));
            assertFalse(adapter.isPartitionColumn("t", "amount"));
        }

        @Test
        @DisplayName("getSchemaVersion 默认 1")
        void testGetSchemaVersion() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            assertEquals(1, adapter.getSchemaVersion("t"));
            adapter.setSchemaVersion("t", 5);
            assertEquals(5, adapter.getSchemaVersion("t"));
        }

        @Test
        @DisplayName("非法类型抛异常")
        void testInvalidType() {
            DataSourceConfig wrong = new DataSourceConfig("x", DataSourceConfig.Type.DORIS)
                    .setJdbcUrl("jdbc:mysql://x");
            assertThrows(IllegalArgumentException.class, () -> new IcebergAdapterImpl(wrong));
        }
    }

    // ===================== DorisAdapterImpl 测试 =====================

    @Nested
    @DisplayName("DorisAdapterImpl Doris 适配器")
    class DorisAdapterImplTest {

        @Test
        @DisplayName("getAdapterType 返回 DORIS")
        void testAdapterType() {
            assertEquals(DataSourceConfig.Type.DORIS,
                    new DorisAdapterImpl(dorisConfig()).getAdapterType());
        }

        @Test
        @DisplayName("getDialect 返回 DORIS")
        void testDialect() {
            assertEquals(SqlDialect.DORIS, new DorisAdapterImpl(dorisConfig()).getDialect());
        }

        @Test
        @DisplayName("toRel 构造 TABLE_SCAN")
        void testToRel() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            CustomRelNode rel = adapter.toRel("doris_olap.orders", Arrays.asList("id", "amount"));
            assertEquals(CustomRelNode.Op.TABLE_SCAN, rel.getOp());
            assertEquals("doris_olap.orders", rel.getTableName());
            assertEquals("doris_olap", rel.getSourceName());
        }

        @Test
        @DisplayName("pushDown 生成 DORIS SQL")
        void testPushDown() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            CustomRelNode scan = adapter.toRel("doris_olap.orders", null);
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition("amount > 100");
            filter.addChild(scan);

            BaseAdapter.PushDownResult result = adapter.pushDown(filter, defaultCtx(SqlDialect.DORIS));
            assertTrue(result.isSuccess());
            assertTrue(result.getPushedSql().contains("doris_olap.orders"));
            assertTrue(result.getPushedSql().contains("amount > 100"));
        }

        @Test
        @DisplayName("costEstimate 基于 Doris 表统计")
        void testCostEstimate() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            CustomRelNode scan = adapter.toRel("doris_olap.orders", null);
            BaseAdapter.Cost cost = adapter.costEstimate(scan);

            assertTrue(cost.getRows() > 0);
            // Doris CPU 因子 0.1，IO 因子 1.0
            assertTrue(cost.getCpuCost() > 0);
            assertTrue(cost.getIoCost() > 0);
        }

        @Test
        @DisplayName("costEstimate 等值谓词使用列基数降低行数")
        void testCostEstimateEquality() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            CustomRelNode scan = adapter.toRel("doris_olap.orders", null);
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition("id = 12345");
            filter.addChild(scan);

            BaseAdapter.Cost scanCost = adapter.costEstimate(scan);
            BaseAdapter.Cost filterCost = adapter.costEstimate(filter);
            // 等值谓词应降低估算行数（选择率有 0.0001 下限，但仍应减少行数）
            assertTrue(filterCost.getRows() < scanCost.getRows(),
                    "等值谓词应降低行数: filter=" + filterCost.getRows() + " vs scan=" + scanCost.getRows());
        }

        @Test
        @DisplayName("routeMaterializedView 默认加 _mv 后缀")
        void testRouteMvDefault() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            String mv = adapter.routeMaterializedView("doris_olap.orders",
                    Arrays.asList("dt"), Arrays.asList("sum(amount)"));
            assertEquals("doris_olap.orders_mv", mv);
        }

        @Test
        @DisplayName("routeMaterializedView 已注册物化视图")
        void testRouteMvRegistered() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            adapter.registerMaterializedView("doris_olap.orders",
                    Arrays.asList("dt"), Arrays.asList("sum(amount)"), "doris_olap.mv_orders_daily");
            String mv = adapter.routeMaterializedView("doris_olap.orders",
                    Arrays.asList("dt"), Arrays.asList("sum(amount)"));
            assertEquals("doris_olap.mv_orders_daily", mv);
        }

        @Test
        @DisplayName("routeMaterializedView 无聚合返回原表")
        void testRouteMvNoAgg() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            String mv = adapter.routeMaterializedView("doris_olap.orders",
                    Collections.emptyList(), Collections.emptyList());
            assertEquals("doris_olap.orders", mv);
        }

        @Test
        @DisplayName("canColocateJoin 同源可 Colocate")
        void testCanColocateJoin() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            assertTrue(adapter.canColocateJoin("doris_olap.t1", "doris_olap.t2"));
            assertFalse(adapter.canColocateJoin("doris_olap.t1", "trino.t2"));
            assertFalse(adapter.canColocateJoin(null, "doris_olap.t2"));
        }

        @Test
        @DisplayName("canColocateJoin Colocate Group 不一致")
        void testCanColocateJoinGroup() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            adapter.declareColocateGroup("doris_olap.t1", "group_a");
            adapter.declareColocateGroup("doris_olap.t2", "group_b");
            assertFalse(adapter.canColocateJoin("doris_olap.t1", "doris_olap.t2"));

            adapter.declareColocateGroup("doris_olap.t3", "group_a");
            assertTrue(adapter.canColocateJoin("doris_olap.t1", "doris_olap.t3"));
        }

        @Test
        @DisplayName("getTabletCount 从配置读取")
        void testGetTabletCount() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            assertEquals(64, adapter.getTabletCount("doris_olap.orders"));
            assertEquals(64, adapter.getTabletCount("unknown_table")); // 默认
            adapter.setTabletCount("custom_table", 128);
            assertEquals(128, adapter.getTabletCount("custom_table"));
        }

        @Test
        @DisplayName("getEstimatedRowCount 从统计信息读取")
        void testGetEstimatedRowCount() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            assertEquals(5000000L, adapter.getEstimatedRowCount("doris_olap.orders"));
            adapter.setEstimatedRowCount("custom", 999L);
            assertEquals(999L, adapter.getEstimatedRowCount("custom"));
        }

        @Test
        @DisplayName("pushDown 含聚合下推到物化视图")
        void testPushDownAggregate() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            CustomRelNode scan = adapter.toRel("doris_olap.orders", null);
            CustomRelNode agg = CustomRelNode.of(CustomRelNode.Op.AGGREGATE)
                    .setProjects(Arrays.asList("dt"))
                    .setRemark("sum(amount)");
            agg.addChild(scan);

            BaseAdapter.PushDownResult result = adapter.pushDown(agg, defaultCtx(SqlDialect.DORIS));
            assertTrue(result.isSuccess());
            // 应路由到物化视图
            assertTrue(result.getPushedSql().contains("orders_mv"));
            assertTrue(result.getPushedSql().contains("GROUP BY"));
        }
    }

    // ===================== TrinoAdapterImpl 测试 =====================

    @Nested
    @DisplayName("TrinoAdapterImpl Trino 适配器")
    class TrinoAdapterImplTest {

        @Test
        @DisplayName("getAdapterType 返回 TRINO")
        void testAdapterType() {
            assertEquals(DataSourceConfig.Type.TRINO,
                    new TrinoAdapterImpl(trinoConfig()).getAdapterType());
        }

        @Test
        @DisplayName("getDialect 返回 TRINO")
        void testDialect() {
            assertEquals(SqlDialect.TRINO, new TrinoAdapterImpl(trinoConfig()).getDialect());
        }

        @Test
        @DisplayName("toRel + pushDown 生成 Trino SQL")
        void testToRelAndPushDown() {
            TrinoAdapterImpl adapter = new TrinoAdapterImpl(trinoConfig());
            CustomRelNode scan = adapter.toRel("hive.db.orders", Arrays.asList("id", "amount"));
            assertEquals("hive.db.orders", scan.getTableName());

            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition("amount > 100");
            filter.addChild(scan);

            BaseAdapter.PushDownResult result = adapter.pushDown(filter, defaultCtx(SqlDialect.TRINO));
            assertTrue(result.isSuccess());
            assertTrue(result.getPushedSql().contains("hive.db.orders"));
        }

        @Test
        @DisplayName("costEstimate 基于 Trino 表统计 + Worker 并行度")
        void testCostEstimate() {
            TrinoAdapterImpl adapter = new TrinoAdapterImpl(trinoConfig());
            CustomRelNode scan = adapter.toRel("trino_hive.orders", null);
            BaseAdapter.Cost cost = adapter.costEstimate(scan);

            assertTrue(cost.getRows() > 0);
            assertTrue(cost.getCpuCost() > 0);
        }

        @Test
        @DisplayName("getConnectorName 从三段式命名提取")
        void testGetConnectorName() {
            TrinoAdapterImpl adapter = new TrinoAdapterImpl(trinoConfig());
            assertEquals("hive", adapter.getConnectorName("hive.db.table"));
            assertEquals("iceberg", adapter.getConnectorName("iceberg.db.table"));
            assertEquals("mysql", adapter.getConnectorName("mysql.db.table"));
            // 未注册的 catalog 名即 connector 名（Trino 默认行为）
            assertEquals("unknown", adapter.getConnectorName("unknown"));
            assertEquals("default", adapter.getConnectorName(null));
        }

        @Test
        @DisplayName("registerConnector 自定义 Connector")
        void testRegisterConnector() {
            TrinoAdapterImpl adapter = new TrinoAdapterImpl(trinoConfig());
            adapter.registerConnector("delta", "delta");
            assertEquals("delta", adapter.getConnectorName("delta.db.table"));
        }

        @Test
        @DisplayName("supportsDynamicFiltering hive/iceberg 支持")
        void testSupportsDynamicFiltering() {
            TrinoAdapterImpl adapter = new TrinoAdapterImpl(trinoConfig());
            assertTrue(adapter.supportsDynamicFiltering("hive"));
            assertTrue(adapter.supportsDynamicFiltering("iceberg"));
            assertTrue(adapter.supportsDynamicFiltering("delta"));
            assertFalse(adapter.supportsDynamicFiltering("mysql"));
            assertFalse(adapter.supportsDynamicFiltering(null));
        }

        @Test
        @DisplayName("addDynamicFilterConnector 添加自定义支持")
        void testAddDynamicFilterConnector() {
            TrinoAdapterImpl adapter = new TrinoAdapterImpl(trinoConfig());
            assertFalse(adapter.supportsDynamicFiltering("kafka"));
            adapter.addDynamicFilterConnector("kafka");
            assertTrue(adapter.supportsDynamicFiltering("kafka"));
        }

        @Test
        @DisplayName("inlineCte 移除 WITH 子句")
        void testInlineCte() {
            TrinoAdapterImpl adapter = new TrinoAdapterImpl(trinoConfig());
            String inlined = adapter.inlineCte("WITH t AS (SELECT 1) SELECT * FROM t");
            assertNotNull(inlined);
            assertFalse(inlined.contains("WITH"));
        }

        @Test
        @DisplayName("inlineCte 无 CTE 返回原 SQL")
        void testInlineCteNoCte() {
            TrinoAdapterImpl adapter = new TrinoAdapterImpl(trinoConfig());
            String sql = "SELECT * FROM orders";
            assertEquals(sql, adapter.inlineCte(sql));
        }

        @Test
        @DisplayName("inlineCte null/空")
        void testInlineCteNull() {
            TrinoAdapterImpl adapter = new TrinoAdapterImpl(trinoConfig());
            assertNull(adapter.inlineCte(null));
            assertEquals("", adapter.inlineCte(""));
        }

        @Test
        @DisplayName("getWorkerCount 从配置读取")
        void testGetWorkerCount() {
            TrinoAdapterImpl adapter = new TrinoAdapterImpl(trinoConfig());
            assertEquals(20, adapter.getWorkerCount());
            adapter.setWorkerCount(50);
            assertEquals(50, adapter.getWorkerCount());
        }

        @Test
        @DisplayName("setWorkerCount 非法值回退 1")
        void testSetWorkerCountInvalid() {
            TrinoAdapterImpl adapter = new TrinoAdapterImpl(trinoConfig());
            adapter.setWorkerCount(0);
            assertEquals(1, adapter.getWorkerCount());
            adapter.setWorkerCount(-5);
            assertEquals(1, adapter.getWorkerCount());
        }
    }

    // ===================== IoTDBAdapterImpl 测试 =====================

    @Nested
    @DisplayName("IoTDBAdapterImpl IoTDB 适配器")
    class IoTDBAdapterImplTest {

        @Test
        @DisplayName("getAdapterType 返回 IOTDB")
        void testAdapterType() {
            assertEquals(DataSourceConfig.Type.IOTDB,
                    new IoTDBAdapterImpl(iotdbConfig()).getAdapterType());
        }

        @Test
        @DisplayName("getDialect 返回 ANSI")
        void testDialect() {
            assertEquals(SqlDialect.ANSI, new IoTDBAdapterImpl(iotdbConfig()).getDialect());
        }

        @Test
        @DisplayName("toRel + pushDown 生成 IoTDB 时序 SQL")
        void testToRelAndPushDown() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            CustomRelNode scan = adapter.toRel("iotdb_ts.devices", Arrays.asList("s1", "s2"));
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition("time >= '2024-01-01' AND time < '2024-02-01'");
            filter.addChild(scan);

            BaseAdapter.PushDownResult result = adapter.pushDown(filter, defaultCtx(SqlDialect.ANSI));
            assertTrue(result.isSuccess());
            // 应转为 IoTDB 查询路径
            assertTrue(result.getPushedSql().contains("root."));
        }

        @Test
        @DisplayName("costEstimate 基于 IoTDB 设备×测点×时间点")
        void testCostEstimate() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            CustomRelNode scan = adapter.toRel("iotdb_ts.devices", null);
            BaseAdapter.Cost cost = adapter.costEstimate(scan);
            // 1000 设备 × 50 测点 × 100 时间点 = 5,000,000 行
            assertTrue(cost.getRows() > 0);
        }

        @Test
        @DisplayName("pushDownTimeRange 双边范围")
        void testPushDownTimeRange() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            String range = adapter.pushDownTimeRange(
                    "time >= '2024-01-01' AND time < '2024-02-01'");
            assertNotNull(range);
            assertTrue(range.contains("2024-01-01"));
            assertTrue(range.contains("2024-02-01"));
        }

        @Test
        @DisplayName("pushDownTimeRange 单边范围")
        void testPushDownTimeRangeSingle() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            String range = adapter.pushDownTimeRange("time >= '2024-01-01'");
            assertNotNull(range);
            assertTrue(range.contains("2024-01-01"));
        }

        @Test
        @DisplayName("pushDownTimeRange 空/无效")
        void testPushDownTimeRangeEmpty() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            assertEquals("", adapter.pushDownTimeRange(null));
            assertEquals("", adapter.pushDownTimeRange(""));
            assertEquals("", adapter.pushDownTimeRange("invalid"));
        }

        @Test
        @DisplayName("pushDownDownsampling 生成降采样查询")
        void testPushDownDownsampling() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            String ds = adapter.pushDownDownsampling("mean", "time", "1h");
            assertNotNull(ds);
            assertTrue(ds.contains("mean"));
            assertTrue(ds.contains("GROUP BY"));
            assertTrue(ds.contains("1h"));
        }

        @Test
        @DisplayName("pushDownDownsampling 空参数")
        void testPushDownDownsamplingEmpty() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            assertEquals("", adapter.pushDownDownsampling(null, "time", "1h"));
            assertEquals("", adapter.pushDownDownsampling("mean", null, "1h"));
            assertEquals("", adapter.pushDownDownsampling("mean", "time", null));
        }

        @Test
        @DisplayName("toQueryPath 设备路径谓词")
        void testToQueryPathDevice() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            String path = adapter.toQueryPath("device = 'root.sg.d1'");
            assertEquals("root.sg.d1.*", path);
        }

        @Test
        @DisplayName("toQueryPath 直接路径形式")
        void testToQueryPathDirect() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            assertEquals("root.sg.d1.*", adapter.toQueryPath("root.sg.d1"));
        }

        @Test
        @DisplayName("toQueryPath 表名形式转换")
        void testToQueryPathTable() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            String path = adapter.toQueryPath("sg.d1");
            assertTrue(path.startsWith("root."));
            assertTrue(path.endsWith(".*"));
        }

        @Test
        @DisplayName("toQueryPath 空返回通配")
        void testToQueryPathEmpty() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            assertEquals("root.**", adapter.toQueryPath(null));
            assertEquals("root.**", adapter.toQueryPath(""));
        }

        @Test
        @DisplayName("supportsDownsampling 支持的聚合函数")
        void testSupportsDownsampling() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            assertTrue(adapter.supportsDownsampling("mean"));
            assertTrue(adapter.supportsDownsampling("max"));
            assertTrue(adapter.supportsDownsampling("min"));
            assertTrue(adapter.supportsDownsampling("sum"));
            assertTrue(adapter.supportsDownsampling("count"));
            assertTrue(adapter.supportsDownsampling("avg"));
            assertFalse(adapter.supportsDownsampling("unknown"));
            assertFalse(adapter.supportsDownsampling(null));
        }

        @Test
        @DisplayName("addDownsamplingFunction 自定义支持")
        void testAddDownsamplingFunction() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            assertFalse(adapter.supportsDownsampling("percentile"));
            adapter.addDownsamplingFunction("percentile");
            assertTrue(adapter.supportsDownsampling("percentile"));
        }

        @Test
        @DisplayName("canPushDown 不支持 Join 下推")
        void testCanPushDownJoin() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            CustomRelNode left = adapter.toRel("iotdb_ts.d1", null);
            CustomRelNode right = adapter.toRel("iotdb_ts.d2", null);
            CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN);
            join.addChild(left);
            join.addChild(right);
            assertFalse(adapter.canPushDown(join));
        }

        @Test
        @DisplayName("canPushDown 不支持 LIKE 谓词")
        void testCanPushDownLike() {
            IoTDBAdapterImpl adapter = new IoTDBAdapterImpl(iotdbConfig());
            CustomRelNode scan = adapter.toRel("iotdb_ts.d1", null);
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition("name LIKE '%test%'");
            filter.addChild(scan);
            assertFalse(adapter.canPushDown(filter));
        }
    }

    // ===================== ElasticsearchAdapterImpl 测试 =====================

    @Nested
    @DisplayName("ElasticsearchAdapterImpl ES 适配器")
    class ElasticsearchAdapterImplTest {

        @Test
        @DisplayName("getAdapterType 返回 ELASTICSEARCH")
        void testAdapterType() {
            assertEquals(DataSourceConfig.Type.ELASTICSEARCH,
                    new ElasticsearchAdapterImpl(esConfig()).getAdapterType());
        }

        @Test
        @DisplayName("toRel + pushDown 生成 ES DSL")
        void testToRelAndPushDown() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            CustomRelNode scan = adapter.toRel("es_orders", null);
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition("age > 18");
            filter.addChild(scan);

            BaseAdapter.PushDownResult result = adapter.pushDown(filter, defaultCtx(SqlDialect.ANSI));
            assertTrue(result.isSuccess());
            assertTrue(result.getPushedSql().contains("GET /es_orders/_search"));
            assertTrue(result.getPushedSql().contains("\"query\""));
        }

        @Test
        @DisplayName("costEstimate 基于 ES 表统计")
        void testCostEstimate() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            CustomRelNode scan = adapter.toRel("es_orders", null);
            BaseAdapter.Cost cost = adapter.costEstimate(scan);
            assertTrue(cost.getRows() > 0);
            assertTrue(cost.getCpuCost() > 0);
        }

        @Test
        @DisplayName("toQueryDsl 等值谓词 → term")
        void testToQueryDslEquality() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toQueryDsl("age = 18");
            assertTrue(dsl.contains("\"term\""));
            assertTrue(dsl.contains("\"age\""));
            assertTrue(dsl.contains("18"));
        }

        @Test
        @DisplayName("toQueryDsl 范围谓词 → range")
        void testToQueryDslRange() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toQueryDsl("age > 18");
            assertTrue(dsl.contains("\"range\""));
            assertTrue(dsl.contains("\"gt\""));
            assertTrue(dsl.contains("18"));
        }

        @Test
        @DisplayName("toQueryDsl LIKE 谓词 → wildcard")
        void testToQueryDslLike() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toQueryDsl("name LIKE '%张%'");
            assertTrue(dsl.contains("\"wildcard\""));
            assertTrue(dsl.contains("*张*"));
        }

        @Test
        @DisplayName("toQueryDsl IN 谓词 → terms")
        void testToQueryDslIn() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toQueryDsl("id IN (1, 2, 3)");
            assertTrue(dsl.contains("\"terms\""));
            assertTrue(dsl.contains("\"id\""));
        }

        @Test
        @DisplayName("toQueryDsl IS NULL → must_not exists")
        void testToQueryDslIsNull() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toQueryDsl("name IS NULL");
            assertTrue(dsl.contains("must_not"));
            assertTrue(dsl.contains("exists"));
        }

        @Test
        @DisplayName("toQueryDsl IS NOT NULL → exists")
        void testToQueryDslIsNotNull() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toQueryDsl("name IS NOT NULL");
            assertTrue(dsl.contains("exists"));
        }

        @Test
        @DisplayName("toQueryDsl MATCH → match query")
        void testToQueryDslMatch() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toQueryDsl("MATCH(name, '张三')");
            assertTrue(dsl.contains("\"match\""));
            assertTrue(dsl.contains("张三"));
        }

        @Test
        @DisplayName("toQueryDsl AND 连接 → bool.must")
        void testToQueryDslAnd() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toQueryDsl("age > 18 AND status = 'active'");
            assertTrue(dsl.contains("\"bool\""));
            assertTrue(dsl.contains("\"must\""));
        }

        @Test
        @DisplayName("toQueryDsl OR 连接 → bool.should")
        void testToQueryDslOr() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toQueryDsl("age > 18 OR age < 5");
            assertTrue(dsl.contains("\"bool\""));
            assertTrue(dsl.contains("\"should\""));
        }

        @Test
        @DisplayName("toQueryDsl 空 → match_all")
        void testToQueryDslEmpty() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toQueryDsl(null);
            assertTrue(dsl.contains("match_all"));
            dsl = adapter.toQueryDsl("");
            assertTrue(dsl.contains("match_all"));
        }

        @Test
        @DisplayName("toAggregationDsl GROUP BY + 聚合函数")
        void testToAggregationDsl() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toAggregationDsl(
                    Arrays.asList("category"), Arrays.asList("sum(amount)"));
            assertTrue(dsl.contains("by_category"));
            assertTrue(dsl.contains("terms"));
            assertTrue(dsl.contains("sum"));
        }

        @Test
        @DisplayName("toAggregationDsl 仅聚合函数无 GROUP BY")
        void testToAggregationDslNoGroupBy() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toAggregationDsl(
                    Collections.emptyList(), Arrays.asList("count(*)"));
            assertTrue(dsl.contains("count"));
        }

        @Test
        @DisplayName("toAggregationDsl 空返回 {}")
        void testToAggregationDslEmpty() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            assertEquals("{}", adapter.toAggregationDsl(null, null));
        }

        @Test
        @DisplayName("toSortDsl 排序键转换")
        void testToSortDsl() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toSortDsl(Arrays.asList("age DESC", "name ASC"));
            assertTrue(dsl.contains("\"age\""));
            assertTrue(dsl.contains("desc"));
            assertTrue(dsl.contains("\"name\""));
            assertTrue(dsl.contains("asc"));
        }

        @Test
        @DisplayName("toSortDsl 空返回 []")
        void testToSortDslEmpty() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            assertEquals("[]", adapter.toSortDsl(null));
            assertEquals("[]", adapter.toSortDsl(Collections.emptyList()));
        }

        @Test
        @DisplayName("toPaginationDsl from/size")
        void testToPaginationDsl() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toPaginationDsl(100, 200);
            assertTrue(dsl.contains("\"from\":200"));
            assertTrue(dsl.contains("\"size\":100"));
        }

        @Test
        @DisplayName("toPaginationDsl 负数回退 0")
        void testToPaginationDslNegative() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            String dsl = adapter.toPaginationDsl(-10, -20);
            assertTrue(dsl.contains("\"from\":0"));
            assertTrue(dsl.contains("\"size\":0"));
        }

        @Test
        @DisplayName("isIndexAvailable 从配置读取")
        void testIsIndexAvailable() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            assertTrue(adapter.isIndexAvailable("es_orders"));
            assertTrue(adapter.isIndexAvailable("es_users"));
            assertTrue(adapter.isIndexAvailable("es_products"));
            assertFalse(adapter.isIndexAvailable("nonexistent"));
            assertFalse(adapter.isIndexAvailable(null));
        }

        @Test
        @DisplayName("declareAvailableIndices 声明新索引")
        void testDeclareAvailableIndices() {
            ElasticsearchAdapterImpl adapter = new ElasticsearchAdapterImpl(esConfig());
            assertFalse(adapter.isIndexAvailable("new_index"));
            adapter.declareAvailableIndices("new_index");
            assertTrue(adapter.isIndexAvailable("new_index"));
        }
    }

    // ===================== AdapterRegistry 测试 =====================

    @Nested
    @DisplayName("AdapterRegistry 适配器注册中心")
    class AdapterRegistryTest {

        @Test
        @DisplayName("createAdapter 按类型创建适配器")
        void testCreateAdapter() {
            assertTrue(AdapterRegistry.createAdapter(icebergConfig()) instanceof IcebergAdapterImpl);
            assertTrue(AdapterRegistry.createAdapter(dorisConfig()) instanceof DorisAdapterImpl);
            assertTrue(AdapterRegistry.createAdapter(trinoConfig()) instanceof TrinoAdapterImpl);
            assertTrue(AdapterRegistry.createAdapter(iotdbConfig()) instanceof IoTDBAdapterImpl);
            assertTrue(AdapterRegistry.createAdapter(esConfig()) instanceof ElasticsearchAdapterImpl);
        }

        @Test
        @DisplayName("createAdapter 非法配置抛异常")
        void testCreateAdapterInvalid() {
            DataSourceConfig invalid = new DataSourceConfig("x", DataSourceConfig.Type.ICEBERG);
            assertThrows(IllegalArgumentException.class, () -> AdapterRegistry.createAdapter(invalid));
        }

        @Test
        @DisplayName("createAdapter null 抛异常")
        void testCreateAdapterNull() {
            assertThrows(NullPointerException.class, () -> AdapterRegistry.createAdapter(null));
        }

        @Test
        @DisplayName("从 OptimizerConfig 构造注册中心")
        void testFromOptimizerConfig() {
            OptimizerConfig config = new OptimizerConfig()
                    .setDataSources(Arrays.asList(
                            icebergConfig(), dorisConfig(), trinoConfig(),
                            iotdbConfig(), esConfig()));
            AdapterRegistry registry = new AdapterRegistry(config);

            assertEquals(5, registry.size());
            assertTrue(registry.contains("iceberg_lake"));
            assertTrue(registry.contains("doris_olap"));
            assertTrue(registry.contains("trino_hive"));
            assertTrue(registry.contains("iotdb_ts"));
            assertTrue(registry.contains("es_search"));
        }

        @Test
        @DisplayName("getAdapter 按名查找")
        void testGetAdapter() {
            OptimizerConfig config = new OptimizerConfig()
                    .setDataSources(Arrays.asList(icebergConfig(), dorisConfig()));
            AdapterRegistry registry = new AdapterRegistry(config);

            BaseAdapter iceberg = registry.getAdapter("iceberg_lake");
            assertNotNull(iceberg);
            assertEquals(DataSourceConfig.Type.ICEBERG, iceberg.getAdapterType());

            assertNull(registry.getAdapter("nonexistent"));
        }

        @Test
        @DisplayName("getAdaptersByType 按类型查找")
        void testGetAdaptersByType() {
            OptimizerConfig config = new OptimizerConfig()
                    .setDataSources(Arrays.asList(icebergConfig(), dorisConfig()));
            AdapterRegistry registry = new AdapterRegistry(config);

            List<BaseAdapter> icebergAdapters = registry.getAdaptersByType(DataSourceConfig.Type.ICEBERG);
            assertEquals(1, icebergAdapters.size());

            List<BaseAdapter> trinoAdapters = registry.getAdaptersByType(DataSourceConfig.Type.TRINO);
            assertTrue(trinoAdapters.isEmpty());
        }

        @Test
        @DisplayName("register 手动注册适配器")
        void testRegister() {
            AdapterRegistry registry = new AdapterRegistry();
            registry.register(new IcebergAdapterImpl(icebergConfig()));
            assertEquals(1, registry.size());
            assertTrue(registry.contains("iceberg_lake"));
        }

        @Test
        @DisplayName("registerAll 批量注册")
        void testRegisterAll() {
            AdapterRegistry registry = new AdapterRegistry();
            registry.registerAll(Arrays.asList(icebergConfig(), dorisConfig()));
            assertEquals(2, registry.size());
        }

        @Test
        @DisplayName("registerAll 跳过非法配置")
        void testRegisterAllSkipInvalid() {
            AdapterRegistry registry = new AdapterRegistry();
            DataSourceConfig invalid = new DataSourceConfig("invalid", DataSourceConfig.Type.ICEBERG);
            registry.registerAll(Arrays.asList(icebergConfig(), invalid));
            assertEquals(1, registry.size());
        }

        @Test
        @DisplayName("remove 移除适配器")
        void testRemove() {
            AdapterRegistry registry = new AdapterRegistry(
                    new OptimizerConfig().setDataSources(Arrays.asList(icebergConfig())));
            assertNotNull(registry.remove("iceberg_lake"));
            assertEquals(0, registry.size());
            assertNull(registry.remove("nonexistent"));
        }

        @Test
        @DisplayName("clear 清空")
        void testClear() {
            AdapterRegistry registry = new AdapterRegistry(
                    new OptimizerConfig().setDataSources(Arrays.asList(icebergConfig(), dorisConfig())));
            registry.clear();
            assertEquals(0, registry.size());
        }

        @Test
        @DisplayName("getAllAdapters 不可变视图")
        void testGetAllAdapters() {
            AdapterRegistry registry = new AdapterRegistry(
                    new OptimizerConfig().setDataSources(Arrays.asList(icebergConfig())));
            Map<String, BaseAdapter> all = registry.getAllAdapters();
            assertEquals(1, all.size());
            assertThrows(UnsupportedOperationException.class, () -> all.put("x", null));
        }

        @Test
        @DisplayName("空配置构造空注册中心")
        void testEmptyConfig() {
            AdapterRegistry registry = new AdapterRegistry(new OptimizerConfig());
            assertEquals(0, registry.size());
        }

        @Test
        @DisplayName("registerAll null 不抛异常")
        void testRegisterAllNull() {
            AdapterRegistry registry = new AdapterRegistry();
            registry.registerAll(null);
            assertEquals(0, registry.size());
        }

        @Test
        @DisplayName("toString 含数量与数据源名")
        void testToString() {
            AdapterRegistry registry = new AdapterRegistry(
                    new OptimizerConfig().setDataSources(Arrays.asList(icebergConfig())));
            String s = registry.toString();
            assertTrue(s.contains("size=1"));
            assertTrue(s.contains("iceberg_lake"));
        }
    }

    // ===================== AbstractBaseAdapter 补充分支测试 =====================

    @Nested
    @DisplayName("AbstractBaseAdapter 分支覆盖")
    class AbstractBaseAdapterBranchTest {

        @Test
        @DisplayName("costEstimate 含 IN 谓词选择率")
        void testCostEstimateInPredicate() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            CustomRelNode scan = adapter.toRel("doris_olap.orders", null);
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition("id IN (1, 2, 3)");
            filter.addChild(scan);
            BaseAdapter.Cost cost = adapter.costEstimate(filter);
            assertTrue(cost.getRows() > 0);
        }

        @Test
        @DisplayName("costEstimate 含 LIKE 谓词选择率")
        void testCostEstimateLikePredicate() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            CustomRelNode scan = adapter.toRel("doris_olap.orders", null);
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition("name LIKE '%test%'");
            filter.addChild(scan);
            BaseAdapter.Cost cost = adapter.costEstimate(filter);
            assertTrue(cost.getRows() > 0);
        }

        @Test
        @DisplayName("costEstimate 含 IS NULL 谓词选择率")
        void testCostEstimateIsNullPredicate() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            CustomRelNode scan = adapter.toRel("doris_olap.orders", null);
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition("name IS NULL");
            filter.addChild(scan);
            BaseAdapter.Cost cost = adapter.costEstimate(filter);
            assertTrue(cost.getRows() > 0);
        }

        @Test
        @DisplayName("costEstimate 含多 AND 谓词")
        void testCostEstimateMultipleAnd() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            CustomRelNode scan = adapter.toRel("doris_olap.orders", null);
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition("id = 100 AND amount > 50 AND status IN ('a', 'b')");
            filter.addChild(scan);
            BaseAdapter.Cost cost = adapter.costEstimate(filter);
            assertTrue(cost.getRows() > 0);
        }

        @Test
        @DisplayName("costEstimate 无表名返回默认统计")
        void testCostEstimateNoTable() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            CustomRelNode values = CustomRelNode.of(CustomRelNode.Op.VALUES);
            BaseAdapter.Cost cost = adapter.costEstimate(values);
            assertTrue(cost.getRows() >= 0);
        }

        @Test
        @DisplayName("costEstimate 禁用 Cost 估算返回零")
        void testCostEstimateDisabled() {
            DataSourceConfig config = dorisConfig().setCostEstimationEnabled(false);
            DorisAdapterImpl adapter = new DorisAdapterImpl(config);
            CustomRelNode scan = adapter.toRel("doris_olap.orders", null);
            BaseAdapter.Cost cost = adapter.costEstimate(scan);
            assertEquals(0, cost.total());
        }

        @Test
        @DisplayName("pushDown 带投影列下推")
        void testPushDownWithProjects() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            CustomRelNode scan = adapter.toRel("iceberg_lake.orders", null);
            CustomRelNode project = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                    .setProjects(Arrays.asList("id", "amount"));
            project.addChild(scan);

            BaseAdapter.PushDownResult result = adapter.pushDown(project, defaultCtx(SqlDialect.HIVE));
            assertTrue(result.isSuccess());
            assertTrue(result.getPushedSql().contains("id, amount"));
        }

        @Test
        @DisplayName("getStatistics 缓存生效")
        void testStatisticsCache() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            TableStatistics s1 = adapter.getStatistics("doris_olap.orders");
            TableStatistics s2 = adapter.getStatistics("doris_olap.orders");
            assertSame(s1, s2);
        }

        @Test
        @DisplayName("getStatistics 空表名返回默认")
        void testStatisticsEmptyName() {
            DorisAdapterImpl adapter = new DorisAdapterImpl(dorisConfig());
            TableStatistics stats = adapter.getStatistics("");
            assertEquals(TableStatistics.DEFAULT_ROW_COUNT, stats.getRowCount());
        }

        @Test
        @DisplayName("toString 含类型与方言")
        void testToString() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            String s = adapter.toString();
            assertTrue(s.contains("ICEBERG"));
            assertTrue(s.contains("iceberg_lake"));
            assertTrue(s.contains("HIVE"));
        }

        @Test
        @DisplayName("canPushDown null 返回 false")
        void testCanPushDownNull() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            assertFalse(adapter.canPushDown(null));
        }

        @Test
        @DisplayName("pushDown null relNode 抛异常")
        void testPushDownNullRelNode() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            assertThrows(NullPointerException.class,
                    () -> adapter.pushDown(null, defaultCtx(SqlDialect.HIVE)));
        }

        @Test
        @DisplayName("pushDown null context 抛异常")
        void testPushDownNullContext() {
            IcebergAdapterImpl adapter = new IcebergAdapterImpl(icebergConfig());
            CustomRelNode scan = adapter.toRel("iceberg_lake.orders", null);
            assertThrows(NullPointerException.class,
                    () -> adapter.pushDown(scan, null));
        }
    }

    // ===================== 跨适配器一致性测试 =====================

    @Nested
    @DisplayName("跨适配器一致性")
    class CrossAdapterTest {

        @Test
        @DisplayName("所有适配器 toRel 返回 TABLE_SCAN")
        void testAllToRelTableScan() {
            List<AbstractBaseAdapter> adapters = Arrays.asList(
                    new IcebergAdapterImpl(icebergConfig()),
                    new DorisAdapterImpl(dorisConfig()),
                    new TrinoAdapterImpl(trinoConfig()),
                    new IoTDBAdapterImpl(iotdbConfig()),
                    new ElasticsearchAdapterImpl(esConfig()));

            for (AbstractBaseAdapter adapter : adapters) {
                CustomRelNode rel = adapter.toRel("test_table", Arrays.asList("a", "b"));
                assertEquals(CustomRelNode.Op.TABLE_SCAN, rel.getOp(),
                        adapter.getAdapterType() + " toRel 应返回 TABLE_SCAN");
                assertEquals("test_table", rel.getTableName());
                assertEquals(adapter.getDataSourceConfig().getName(), rel.getSourceName());
            }
        }

        @Test
        @DisplayName("所有适配器 costEstimate 返回非负 Cost")
        void testAllCostNonNegative() {
            List<AbstractBaseAdapter> adapters = Arrays.asList(
                    new IcebergAdapterImpl(icebergConfig()),
                    new DorisAdapterImpl(dorisConfig()),
                    new TrinoAdapterImpl(trinoConfig()),
                    new IoTDBAdapterImpl(iotdbConfig()),
                    new ElasticsearchAdapterImpl(esConfig()));

            for (AbstractBaseAdapter adapter : adapters) {
                CustomRelNode scan = adapter.toRel("test_table", null);
                BaseAdapter.Cost cost = adapter.costEstimate(scan);
                assertTrue(cost.getCpuCost() >= 0, adapter.getAdapterType() + " CPU Cost 应非负");
                assertTrue(cost.getIoCost() >= 0, adapter.getAdapterType() + " IO Cost 应非负");
                assertTrue(cost.getNetworkCost() >= 0, adapter.getAdapterType() + " Network Cost 应非负");
                assertTrue(cost.getRows() > 0, adapter.getAdapterType() + " 行数应 > 0");
            }
        }

        @Test
        @DisplayName("所有适配器 canPushDown 跨源返回 false")
        void testAllCanPushDownCrossSource() {
            List<AbstractBaseAdapter> adapters = Arrays.asList(
                    new IcebergAdapterImpl(icebergConfig()),
                    new DorisAdapterImpl(dorisConfig()),
                    new TrinoAdapterImpl(trinoConfig()),
                    new IoTDBAdapterImpl(iotdbConfig()),
                    new ElasticsearchAdapterImpl(esConfig()));

            for (AbstractBaseAdapter adapter : adapters) {
                CustomRelNode left = adapter.toRel(
                        adapter.getDataSourceConfig().getName() + ".t1", null);
                CustomRelNode right = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                        .setTableName("other_source.t2")
                        .setSourceName("other_source");
                CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN);
                join.addChild(left);
                join.addChild(right);
                // IoTDB 不支持 Join，canPushDown 返回 false（跨源或 Join 限制）
                assertFalse(adapter.canPushDown(join),
                        adapter.getAdapterType() + " 跨源 Join 应不可下推");
            }
        }

        @Test
        @DisplayName("所有适配器 pushDown 跨源返回失败")
        void testAllPushDownCrossSourceFails() {
            List<AbstractBaseAdapter> adapters = Arrays.asList(
                    new IcebergAdapterImpl(icebergConfig()),
                    new DorisAdapterImpl(dorisConfig()),
                    new TrinoAdapterImpl(trinoConfig()),
                    new IoTDBAdapterImpl(iotdbConfig()),
                    new ElasticsearchAdapterImpl(esConfig()));

            for (AbstractBaseAdapter adapter : adapters) {
                CustomRelNode left = adapter.toRel(
                        adapter.getDataSourceConfig().getName() + ".t1", null);
                CustomRelNode right = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                        .setTableName("other.t2")
                        .setSourceName("other");
                CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN);
                join.addChild(left);
                join.addChild(right);

                BaseAdapter.PushDownResult result = adapter.pushDown(join,
                        defaultCtx(adapter.getDialect()));
                assertFalse(result.isSuccess(),
                        adapter.getAdapterType() + " 跨源 pushDown 应失败");
            }
        }
    }
}