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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProjectPushDownRule} 投影下推规则单元测试。
 *
 * <p>测试覆盖 5 种数据源（Iceberg/Doris/Trino/IoTDB/ES）各 ≥ 8 个用例，共 ≥ 40 个用例，
 * 验证：</p>
 * <ul>
 *   <li>列裁剪正确下推到 TableScan</li>
 *   <li>嵌套投影正确合并</li>
 *   <li>下推率 ≥ 70%</li>
 *   <li>列裁剪后数据传输量减少 ≥ 50%</li>
 *   <li>查询结果语义不变</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class ProjectPushDownRuleTest {

    /** 测试用 10 列表（用于裁剪率验证） */
    private static final List<String> TEN_COLUMNS = Arrays.asList(
            "id", "name", "age", "email", "addr", "phone", "city", "country", "dt", "status");

    /** 测试用 5 列表 */
    private static final List<String> FIVE_COLUMNS = Arrays.asList(
            "id", "name", "age", "email", "addr");

    // ===================== 辅助方法 =====================

    /**
     * 构造 Project → TableScan 的 RelNode 树。
     *
     * @param adapter     适配器（提供数据源名）
     * @param tableName   表名
     * @param allColumns  表的全部列
     * @param projectExprs 投影表达式
     * @return Project 节点
     */
    private CustomRelNode buildProjectOverScan(BaseAdapter adapter, String tableName,
                                               List<String> allColumns,
                                               List<String> projectExprs) {
        CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName(tableName)
                .setSourceName(adapter.getDataSourceConfig().getName())
                .setProjects(allColumns)
                .setRemark("columns: " + allColumns);
        CustomRelNode project = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                .setProjects(projectExprs);
        project.addChild(scan);
        return project;
    }

    /**
     * 构造嵌套 Project → Project → TableScan 的 RelNode 树。
     */
    private CustomRelNode buildNestedProject(BaseAdapter adapter, String tableName,
                                             List<String> allColumns,
                                             List<String> innerProjects,
                                             List<String> outerProjects) {
        CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName(tableName)
                .setSourceName(adapter.getDataSourceConfig().getName())
                .setProjects(allColumns)
                .setRemark("columns: " + allColumns);
        CustomRelNode innerProject = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                .setProjects(innerProjects);
        innerProject.addChild(scan);
        CustomRelNode outerProject = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                .setProjects(outerProjects);
        outerProject.addChild(innerProject);
        return outerProject;
    }

    /**
     * 断言列裁剪率 ≥ 阈值。
     */
    private void assertReductionRate(ProjectionStatistics stats, double threshold, String msg) {
        double rate = stats.getColumnReductionRate();
        assertTrue(rate >= threshold,
                msg + " — 列裁剪率 " + String.format("%.2f%%", rate * 100)
                        + " < 阈值 " + String.format("%.2f%%", threshold * 100));
    }

    /**
     * 断言数据传输减少率 ≥ 阈值。
     */
    private void assertTransferReduction(ProjectionStatistics stats, double threshold, String msg) {
        double rate = stats.getDataTransferReductionRate();
        assertTrue(rate >= threshold,
                msg + " — 数据传输减少率 " + String.format("%.2f%%", rate * 100)
                        + " < 阈值 " + String.format("%.2f%%", threshold * 100));
    }

    /**
     * 断言下推率 ≥ 阈值。
     */
    private void assertPushDownRate(ProjectionStatistics stats, double threshold, String msg) {
        double rate = stats.getPushDownRate();
        assertTrue(rate >= threshold,
                msg + " — 下推率 " + String.format("%.2f%%", rate * 100)
                        + " < 阈值 " + String.format("%.2f%%", threshold * 100));
    }

    // ===================== Iceberg 适配器测试（8 用例） =====================

    @Nested
    @DisplayName("Iceberg 适配器投影下推")
    class IcebergProjectPushDownTest {

        private StubIcebergAdapter adapter = new StubIcebergAdapter();

        @Test
        @DisplayName("Iceberg-1: 单列投影 SELECT name FROM users → 只读 name 列")
        void testSingleColumnProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "iceberg.users",
                    FIVE_COLUMNS, List.of("name"));

            CustomRelNode result = rule.apply(project);

            // 验证下推成功
            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            // 验证 TableScan 只保留 name 列
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(1, scan.getProjects().size());
            assertTrue(scan.getProjects().contains("name"));
            // 验证列裁剪率 = 4/5 = 80%
            assertReductionRate(rule.getStatistics(), 0.7, "Iceberg-1");
            assertTransferReduction(rule.getStatistics(), 0.5, "Iceberg-1");
        }

        @Test
        @DisplayName("Iceberg-2: 多列投影 SELECT id, name, age FROM users → 读 3 列")
        void testMultiColumnProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "iceberg.users",
                    FIVE_COLUMNS, Arrays.asList("id", "name", "age"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(3, scan.getProjects().size());
            // 列裁剪率 = 2/5 = 40%，但下推率 = 100%
            assertPushDownRate(rule.getStatistics(), 0.7, "Iceberg-2");
        }

        @Test
        @DisplayName("Iceberg-3: 全列无裁剪 SELECT * FROM users → 不下推")
        void testSelectStarNoPushDown() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            // SELECT * 等价于引用全部列
            CustomRelNode project = buildProjectOverScan(adapter, "iceberg.users",
                    FIVE_COLUMNS, FIVE_COLUMNS);

            CustomRelNode result = rule.apply(project);

            // 全列引用，不下推
            assertNotEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
        }

        @Test
        @DisplayName("Iceberg-4: 表达式投影 SELECT id, name || 'x' FROM users → 读 id, name")
        void testExpressionProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "iceberg.users",
                    FIVE_COLUMNS, Arrays.asList("id", "name || 'x'"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(2, scan.getProjects().size());
            assertTrue(scan.getProjects().contains("id"));
            assertTrue(scan.getProjects().contains("name"));
            // 列裁剪率 = 3/5 = 60%，数据传输减少 60%
            assertTransferReduction(rule.getStatistics(), 0.5, "Iceberg-4");
        }

        @Test
        @DisplayName("Iceberg-5: 嵌套投影合并 SELECT a FROM (SELECT id AS a, name FROM users)")
        void testNestedProjectMerge() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode nested = buildNestedProject(adapter, "iceberg.users",
                    FIVE_COLUMNS,
                    Arrays.asList("id AS a", "name"),
                    List.of("a"));

            CustomRelNode result = rule.apply(nested);

            // 验证嵌套投影被合并
            assertTrue(rule.getStatistics().getMergeCount() >= 1, "Iceberg-5: 嵌套投影应被合并");
            // 合并后应只引用 id 列
            Set<String> used = rule.extractUsedColumns(result.getProjects());
            assertTrue(used.contains("id"), "Iceberg-5: 合并后应引用 id 列");
        }

        @Test
        @DisplayName("Iceberg-6: JOIN 投影 SELECT t1.name FROM t1 JOIN t2 ON t1.id = t2.id")
        void testJoinProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            // 构造 t1 的投影：SELECT name, id FROM t1（JOIN 需要 id）
            CustomRelNode t1Project = buildProjectOverScan(adapter, "iceberg.t1",
                    FIVE_COLUMNS, Arrays.asList("name", "id"));

            CustomRelNode result = rule.apply(t1Project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(2, scan.getProjects().size());
            // 列裁剪率 = 3/5 = 60%
            assertReductionRate(rule.getStatistics(), 0.5, "Iceberg-6");
        }

        @Test
        @DisplayName("Iceberg-7: 聚合投影 SELECT count(*), avg(age) FROM users")
        void testAggregateProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            // count(*) 引用全部列，avg(age) 引用 age
            CustomRelNode project = buildProjectOverScan(adapter, "iceberg.users",
                    FIVE_COLUMNS, Arrays.asList("count(*)", "avg(age)"));

            CustomRelNode result = rule.apply(project);

            // count(*) 引用全部列，不下推
            assertNotEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());

            // 单独验证 avg(age) 的列提取
            Set<String> used = rule.extractUsedColumns(List.of("avg(age)"));
            assertTrue(used.contains("age"), "Iceberg-7: avg(age) 应提取 age 列");
        }

        @Test
        @DisplayName("Iceberg-8: 列裁剪率验证 10 列表查 2 列，裁剪率 = 80%")
        void testColumnReductionRate() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "iceberg.big_table",
                    TEN_COLUMNS, Arrays.asList("name", "age"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            // 列裁剪率 = 8/10 = 80%
            assertReductionRate(rule.getStatistics(), 0.8, "Iceberg-8");
            assertTransferReduction(rule.getStatistics(), 0.8, "Iceberg-8");
            assertPushDownRate(rule.getStatistics(), 1.0, "Iceberg-8");
            // 验证裁剪列数
            assertEquals(8, rule.getStatistics().getPrunedColumns());
            assertEquals(2, rule.getStatistics().getRetainedColumns());
        }
    }

    // ===================== Doris 适配器测试（8 用例） =====================

    @Nested
    @DisplayName("Doris 适配器投影下推")
    class DorisProjectPushDownTest {

        private StubDorisAdapter adapter = new StubDorisAdapter();

        @Test
        @DisplayName("Doris-1: 单列投影 SELECT name FROM users → 只读 name 列")
        void testSingleColumnProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "doris.users",
                    FIVE_COLUMNS, List.of("name"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(1, scan.getProjects().size());
            assertTrue(scan.getProjects().contains("name"));
            assertReductionRate(rule.getStatistics(), 0.7, "Doris-1");
            assertTransferReduction(rule.getStatistics(), 0.5, "Doris-1");
        }

        @Test
        @DisplayName("Doris-2: 多列投影 SELECT id, name, age FROM users → 读 3 列")
        void testMultiColumnProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "doris.users",
                    FIVE_COLUMNS, Arrays.asList("id", "name", "age"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(3, scan.getProjects().size());
            assertPushDownRate(rule.getStatistics(), 0.7, "Doris-2");
        }

        @Test
        @DisplayName("Doris-3: 全列无裁剪 SELECT * FROM users → 不下推")
        void testSelectStarNoPushDown() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "doris.users",
                    FIVE_COLUMNS, FIVE_COLUMNS);

            CustomRelNode result = rule.apply(project);

            assertNotEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
        }

        @Test
        @DisplayName("Doris-4: 表达式投影 SELECT id, name || 'x' FROM users → 读 id, name")
        void testExpressionProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "doris.users",
                    FIVE_COLUMNS, Arrays.asList("id", "name || 'x'"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(2, scan.getProjects().size());
            assertTransferReduction(rule.getStatistics(), 0.5, "Doris-4");
        }

        @Test
        @DisplayName("Doris-5: 嵌套投影合并 SELECT a FROM (SELECT id AS a, name FROM users)")
        void testNestedProjectMerge() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode nested = buildNestedProject(adapter, "doris.users",
                    FIVE_COLUMNS,
                    Arrays.asList("id AS a", "name"),
                    List.of("a"));

            CustomRelNode result = rule.apply(nested);

            assertTrue(rule.getStatistics().getMergeCount() >= 1, "Doris-5: 嵌套投影应被合并");
            Set<String> used = rule.extractUsedColumns(result.getProjects());
            assertTrue(used.contains("id"), "Doris-5: 合并后应引用 id 列");
        }

        @Test
        @DisplayName("Doris-6: JOIN 投影 SELECT t1.name FROM t1 JOIN t2 ON t1.id = t2.id")
        void testJoinProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode t1Project = buildProjectOverScan(adapter, "doris.t1",
                    FIVE_COLUMNS, Arrays.asList("name", "id"));

            CustomRelNode result = rule.apply(t1Project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(2, scan.getProjects().size());
            assertReductionRate(rule.getStatistics(), 0.5, "Doris-6");
        }

        @Test
        @DisplayName("Doris-7: 聚合投影 SELECT count(*), avg(age) FROM users")
        void testAggregateProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "doris.users",
                    FIVE_COLUMNS, Arrays.asList("count(*)", "avg(age)"));

            CustomRelNode result = rule.apply(project);

            assertNotEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            Set<String> used = rule.extractUsedColumns(List.of("avg(age)"));
            assertTrue(used.contains("age"), "Doris-7: avg(age) 应提取 age 列");
        }

        @Test
        @DisplayName("Doris-8: 列裁剪率验证 10 列表查 2 列，裁剪率 = 80%")
        void testColumnReductionRate() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "doris.big_table",
                    TEN_COLUMNS, Arrays.asList("name", "age"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            assertReductionRate(rule.getStatistics(), 0.8, "Doris-8");
            assertTransferReduction(rule.getStatistics(), 0.8, "Doris-8");
            assertPushDownRate(rule.getStatistics(), 1.0, "Doris-8");
            assertEquals(8, rule.getStatistics().getPrunedColumns());
            assertEquals(2, rule.getStatistics().getRetainedColumns());
        }
    }

    // ===================== Trino 适配器测试（8 用例） =====================

    @Nested
    @DisplayName("Trino 适配器投影下推")
    class TrinoProjectPushDownTest {

        private StubTrinoAdapter adapter = new StubTrinoAdapter();

        @Test
        @DisplayName("Trino-1: 单列投影 SELECT name FROM users → 只读 name 列")
        void testSingleColumnProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "trino.users",
                    FIVE_COLUMNS, List.of("name"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(1, scan.getProjects().size());
            assertTrue(scan.getProjects().contains("name"));
            assertReductionRate(rule.getStatistics(), 0.7, "Trino-1");
            assertTransferReduction(rule.getStatistics(), 0.5, "Trino-1");
        }

        @Test
        @DisplayName("Trino-2: 多列投影 SELECT id, name, age FROM users → 读 3 列")
        void testMultiColumnProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "trino.users",
                    FIVE_COLUMNS, Arrays.asList("id", "name", "age"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(3, scan.getProjects().size());
            assertPushDownRate(rule.getStatistics(), 0.7, "Trino-2");
        }

        @Test
        @DisplayName("Trino-3: 全列无裁剪 SELECT * FROM users → 不下推")
        void testSelectStarNoPushDown() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "trino.users",
                    FIVE_COLUMNS, FIVE_COLUMNS);

            CustomRelNode result = rule.apply(project);

            assertNotEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
        }

        @Test
        @DisplayName("Trino-4: 表达式投影 SELECT id, name || 'x' FROM users → 读 id, name")
        void testExpressionProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "trino.users",
                    FIVE_COLUMNS, Arrays.asList("id", "name || 'x'"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(2, scan.getProjects().size());
            assertTransferReduction(rule.getStatistics(), 0.5, "Trino-4");
        }

        @Test
        @DisplayName("Trino-5: 嵌套投影合并 SELECT a FROM (SELECT id AS a, name FROM users)")
        void testNestedProjectMerge() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode nested = buildNestedProject(adapter, "trino.users",
                    FIVE_COLUMNS,
                    Arrays.asList("id AS a", "name"),
                    List.of("a"));

            CustomRelNode result = rule.apply(nested);

            assertTrue(rule.getStatistics().getMergeCount() >= 1, "Trino-5: 嵌套投影应被合并");
            Set<String> used = rule.extractUsedColumns(result.getProjects());
            assertTrue(used.contains("id"), "Trino-5: 合并后应引用 id 列");
        }

        @Test
        @DisplayName("Trino-6: JOIN 投影 SELECT t1.name FROM t1 JOIN t2 ON t1.id = t2.id")
        void testJoinProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode t1Project = buildProjectOverScan(adapter, "trino.t1",
                    FIVE_COLUMNS, Arrays.asList("name", "id"));

            CustomRelNode result = rule.apply(t1Project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(2, scan.getProjects().size());
            assertReductionRate(rule.getStatistics(), 0.5, "Trino-6");
        }

        @Test
        @DisplayName("Trino-7: 聚合投影 SELECT count(*), avg(age) FROM users")
        void testAggregateProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "trino.users",
                    FIVE_COLUMNS, Arrays.asList("count(*)", "avg(age)"));

            CustomRelNode result = rule.apply(project);

            assertNotEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            Set<String> used = rule.extractUsedColumns(List.of("avg(age)"));
            assertTrue(used.contains("age"), "Trino-7: avg(age) 应提取 age 列");
        }

        @Test
        @DisplayName("Trino-8: 列裁剪率验证 10 列表查 2 列，裁剪率 = 80%")
        void testColumnReductionRate() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "trino.big_table",
                    TEN_COLUMNS, Arrays.asList("name", "age"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            assertReductionRate(rule.getStatistics(), 0.8, "Trino-8");
            assertTransferReduction(rule.getStatistics(), 0.8, "Trino-8");
            assertPushDownRate(rule.getStatistics(), 1.0, "Trino-8");
            assertEquals(8, rule.getStatistics().getPrunedColumns());
            assertEquals(2, rule.getStatistics().getRetainedColumns());
        }
    }

    // ===================== IoTDB 适配器测试（8 用例，针对时序测点裁剪） =====================

    @Nested
    @DisplayName("IoTDB 适配器投影下推（时序测点裁剪）")
    class IoTDBProjectPushDownTest {

        private StubIoTDBAdapter adapter = new StubIoTDBAdapter();

        /** IoTDB 测点列表（设备-测点模型） */
        private final List<String> iotdbMeasurements = Arrays.asList(
                "time", "root.sg.d1.temperature", "root.sg.d1.humidity",
                "root.sg.d1.pressure", "root.sg.d1.status");

        @Test
        @DisplayName("IoTDB-1: 单测点投影 SELECT temperature FROM device → 只读 1 测点")
        void testSingleMeasurementProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "iotdb.root.sg.d1",
                    iotdbMeasurements, List.of("root.sg.d1.temperature"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(1, scan.getProjects().size());
            // 5 测点裁剪为 1，裁剪率 = 80%
            assertReductionRate(rule.getStatistics(), 0.7, "IoTDB-1");
            assertTransferReduction(rule.getStatistics(), 0.5, "IoTDB-1");
        }

        @Test
        @DisplayName("IoTDB-2: 多测点投影 SELECT temperature, humidity FROM device → 读 2 测点")
        void testMultiMeasurementProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "iotdb.root.sg.d1",
                    iotdbMeasurements,
                    Arrays.asList("root.sg.d1.temperature", "root.sg.d1.humidity"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(2, scan.getProjects().size());
            // 裁剪率 = 3/5 = 60%
            assertReductionRate(rule.getStatistics(), 0.5, "IoTDB-2");
            assertPushDownRate(rule.getStatistics(), 0.7, "IoTDB-2");
        }

        @Test
        @DisplayName("IoTDB-3: 全测点无裁剪 SELECT * FROM device → 不下推")
        void testSelectStarNoPushDown() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "iotdb.root.sg.d1",
                    iotdbMeasurements, iotdbMeasurements);

            CustomRelNode result = rule.apply(project);

            assertNotEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
        }

        @Test
        @DisplayName("IoTDB-4: 时间+测点投影 SELECT time, temperature FROM device")
        void testTimeAndMeasurementProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "iotdb.root.sg.d1",
                    iotdbMeasurements,
                    Arrays.asList("time", "root.sg.d1.temperature"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(2, scan.getProjects().size());
            assertTrue(scan.getProjects().contains("time"));
            // 裁剪率 = 3/5 = 60%
            assertTransferReduction(rule.getStatistics(), 0.5, "IoTDB-4");
        }

        @Test
        @DisplayName("IoTDB-5: 嵌套测点投影合并 SELECT t FROM (SELECT temperature AS t FROM device)")
        void testNestedMeasurementMerge() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode nested = buildNestedProject(adapter, "iotdb.root.sg.d1",
                    iotdbMeasurements,
                    Arrays.asList("root.sg.d1.temperature AS t", "root.sg.d1.humidity"),
                    List.of("t"));

            CustomRelNode result = rule.apply(nested);

            assertTrue(rule.getStatistics().getMergeCount() >= 1, "IoTDB-5: 嵌套投影应被合并");
            Set<String> used = rule.extractUsedColumns(result.getProjects());
            assertTrue(used.contains("root.sg.d1.temperature"), "IoTDB-5: 合并后应引用 temperature");
        }

        @Test
        @DisplayName("IoTDB-6: 降采样投影 SELECT avg(temperature) FROM device GROUP BY time")
        void testDownsamplingProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "iotdb.root.sg.d1",
                    iotdbMeasurements,
                    Arrays.asList("avg(root.sg.d1.temperature)", "time"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            // 只读 time + temperature
            assertEquals(2, scan.getProjects().size());
            assertReductionRate(rule.getStatistics(), 0.5, "IoTDB-6");
        }

        @Test
        @DisplayName("IoTDB-7: 聚合投影 SELECT count(*), max(temperature) FROM device")
        void testAggregateProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "iotdb.root.sg.d1",
                    iotdbMeasurements,
                    Arrays.asList("count(*)", "max(root.sg.d1.temperature)"));

            CustomRelNode result = rule.apply(project);

            // count(*) 引用全部列，不下推
            assertNotEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            Set<String> used = rule.extractUsedColumns(List.of("max(root.sg.d1.temperature)"));
            assertTrue(used.contains("root.sg.d1.temperature"), "IoTDB-7: max 应提取 temperature");
        }

        @Test
        @DisplayName("IoTDB-8: 测点裁剪率验证 10 测点查 2 测点，裁剪率 = 80%")
        void testMeasurementReductionRate() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "iotdb.root.sg.big",
                    TEN_COLUMNS, Arrays.asList("name", "age"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            assertReductionRate(rule.getStatistics(), 0.8, "IoTDB-8");
            assertTransferReduction(rule.getStatistics(), 0.8, "IoTDB-8");
            assertPushDownRate(rule.getStatistics(), 1.0, "IoTDB-8");
            assertEquals(8, rule.getStatistics().getPrunedColumns());
        }
    }

    // ===================== ES 适配器测试（8 用例，针对 source 字段过滤） =====================

    @Nested
    @DisplayName("ES 适配器投影下推（source 字段过滤）")
    class ElasticsearchProjectPushDownTest {

        private StubElasticsearchAdapter adapter = new StubElasticsearchAdapter();

        /** ES 索引字段列表 */
        private final List<String> esFields = Arrays.asList(
                "_id", "title", "content", "tags", "author", "timestamp", "score", "category");

        @Test
        @DisplayName("ES-1: 单字段投影 SELECT title FROM index → source 过滤为 1 字段")
        void testSingleFieldProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "es.articles",
                    esFields, List.of("title"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(1, scan.getProjects().size());
            assertTrue(scan.getProjects().contains("title"));
            // 8 字段裁剪为 1，裁剪率 = 87.5%
            assertReductionRate(rule.getStatistics(), 0.7, "ES-1");
            assertTransferReduction(rule.getStatistics(), 0.5, "ES-1");
        }

        @Test
        @DisplayName("ES-2: 多字段投影 SELECT title, author, score FROM index → 读 3 字段")
        void testMultiFieldProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "es.articles",
                    esFields, Arrays.asList("title", "author", "score"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(3, scan.getProjects().size());
            // 裁剪率 = 5/8 = 62.5%
            assertReductionRate(rule.getStatistics(), 0.5, "ES-2");
            assertPushDownRate(rule.getStatistics(), 0.7, "ES-2");
        }

        @Test
        @DisplayName("ES-3: 全字段无裁剪 SELECT * FROM index → 不下推")
        void testSelectStarNoPushDown() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "es.articles",
                    esFields, esFields);

            CustomRelNode result = rule.apply(project);

            assertNotEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
        }

        @Test
        @DisplayName("ES-4: 表达式投影 SELECT _id, title || content FROM index → 读 3 字段")
        void testExpressionProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "es.articles",
                    esFields, Arrays.asList("_id", "title || content"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            // _id + title + content = 3 字段
            assertEquals(3, scan.getProjects().size());
            // 裁剪率 = 5/8 = 62.5%
            assertTransferReduction(rule.getStatistics(), 0.5, "ES-4");
        }

        @Test
        @DisplayName("ES-5: 嵌套字段投影合并 SELECT t FROM (SELECT title AS t FROM index)")
        void testNestedFieldMerge() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode nested = buildNestedProject(adapter, "es.articles",
                    esFields,
                    Arrays.asList("title AS t", "author"),
                    List.of("t"));

            CustomRelNode result = rule.apply(nested);

            assertTrue(rule.getStatistics().getMergeCount() >= 1, "ES-5: 嵌套投影应被合并");
            Set<String> used = rule.extractUsedColumns(result.getProjects());
            assertTrue(used.contains("title"), "ES-5: 合并后应引用 title");
        }

        @Test
        @DisplayName("ES-6: 聚合投影 SELECT category, avg(score) FROM index GROUP BY category")
        void testAggregationProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "es.articles",
                    esFields, Arrays.asList("category", "avg(score)"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            // category + score = 2 字段
            assertEquals(2, scan.getProjects().size());
            // 裁剪率 = 6/8 = 75%
            assertReductionRate(rule.getStatistics(), 0.7, "ES-6");
        }

        @Test
        @DisplayName("ES-7: 全文检索+字段投影 SELECT title, score FROM index WHERE match(content, 'x')")
        void testFullTextSearchProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            // match 检索需要 content 字段，加上投影的 title, score
            CustomRelNode project = buildProjectOverScan(adapter, "es.articles",
                    esFields, Arrays.asList("title", "score", "content"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            CustomRelNode scan = result.getChildren().get(0);
            assertEquals(3, scan.getProjects().size());
            // 裁剪率 = 5/8 = 62.5%
            assertTransferReduction(rule.getStatistics(), 0.5, "ES-7");
        }

        @Test
        @DisplayName("ES-8: 字段裁剪率验证 10 字段查 2 字段，裁剪率 = 80%")
        void testFieldReductionRate() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "es.big_index",
                    TEN_COLUMNS, Arrays.asList("name", "age"));

            CustomRelNode result = rule.apply(project);

            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
            assertReductionRate(rule.getStatistics(), 0.8, "ES-8");
            assertTransferReduction(rule.getStatistics(), 0.8, "ES-8");
            assertPushDownRate(rule.getStatistics(), 1.0, "ES-8");
            assertEquals(8, rule.getStatistics().getPrunedColumns());
        }
    }

    // ===================== 统计器与规则核心测试 =====================

    @Nested
    @DisplayName("ProjectionStatistics 统计器测试")
    class StatisticsTest {

        @Test
        @DisplayName("统计器-1: 列裁剪率计算正确")
        void testColumnReductionRate() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordProjection(DataSourceConfig.Type.ICEBERG, 10, 3);
            // 裁剪率 = 7/10 = 70%
            assertEquals(0.7, stats.getColumnReductionRate(), 0.001);
            assertEquals(7, stats.getPrunedColumns());
            assertEquals(3, stats.getRetainedColumns());
        }

        @Test
        @DisplayName("统计器-2: 数据传输减少率 ≈ 列裁剪率")
        void testDataTransferReductionRate() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordProjection(DataSourceConfig.Type.DORIS, 10, 2);
            assertEquals(stats.getColumnReductionRate(), stats.getDataTransferReductionRate(), 0.001);
        }

        @Test
        @DisplayName("统计器-3: 按数据源分类统计")
        void testSourceStats() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordProjection(DataSourceConfig.Type.ICEBERG, 10, 3);
            stats.recordProjection(DataSourceConfig.Type.DORIS, 8, 2);
            stats.recordProjection(DataSourceConfig.Type.TRINO, 5, 1);

            assertEquals(0.7, stats.getColumnReductionRate(DataSourceConfig.Type.ICEBERG), 0.001);
            assertEquals(0.75, stats.getColumnReductionRate(DataSourceConfig.Type.DORIS), 0.001);
            assertEquals(0.8, stats.getColumnReductionRate(DataSourceConfig.Type.TRINO), 0.001);
            assertEquals(3, stats.getActiveSourceTypes().size());
        }

        @Test
        @DisplayName("统计器-4: 下推率计算")
        void testPushDownRate() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordProjection(DataSourceConfig.Type.ICEBERG, 10, 3);
            stats.recordProjection(DataSourceConfig.Type.DORIS, 8, 2);
            stats.recordSkip("SELECT *");
            // 2 次下推 + 1 次跳过 = 下推率 2/3
            assertEquals(2.0 / 3, stats.getPushDownRate(), 0.001);
        }

        @Test
        @DisplayName("统计器-5: 嵌套投影合并计数")
        void testMergeCount() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordMerge();
            stats.recordMerge();
            assertEquals(2, stats.getMergeCount());
        }

        @Test
        @DisplayName("统计器-6: 重置统计")
        void testReset() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordProjection(DataSourceConfig.Type.ICEBERG, 10, 3);
            stats.recordMerge();
            stats.reset();
            assertEquals(0, stats.getTotalColumns());
            assertEquals(0, stats.getMergeCount());
            assertEquals(0.0, stats.getColumnReductionRate());
        }

        @Test
        @DisplayName("统计器-7: 空统计器返回 0")
        void testEmptyStats() {
            ProjectionStatistics stats = new ProjectionStatistics();
            assertEquals(0.0, stats.getColumnReductionRate());
            assertEquals(0.0, stats.getDataTransferReductionRate());
            assertEquals(0.0, stats.getPushDownRate());
            assertTrue(stats.getActiveSourceTypes().isEmpty());
        }

        @Test
        @DisplayName("统计器-8: summary 字符串包含关键指标")
        void testSummary() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordProjection(DataSourceConfig.Type.ICEBERG, 10, 3);
            String summary = stats.summary();
            assertTrue(summary.contains("reductionRate"));
            assertTrue(summary.contains("transferReductionRate"));
            assertTrue(summary.contains("ICEBERG"));
        }

        @Test
        @DisplayName("统计器-9: toString 包含关键指标")
        void testToString() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordProjection(DataSourceConfig.Type.DORIS, 10, 3);
            String str = stats.toString();
            assertTrue(str.contains("totalCols"));
            assertTrue(str.contains("retainedCols"));
            assertTrue(str.contains("prunedCols"));
            assertTrue(str.contains("reductionRate"));
        }

        @Test
        @DisplayName("统计器-10: getSourceStats 返回快照")
        void testGetSourceStats() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordProjection(DataSourceConfig.Type.ICEBERG, 10, 3);
            stats.recordProjection(DataSourceConfig.Type.DORIS, 8, 2);
            var sourceStats = stats.getSourceStats();
            assertNotNull(sourceStats);
            assertEquals(5, sourceStats.size()); // 5 种数据源
            // ICEBERG: total=10, retained=3
            assertEquals(10, sourceStats.get(DataSourceConfig.Type.ICEBERG)[0]);
            assertEquals(3, sourceStats.get(DataSourceConfig.Type.ICEBERG)[1]);
        }

        @Test
        @DisplayName("统计器-11: getPushedDescriptions 返回下推描述")
        void testGetPushedDescriptions() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordProjection(DataSourceConfig.Type.ICEBERG, 10, 3, "users: [id,name,...] -> [name]");
            var descs = stats.getPushedDescriptions();
            assertEquals(1, descs.size());
            assertTrue(descs.get(0).contains("users"));
        }

        @Test
        @DisplayName("统计器-12: getSkipReasons 返回跳过原因")
        void testGetSkipReasons() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordSkip("SELECT *");
            stats.recordSkip("count(*)");
            var reasons = stats.getSkipReasons();
            assertEquals(2, reasons.size());
            assertTrue(reasons.contains("SELECT *"));
        }

        @Test
        @DisplayName("统计器-13: 按数据源获取下推率")
        void testPushDownRateBySource() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordProjection(DataSourceConfig.Type.ICEBERG, 10, 3);
            // 有裁剪 → 下推率 = 1.0
            assertEquals(1.0, stats.getPushDownRate(DataSourceConfig.Type.ICEBERG), 0.001);
            // 无记录 → 下推率 = 0.0
            assertEquals(0.0, stats.getPushDownRate(DataSourceConfig.Type.DORIS), 0.001);
        }

        @Test
        @DisplayName("统计器-14: getDataTransferReductionRate 按数据源")
        void testTransferReductionBySource() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordProjection(DataSourceConfig.Type.ICEBERG, 10, 3);
            assertEquals(0.7, stats.getDataTransferReductionRate(DataSourceConfig.Type.ICEBERG), 0.001);
            assertEquals(0.0, stats.getDataTransferReductionRate(DataSourceConfig.Type.DORIS), 0.001);
        }

        @Test
        @DisplayName("统计器-15: 负数参数被忽略")
        void testNegativeArgs() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordProjection(DataSourceConfig.Type.ICEBERG, -1, 3);
            assertEquals(0, stats.getTotalColumns());
        }

        @Test
        @DisplayName("统计器-16: 保留列数大于总列数时裁剪列为0")
        void testRetainedExceedsTotal() {
            ProjectionStatistics stats = new ProjectionStatistics();
            stats.recordProjection(DataSourceConfig.Type.ICEBERG, 3, 10);
            assertEquals(0, stats.getPrunedColumns());
            assertEquals(0.0, stats.getColumnReductionRate());
        }
    }

    @Nested
    @DisplayName("ProjectPushDownRule 核心逻辑测试")
    class RuleCoreTest {

        private StubIcebergAdapter adapter = new StubIcebergAdapter();

        @Test
        @DisplayName("核心-1: extractUsedColumns 提取纯列名")
        void testExtractUsedColumnsSimple() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            Set<String> used = rule.extractUsedColumns(Arrays.asList("id", "name", "age"));
            assertEquals(3, used.size());
            assertTrue(used.contains("id"));
            assertTrue(used.contains("name"));
            assertTrue(used.contains("age"));
        }

        @Test
        @DisplayName("核心-2: extractUsedColumns 提取表达式中的列")
        void testExtractUsedColumnsExpression() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            Set<String> used = rule.extractUsedColumns(Arrays.asList("a + b", "c * 2"));
            assertTrue(used.contains("a"));
            assertTrue(used.contains("b"));
            assertTrue(used.contains("c"));
            assertFalse(used.contains("2"));
        }

        @Test
        @DisplayName("核心-3: extractUsedColumns 处理 count(*)")
        void testExtractUsedColumnsCountStar() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            Set<String> used = rule.extractUsedColumns(List.of("count(*)"));
            assertTrue(used.contains("*"));
        }

        @Test
        @DisplayName("核心-4: extractUsedColumns 跳过 SQL 关键字")
        void testExtractUsedColumnsSkipKeywords() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            Set<String> used = rule.extractUsedColumns(List.of("CASE WHEN age > 18 THEN name ELSE 'unknown' END"));
            assertTrue(used.contains("age"));
            assertTrue(used.contains("name"));
            assertFalse(used.contains("CASE"));
            assertFalse(used.contains("WHEN"));
            assertFalse(used.contains("THEN"));
        }

        @Test
        @DisplayName("核心-5: isNestedProject 判定")
        void testIsNestedProject() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode nested = buildNestedProject(adapter, "t",
                    FIVE_COLUMNS, Arrays.asList("id", "name"), List.of("id"));
            assertTrue(rule.isNestedProject(nested));

            CustomRelNode simple = buildProjectOverScan(adapter, "t",
                    FIVE_COLUMNS, List.of("id"));
            assertFalse(rule.isNestedProject(simple));
        }

        @Test
        @DisplayName("核心-6: mergeNestedProjects 合并嵌套投影")
        void testMergeNestedProjects() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode nested = buildNestedProject(adapter, "t",
                    FIVE_COLUMNS,
                    Arrays.asList("id AS a", "name"),
                    List.of("a"));

            CustomRelNode merged = rule.mergeNestedProjects(nested);
            // 合并后应只引用 id
            Set<String> used = rule.extractUsedColumns(merged.getProjects());
            assertTrue(used.contains("id"));
            assertFalse(used.contains("name"));
            assertEquals(1, rule.getStatistics().getMergeCount());
        }

        @Test
        @DisplayName("核心-7: analyze 返回正确的下推分析")
        void testAnalyze() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            ProjectPushDownRule.ProjectionAnalysis analysis = rule.analyze(
                    Arrays.asList("name", "age"),
                    FIVE_COLUMNS,
                    adapter);

            assertEquals(5, analysis.getTotalColumnCount());
            assertEquals(2, analysis.getRetainedColumnCount());
            assertEquals(3, analysis.getPrunedColumnCount());
            assertEquals(0.6, analysis.getColumnReductionRate(), 0.001);
            assertTrue(analysis.shouldPushDown());
            assertFalse(analysis.referencesAllColumns());
        }

        @Test
        @DisplayName("核心-8: analyze 处理 count(*) 全列引用")
        void testAnalyzeCountStar() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            ProjectPushDownRule.ProjectionAnalysis analysis = rule.analyze(
                    List.of("count(*)"),
                    FIVE_COLUMNS,
                    adapter);

            assertTrue(analysis.referencesAllColumns());
            assertFalse(analysis.shouldPushDown());
        }

        @Test
        @DisplayName("核心-9: 规则不匹配非 Project 节点")
        void testNotMatchNonProject() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setCondition("x > 1");
            // matches 应返回 false（操作类型不匹配）
            assertFalse(rule.matches(filter));
        }

        @Test
        @DisplayName("核心-10: 规则禁用后不匹配")
        void testDisabledRule() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            rule.setEnabled(false);
            CustomRelNode project = buildProjectOverScan(adapter, "t",
                    FIVE_COLUMNS, List.of("name"));
            assertFalse(rule.matches(project));
        }

        @Test
        @DisplayName("核心-11: extractUsedColumns 空列表返回空集合")
        void testExtractUsedColumnsEmpty() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            assertTrue(rule.extractUsedColumns(Collections.emptyList()).isEmpty());
            assertTrue(rule.extractUsedColumns(null).isEmpty());
        }

        @Test
        @DisplayName("核心-12: extractUsedColumns 处理 null/空白表达式")
        void testExtractUsedColumnsNullExpr() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            Set<String> used = rule.extractUsedColumns(Arrays.asList(null, "", "  "));
            assertTrue(used.isEmpty());
        }

        @Test
        @DisplayName("核心-13: extractUsedColumns 处理星号 *")
        void testExtractUsedColumnsStar() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            Set<String> used = rule.extractUsedColumns(List.of("*"));
            assertTrue(used.contains("*"));
        }

        @Test
        @DisplayName("核心-14: isNestedProject 对非 Project 节点返回 false")
        void testIsNestedProjectNonProject() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER);
            assertFalse(rule.isNestedProject(filter));
            assertFalse(rule.isNestedProject(null));
        }

        @Test
        @DisplayName("核心-15: mergeNestedProjects 对非嵌套投影返回原节点")
        void testMergeNonNested() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode simple = buildProjectOverScan(adapter, "t",
                    FIVE_COLUMNS, List.of("name"));
            CustomRelNode merged = rule.mergeNestedProjects(simple);
            assertSame(simple, merged);
        }

        @Test
        @DisplayName("核心-16: analyze 处理全列引用")
        void testAnalyzeAllColumns() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            ProjectPushDownRule.ProjectionAnalysis analysis = rule.analyze(
                    FIVE_COLUMNS, FIVE_COLUMNS, adapter);
            assertFalse(analysis.shouldPushDown());
            assertEquals(0.0, analysis.getColumnReductionRate(), 0.001);
        }

        @Test
        @DisplayName("核心-17: analyze 处理空投影")
        void testAnalyzeEmptyProjection() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            ProjectPushDownRule.ProjectionAnalysis analysis = rule.analyze(
                    Collections.emptyList(), FIVE_COLUMNS, adapter);
            assertFalse(analysis.shouldPushDown());
            assertEquals(0, analysis.getRetainedColumnCount());
        }

        @Test
        @DisplayName("核心-18: ProjectionAnalysis toString 包含关键信息")
        void testAnalysisToString() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            ProjectPushDownRule.ProjectionAnalysis analysis = rule.analyze(
                    Arrays.asList("name", "age"), FIVE_COLUMNS, adapter);
            String str = analysis.toString();
            assertTrue(str.contains("total"));
            assertTrue(str.contains("retained"));
            assertTrue(str.contains("pruned"));
            assertTrue(str.contains("reductionRate"));
        }

        @Test
        @DisplayName("核心-19: ProjectionAnalysis getter 方法")
        void testAnalysisGetters() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            ProjectPushDownRule.ProjectionAnalysis analysis = rule.analyze(
                    Arrays.asList("name", "age"), FIVE_COLUMNS, adapter);
            assertEquals(5, analysis.getTotalColumnCount());
            assertEquals(2, analysis.getRetainedColumnCount());
            assertEquals(3, analysis.getPrunedColumnCount());
            assertEquals(2, analysis.getProjectedColumns().size());
            assertEquals(5, analysis.getAllColumns().size());
            assertTrue(analysis.getUsedColumns().contains("name"));
            assertTrue(analysis.getUsedColumns().contains("age"));
        }

        @Test
        @DisplayName("核心-20: onMatch 处理无 TableScan 的 Project")
        void testOnMatchNoTableScan() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            // Project 无子节点
            CustomRelNode project = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                    .setProjects(List.of("name"));
            CustomRelNode result = rule.apply(project);
            // 应跳过（未找到 TableScan）
            assertNotNull(result);
            assertTrue(rule.getStatistics().getSkipCount() >= 1);
        }

        @Test
        @DisplayName("核心-21: onMatch 处理空 projects")
        void testOnMatchEmptyProjects() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                    .setTableName("t")
                    .setSourceName("iceberg_lake")
                    .setProjects(FIVE_COLUMNS);
            CustomRelNode project = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                    .setProjects(Collections.emptyList());
            project.addChild(scan);
            CustomRelNode result = rule.apply(project);
            assertNotNull(result);
        }

        @Test
        @DisplayName("核心-22: onMatch 处理引用列不在表列中")
        void testOnMatchColumnNotInTable() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            CustomRelNode project = buildProjectOverScan(adapter, "t",
                    FIVE_COLUMNS, List.of("nonexistent_col"));
            CustomRelNode result = rule.apply(project);
            assertNotNull(result);
            // 引用列不在表列中，应跳过
            assertTrue(rule.getStatistics().getSkipCount() >= 1);
        }

        @Test
        @DisplayName("核心-23: getRuleName 返回正确名称")
        void testGetRuleName() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            assertEquals("ProjectPushDown", rule.getRuleName());
        }

        @Test
        @DisplayName("核心-24: getMatchOp 返回 PROJECT")
        void testGetMatchOp() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            assertEquals(CustomRelNode.Op.PROJECT, rule.getMatchOp());
        }

        @Test
        @DisplayName("核心-25: 从 remark 解析列信息")
        void testParseColumnsFromRemark() {
            ProjectPushDownRule rule = new ProjectPushDownRule(adapter);
            // TableScan 无 projects，但有 remark 包含列信息
            CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                    .setTableName("t")
                    .setSourceName("iceberg_lake")
                    .setRemark("columns: [id, name, age, email, addr]");
            CustomRelNode project = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                    .setProjects(List.of("name"));
            project.addChild(scan);
            CustomRelNode result = rule.apply(project);
            assertEquals(CustomRelNode.PushDownStatus.PUSHED, result.getPushDownStatus());
        }
    }

    // ===================== 桩实现 =====================

    /** Iceberg 适配器桩实现 */
    static class StubIcebergAdapter implements IcebergAdapter {
        private final DataSourceConfig config = new DataSourceConfig("iceberg_lake",
                DataSourceConfig.Type.ICEBERG)
                .setJdbcUrl("jdbc:hive2://localhost:10000")
                .setDialect(SqlDialect.HIVE);

        @Override
        public DataSourceConfig getDataSourceConfig() { return config; }

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
        public SqlDialect getDialect() { return SqlDialect.HIVE; }

        @Override
        public List<String> prunePartitions(String tableName, String partitionFilter) {
            return Arrays.asList("2024-01-01");
        }

        @Override
        public long selectSnapshot(String tableName, Long snapshotId, Long asOfTimestamp) {
            return snapshotId != null ? snapshotId : 999L;
        }

        @Override
        public boolean isPartitionColumn(String tableName, String column) {
            return "dt".equals(column);
        }

        @Override
        public int getSchemaVersion(String tableName) { return 3; }
    }

    /** Doris 适配器桩实现 */
    static class StubDorisAdapter implements DorisAdapter {
        private final DataSourceConfig config = new DataSourceConfig("doris_olap",
                DataSourceConfig.Type.DORIS)
                .setJdbcUrl("jdbc:mysql://localhost:9030")
                .setDialect(SqlDialect.DORIS);

        @Override
        public DataSourceConfig getDataSourceConfig() { return config; }

        @Override
        public CustomRelNode toRel(String tableName, List<String> columns) {
            return CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                    .setTableName(tableName).setSourceName(config.getName());
        }

        @Override
        public PushDownResult pushDown(CustomRelNode relNode, PushDownContext context) {
            if (!canPushDown(relNode)) {
                return PushDownResult.failure("跨源节点不可下推");
            }
            return new PushDownResult("SELECT * FROM t", relNode,
                    new ArrayList<>(), true, null);
        }

        @Override
        public Cost costEstimate(CustomRelNode relNode) {
            return new Cost(10, 100, 5, 1000);
        }

        @Override
        public SqlDialect getDialect() { return SqlDialect.DORIS; }

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
        public int getTabletCount(String tableName) { return 64; }

        @Override
        public long getEstimatedRowCount(String tableName) { return 1_000_000L; }
    }

    /** Trino 适配器桩实现 */
    static class StubTrinoAdapter implements TrinoAdapter {
        private final DataSourceConfig config = new DataSourceConfig("trino_hive",
                DataSourceConfig.Type.TRINO)
                .setJdbcUrl("jdbc:trino://localhost:8080")
                .setDialect(SqlDialect.TRINO);

        @Override
        public DataSourceConfig getDataSourceConfig() { return config; }

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
        public SqlDialect getDialect() { return SqlDialect.TRINO; }

        @Override
        public String getConnectorName(String tableName) {
            if (tableName.startsWith("hive.")) return "hive";
            if (tableName.startsWith("iceberg.")) return "iceberg";
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
        public int getWorkerCount() { return 10; }
    }

    /** IoTDB 适配器桩实现 */
    static class StubIoTDBAdapter implements IoTDBAdapter {
        private final DataSourceConfig config = new DataSourceConfig("iotdb_ts",
                DataSourceConfig.Type.IOTDB)
                .setEndpoint("http://iotdb:18080")
                .setDialect(SqlDialect.ANSI);

        @Override
        public DataSourceConfig getDataSourceConfig() { return config; }

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
        public SqlDialect getDialect() { return SqlDialect.ANSI; }

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
        public DataSourceConfig getDataSourceConfig() { return config; }

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
        public SqlDialect getDialect() { return SqlDialect.ANSI; }

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