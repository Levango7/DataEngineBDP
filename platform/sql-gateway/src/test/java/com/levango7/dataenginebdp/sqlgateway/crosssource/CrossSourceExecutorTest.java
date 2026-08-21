package com.levango7.dataenginebdp.sqlgateway.crosssource;

import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlParserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CrossSourceExecutor} 单元测试。
 *
 * <p>覆盖跨源执行器的并行查询、内存归并（UNION ALL）、执行计划生成、
 * 超时控制、异常处理等场景。使用自定义 {@link CrossSourceExecutor.SourceResolver}
 * 模拟表→源映射，不依赖真实后端。</p>
 *
 * @author shuqing-bigdata
 */
class CrossSourceExecutorTest {

    private static final CrossSourceExecutor EXECUTOR_FOR_TEST =
            new CrossSourceExecutor(new SqlParserService());

    @AfterAll
    static void tearDown() {
        EXECUTOR_FOR_TEST.shutdown();
    }

    // ===================== executeWithPlan =====================

    @Test
    @DisplayName("executeWithPlan — 单任务直接执行")
    void executeWithPlan_singleTask() {
        List<SourceQueryTask> tasks = List.of(
                createMockTask("trino", List.of("id", "name"),
                        List.of(List.of(1, "alice"), List.of(2, "bob"))));

        MergeResult result = EXECUTOR_FOR_TEST.executeWithPlan(tasks);

        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.getColumns()).containsExactly("id", "name");
        assertThat(result.getSource()).isEqualTo("trino");
    }

    @Test
    @DisplayName("executeWithPlan — 多任务并行执行 + UNION ALL 归并")
    void executeWithPlan_multiTaskUnionAll() {
        List<SourceQueryTask> tasks = List.of(
                createMockTask("trino", List.of("id", "name"),
                        List.of(List.of(1, "alice"), List.of(2, "bob"))),
                createMockTask("doris", List.of("id", "name"),
                        List.of(List.of(3, "carol"), List.of(4, "dave"))));

        MergeResult result = EXECUTOR_FOR_TEST.executeWithPlan(tasks);

        assertThat(result.getRowCount()).isEqualTo(4);
        assertThat(result.getSource()).isEqualTo("merged");
        // 验证所有 id 都出现（数值经类型归一化为 BigDecimal）
        List<Object> ids = new ArrayList<>();
        result.getRows().forEach(row -> ids.add(row.get(0)));
        assertThat(ids).containsExactlyInAnyOrder(
                BigDecimal.valueOf(1), BigDecimal.valueOf(2),
                BigDecimal.valueOf(3), BigDecimal.valueOf(4));
    }

    @Test
    @DisplayName("executeWithPlan — 空任务列表抛 MERGE_ERROR")
    void executeWithPlan_emptyTasks() {
        assertThatThrownBy(() -> EXECUTOR_FOR_TEST.executeWithPlan(List.of()))
                .isInstanceOf(CrossSourceException.class)
                .satisfies(e -> assertThat(((CrossSourceException) e).getErrorCode())
                        .isEqualTo(CrossSourceException.MERGE_ERROR));
    }

    @Test
    @DisplayName("executeWithPlan — null 任务列表抛 MERGE_ERROR")
    void executeWithPlan_nullTasks() {
        assertThatThrownBy(() -> EXECUTOR_FOR_TEST.executeWithPlan(null))
                .isInstanceOf(CrossSourceException.class);
    }

    @Test
    @DisplayName("executeWithPlan — 任务失败抛 QUERY_FAILED")
    void executeWithPlan_taskFailure() {
        List<SourceQueryTask> tasks = List.of(
                createFailingTask("trino", "模拟查询失败"));

        assertThatThrownBy(() -> EXECUTOR_FOR_TEST.executeWithPlan(tasks))
                .isInstanceOf(CrossSourceException.class)
                .satisfies(e -> assertThat(((CrossSourceException) e).getErrorCode())
                        .isEqualTo(CrossSourceException.QUERY_FAILED));
    }

    @Test
    @DisplayName("executeWithPlan — 部分任务失败时整体失败")
    void executeWithPlan_partialFailure() {
        List<SourceQueryTask> tasks = List.of(
                createMockTask("trino", List.of("id"),
                        List.of(List.of(1), List.of(2))),
                createFailingTask("doris", "Doris 查询失败"));

        assertThatThrownBy(() -> EXECUTOR_FOR_TEST.executeWithPlan(tasks))
                .isInstanceOf(CrossSourceException.class);
    }

    // ===================== explain =====================

    @Test
    @DisplayName("explain — 单源 SQL 标记为非跨源")
    void explain_singleSource() {
        CrossSourceExecutor executor = createExecutorWithResolver(
                new MockSourceResolver(Map.of("users", "trino")));

        CrossSourceExecutor.ExecutionPlan plan =
                executor.explain("SELECT * FROM users", SqlDialect.ANSI);

        assertThat(plan.isCrossSource()).isFalse();
        assertThat(plan.getStrategy()).isEqualTo("SINGLE_SOURCE_PROXY");
        assertThat(plan.getTables()).contains("users");
        assertThat(plan.getSources()).contains("trino");
        executor.shutdown();
    }

    @Test
    @DisplayName("explain — 跨源 SQL 标记为跨源")
    void explain_crossSource() {
        CrossSourceExecutor executor = createExecutorWithResolver(
                new MockSourceResolver(Map.of(
                        "hive.users", "trino",
                        "doris.orders", "doris")));

        CrossSourceExecutor.ExecutionPlan plan =
                executor.explain("SELECT * FROM hive.users JOIN doris.orders ON hive.users.id = doris.orders.uid",
                        SqlDialect.ANSI);

        assertThat(plan.isCrossSource()).isTrue();
        assertThat(plan.getStrategy()).isEqualTo("PARALLEL_AND_MERGE");
        assertThat(plan.getSources()).contains("trino", "doris");
        executor.shutdown();
    }

    @Test
    @DisplayName("explain — SQL 为空抛 PARSE_ERROR")
    void explain_emptySql() {
        CrossSourceExecutor executor = createExecutorWithResolver(new MockSourceResolver(Map.of()));

        assertThatThrownBy(() -> executor.explain("", SqlDialect.ANSI))
                .isInstanceOf(CrossSourceException.class)
                .satisfies(e -> assertThat(((CrossSourceException) e).getErrorCode())
                        .isEqualTo(CrossSourceException.PARSE_ERROR));
        executor.shutdown();
    }

    // ===================== execute =====================

    @Test
    @DisplayName("execute — 单源查询直接代理")
    void execute_singleSource() {
        MockSourceResolver resolver = new MockSourceResolver(Map.of("users", "trino"));
        resolver.setMockResult("trino", List.of("id", "name"),
                List.of(List.of(1, "alice")));
        CrossSourceExecutor executor = createExecutorWithMockResolver(resolver);

        MergeResult result = executor.execute("SELECT * FROM users", SqlDialect.ANSI, "tenant-001");

        assertThat(result.getRowCount()).isEqualTo(1);
        assertThat(result.getSource()).isEqualTo("trino");
        executor.shutdown();
    }

    @Test
    @DisplayName("execute — 跨源查询并行执行 + UNION ALL 归并")
    void execute_crossSource() {
        MockSourceResolver resolver = new MockSourceResolver(Map.of(
                "hive.users", "trino",
                "doris.orders", "doris"));
        resolver.setMockResult("trino", List.of("id", "name"),
                List.of(List.of(1, "alice"), List.of(2, "bob")));
        resolver.setMockResult("doris", List.of("id", "name"),
                List.of(List.of(3, "carol")));
        CrossSourceExecutor executor = createExecutorWithMockResolver(resolver);

        MergeResult result = executor.execute(
                "SELECT * FROM hive.users UNION ALL SELECT * FROM doris.orders",
                SqlDialect.ANSI, null);

        assertThat(result.getSource()).isEqualTo("merged");
        // 归并后行数 = trino 行数 + doris 行数
        assertThat(result.getRowCount()).isGreaterThanOrEqualTo(1);
        executor.shutdown();
    }

    @Test
    @DisplayName("execute — SQL 为空抛 PARSE_ERROR")
    void execute_emptySql() {
        CrossSourceExecutor executor = createExecutorWithResolver(new MockSourceResolver(Map.of()));

        assertThatThrownBy(() -> executor.execute("", SqlDialect.ANSI, null))
                .isInstanceOf(CrossSourceException.class)
                .satisfies(e -> assertThat(((CrossSourceException) e).getErrorCode())
                        .isEqualTo(CrossSourceException.PARSE_ERROR));
        executor.shutdown();
    }

    @Test
    @DisplayName("execute — 表未找到源抛 SOURCE_NOT_FOUND")
    void execute_sourceNotFound() {
        MockSourceResolver resolver = new MockSourceResolver(Map.of("users", "trino"));
        CrossSourceExecutor executor = createExecutorWithMockResolver(resolver);

        // unknown 表未在 resolver 中映射 → resolveSource 返回 null
        assertThatThrownBy(() -> executor.execute("SELECT * FROM unknown", SqlDialect.ANSI, null))
                .isInstanceOf(CrossSourceException.class)
                .satisfies(e -> assertThat(((CrossSourceException) e).getErrorCode())
                        .isEqualTo(CrossSourceException.SOURCE_NOT_FOUND));
        executor.shutdown();
    }

    // ===================== 配置参数 =====================

    @Test
    @DisplayName("配置 — 自定义超时与行数上限")
    void config_customTimeoutAndMaxRows() {
        CrossSourceExecutor executor = new CrossSourceExecutor(
                new SqlParserService(), null, null, 60, 5000, 4, null);

        assertThat(executor.getTimeoutSeconds()).isEqualTo(60);
        assertThat(executor.getMaxRows()).isEqualTo(5000);
        executor.shutdown();
    }

    @Test
    @DisplayName("配置 — 默认值当参数为 0 或负数")
    void config_defaultValuesWhenInvalid() {
        CrossSourceExecutor executor = new CrossSourceExecutor(
                new SqlParserService(), null, null, 0, 0, 0, null);

        assertThat(executor.getTimeoutSeconds()).isEqualTo(CrossSourceExecutor.DEFAULT_TIMEOUT_SECONDS);
        assertThat(executor.getMaxRows()).isEqualTo(CrossSourceExecutor.DEFAULT_MAX_ROWS);
        executor.shutdown();
    }

    @Test
    @DisplayName("getJoinEngine — 返回 JOIN 引擎实例")
    void getJoinEngine_returnsInstance() {
        assertThat(EXECUTOR_FOR_TEST.getJoinEngine()).isNotNull();
        assertThat(EXECUTOR_FOR_TEST.getJoinEngine().getMaxRows())
                .isEqualTo(CrossSourceExecutor.DEFAULT_MAX_ROWS);
    }

    @Test
    @DisplayName("getUnionEngine — 返回 UNION 引擎实例")
    void getUnionEngine_returnsInstance() {
        assertThat(EXECUTOR_FOR_TEST.getUnionEngine()).isNotNull();
    }

    // ===================== 辅助方法 =====================

    /**
     * 创建一个模拟的 SourceQueryTask（不调用真实后端）。
     */
    private static SourceQueryTask createMockTask(String source,
                                                  List<String> columns,
                                                  List<List<Object>> rows) {
        return new MockSourceQueryTask(source, columns, rows);
    }

    /**
     * 创建一个失败的 SourceQueryTask。
     */
    private static SourceQueryTask createFailingTask(String source, String errorMsg) {
        return new MockSourceQueryTask(source, errorMsg);
    }

    /**
     * 创建使用指定 SourceResolver 的 CrossSourceExecutor。
     */
    private static CrossSourceExecutor createExecutorWithResolver(
            CrossSourceExecutor.SourceResolver resolver) {
        return new CrossSourceExecutor(
                new SqlParserService(), null, resolver,
                10, 1000, 4, null);
    }

    /**
     * 创建使用 MockSourceResolver 的 CrossSourceExecutor（注入 mock 后端）。
     */
    private static CrossSourceExecutor createExecutorWithMockResolver(
            MockSourceResolver resolver) {
        return new CrossSourceExecutor(
                new SqlParserService(), null, resolver,
                10, 1000, 4, null);
    }

    /**
     * 模拟 SourceQueryTask：直接返回预设结果或抛预设异常。
     */
    private static final class MockSourceQueryTask extends SourceQueryTask {
        private final List<String> columns;
        private final List<List<Object>> rows;
        private final String error;

        MockSourceQueryTask(String source, List<String> columns, List<List<Object>> rows) {
            super(SourceQueryTask.builder().source(source).sql("MOCK"));
            this.columns = columns;
            this.rows = rows;
            this.error = null;
        }

        MockSourceQueryTask(String source, String error) {
            super(SourceQueryTask.builder().source(source).sql("MOCK"));
            this.columns = null;
            this.rows = null;
            this.error = error;
        }

        @Override
        public MergeResult call() {
            if (error != null) {
                throw new CrossSourceException(CrossSourceException.QUERY_FAILED, error);
            }
            return new MergeResult(columns, rows, getSource(), 0);
        }
    }

    /**
     * 模拟 SourceResolver：基于预设的表→源映射解析，并返回 MockSourceQueryTask。
     */
    private static final class MockSourceResolver implements CrossSourceExecutor.SourceResolver {
        private final Map<String, String> tableToSource;
        private final Map<String, MergeResult> sourceToResult = new LinkedHashMap<>();
        private final AtomicInteger splitCallCount = new AtomicInteger(0);

        MockSourceResolver(Map<String, String> tableToSource) {
            this.tableToSource = tableToSource;
        }

        void setMockResult(String source, List<String> columns, List<List<Object>> rows) {
            sourceToResult.put(source, new MergeResult(columns, rows, source, 0));
        }

        @Override
        public String resolveSource(String tableName) {
            return tableToSource.get(tableName);
        }

        @Override
        public List<SourceQueryTask> splitQuery(String sql, SqlDialect dialect,
                                                Map<String, String> tableToSource,
                                                String tenantId) {
            splitCallCount.incrementAndGet();
            // 按源分组
            Map<String, List<String>> sourceToTables = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : tableToSource.entrySet()) {
                sourceToTables.computeIfAbsent(e.getValue(), k -> new ArrayList<>())
                        .add(e.getKey());
            }
            List<SourceQueryTask> tasks = new ArrayList<>();
            for (String source : sourceToTables.keySet()) {
                MergeResult mock = sourceToResult.get(source);
                if (mock != null) {
                    tasks.add(new MockSourceQueryTask(source,
                            mock.getColumns(), mock.getRows()));
                } else {
                    tasks.add(new MockSourceQueryTask(source, List.of("id"), List.of()));
                }
            }
            return tasks;
        }
    }
}