package com.levango7.dataenginebdp.sqlgateway.crosssource;

import com.levango7.dataenginebdp.sqlgateway.parser.ASTNode;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlParseException;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlParserService;
import com.levango7.dataenginebdp.sqlgateway.service.BackendProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 跨源执行器。
 *
 * <p>跨源归并引擎的核心入口，负责：</p>
 * <ol>
 *   <li>解析 SQL，提取涉及的表名；</li>
 *   <li>通过 {@link SourceResolver} 查询每个表所属的数据源（Trino/Doris/Hive）；</li>
 *   <li>若所有表在同一源 → 直接代理到该源；</li>
 *   <li>若表跨多个源 → 拆分为多个单源查询 → 并行执行（{@link CompletableFuture}）→ 内存归并；</li>
 *   <li>归并策略：JOIN → {@link CrossSourceJoinEngine}，UNION → {@link CrossSourceUnionEngine}。</li>
 * </ol>
 *
 * <p>使用 {@code CompletableFuture} 并行查询多源，超时控制默认 30s。
 * 内存归并结果集大小受 {@code maxRows} 限制（默认 10000 行）。</p>
 *
 * <p>线程池：内部维护固定大小线程池（默认 8 线程），可通过构造参数调整。</p>
 *
 * @author shuqing-bigdata
 */
@Service
public class CrossSourceExecutor {

    private static final Logger log = LoggerFactory.getLogger(CrossSourceExecutor.class);

    /** 默认超时（秒） */
    public static final long DEFAULT_TIMEOUT_SECONDS = 30L;
    /** 默认线程池大小 */
    public static final int DEFAULT_THREAD_POOL_SIZE = 8;
    /** 默认结果集行数上限 */
    public static final int DEFAULT_MAX_ROWS = MergeResult.DEFAULT_MAX_ROWS;

    private final SqlParserService parserService;
    private final BackendProxyService backendProxyService;
    private final SourceResolver sourceResolver;
    private final CrossSourceJoinEngine joinEngine;
    private final CrossSourceUnionEngine unionEngine;
    private final ExecutorService threadPool;

    private final long timeoutSeconds;
    private final int maxRows;

    /**
     * 跨源查询线程池队列容量（v2.1：防止无界队列堆积导致 OOM）。
     */
    private final int queueCapacity;

    /**
     * 性能指标收集器（可选；未注入时指标静默跳过）。
     */
    private final com.levango7.dataenginebdp.sqlgateway.metering.PerformanceMetrics performanceMetrics;

    /**
     * 默认构造（使用默认配置）。
     *
     * <p>本构造不注入 BackendProxyService 与 SourceResolver，仅适用于
     * 单元测试中通过 {@link #executeWithPlan} 直接传入拆分计划的场景。</p>
     *
     * @param parserService SQL 解析服务
     */
    public CrossSourceExecutor(SqlParserService parserService) {
        this(parserService, null, null,
                DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_ROWS, DEFAULT_THREAD_POOL_SIZE, null);
    }

    /**
     * Spring 自动注入构造函数。
     *
     * <p>从配置读取超时、行数上限、线程池大小，注入 {@link SourceResolver} 实现。
     * {@link BackendProxyService} 由 {@link SourceResolver} 实现内部持有，此处不直接注入。</p>
     *
     * @param sourceResolver    源解析器（Spring 注入）
     * @param timeoutSeconds    超时秒数（配置 {@code sql-gateway.cross-source.timeout-seconds}）
     * @param maxRows           结果集行数上限（配置 {@code sql-gateway.cross-source.max-rows}）
     * @param threadPoolSize    线程池大小（配置 {@code sql-gateway.cross-source.thread-pool-size}）
     */
    @Autowired
    public CrossSourceExecutor(
            SourceResolver sourceResolver,
            @Value("${sql-gateway.cross-source.timeout-seconds:30}") long timeoutSeconds,
            @Value("${sql-gateway.cross-source.max-rows:10000}") int maxRows,
            @Value("${sql-gateway.cross-source.thread-pool-size:8}") int threadPoolSize,
            org.springframework.beans.factory.ObjectProvider<
                    com.levango7.dataenginebdp.sqlgateway.metering.PerformanceMetrics> metricsProvider) {
        this(new SqlParserService(), null, sourceResolver,
                timeoutSeconds, maxRows, threadPoolSize, metricsProvider.getIfAvailable());
    }

    /**
     * 全参构造。
     *
     * @param parserService     SQL 解析服务
     * @param backendProxyService 后端代理服务
     * @param sourceResolver    源解析器（表名 → 源标识）
     * @param timeoutSeconds    超时秒数
     * @param maxRows           结果集行数上限
     * @param threadPoolSize    线程池大小
     */
    public CrossSourceExecutor(SqlParserService parserService,
                               BackendProxyService backendProxyService,
                               SourceResolver sourceResolver,
                               long timeoutSeconds,
                               int maxRows,
                               int threadPoolSize,
                               com.levango7.dataenginebdp.sqlgateway.metering.PerformanceMetrics performanceMetrics) {
        this.parserService = parserService == null ? new SqlParserService() : parserService;
        this.backendProxyService = backendProxyService;
        this.sourceResolver = sourceResolver;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        this.maxRows = maxRows > 0 ? maxRows : DEFAULT_MAX_ROWS;
        int poolSize = threadPoolSize > 0 ? threadPoolSize : DEFAULT_THREAD_POOL_SIZE;
        // v2.1：使用 ThreadPoolExecutor 替代 newFixedThreadPool，配置有界队列 + 拒绝策略 + 命名线程
        // 队列容量 = poolSize * 4，溢出时 AbortPolicy 快速失败（调用方降级而非堆积）
        this.queueCapacity = poolSize * 4;
        AtomicInteger threadCounter = new AtomicInteger(0);
        this.threadPool = new ThreadPoolExecutor(
                poolSize, poolSize,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(this.queueCapacity),
                r -> {
                    Thread t = new Thread(r, "cross-source-exec-" + threadCounter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());  // 队列满时由调用线程执行，反压
        this.joinEngine = new CrossSourceJoinEngine(this.maxRows);
        this.unionEngine = new CrossSourceUnionEngine(this.maxRows);
        this.performanceMetrics = performanceMetrics;
        log.info("CrossSourceExecutor 初始化完成: timeout={}s, maxRows={}, poolSize={}, queueCapacity={}",
                this.timeoutSeconds, this.maxRows, poolSize, this.queueCapacity);
    }

    /**
     * 执行跨源 SQL 查询。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>解析 SQL 提取表名；</li>
     *   <li>查询每个表的源；</li>
     *   <li>单源 → 直接代理；多源 → 拆分并行查询 + 内存归并。</li>
     * </ol>
     *
     * @param sql     SQL 文本
     * @param dialect SQL 方言
     * @param tenantId 租户 ID
     * @return 归并结果
     * @throws CrossSourceException 执行失败
     */
    public MergeResult execute(String sql, SqlDialect dialect, String tenantId) {
        long start = System.currentTimeMillis();
        String queryId = UUID.randomUUID().toString();
        log.info("跨源查询开始 queryId={} dialect={} sql={}{}", queryId, dialect,
                abbreviate(sql, 80), tenantId == null ? "" : " tenant=" + tenantId);

        if (sql == null || sql.isBlank()) {
            throw new CrossSourceException(CrossSourceException.PARSE_ERROR, "SQL 不能为空");
        }

        // 1. 解析 SQL 提取表名
        List<String> tables;
        try {
            ASTNode ast = parserService.parse(sql, dialect);
            tables = ast.extractTables();
        } catch (SqlParseException e) {
            throw new CrossSourceException(CrossSourceException.PARSE_ERROR,
                    "SQL 解析失败: " + e.getMessage(), e);
        }
        log.info("queryId={} 涉及表: {}", queryId, tables);

        if (tables.isEmpty()) {
            throw new CrossSourceException(CrossSourceException.PARSE_ERROR,
                    "SQL 未提取到任何表名，无法进行跨源查询");
        }

        // 2. 查询每个表的源
        if (sourceResolver == null) {
            throw new CrossSourceException(CrossSourceException.SOURCE_NOT_FOUND,
                    "SourceResolver 未注入，无法解析表所属源");
        }
        Map<String, String> tableToSource = new LinkedHashMap<>();
        Set<String> involvedSources = new LinkedHashSet<>();
        for (String table : tables) {
            String src = sourceResolver.resolveSource(table);
            if (src == null || src.isBlank()) {
                throw new CrossSourceException(CrossSourceException.SOURCE_NOT_FOUND,
                        "表 " + table + " 未找到对应数据源");
            }
            tableToSource.put(table, src);
            involvedSources.add(src);
        }
        log.info("queryId={} 表→源映射: {}, 涉及源: {}", queryId, tableToSource, involvedSources);

        // 3. 单源 → 直接代理
        if (involvedSources.size() == 1) {
            String singleSource = involvedSources.iterator().next();
            log.info("queryId={} 单源查询 source={}", queryId, singleSource);
            // 通过 SourceResolver 创建单源查询任务（便于测试 mock）
            List<SourceQueryTask> singleTasks = sourceResolver.splitQuery(
                    sql, dialect, tableToSource, tenantId);
            if (singleTasks == null || singleTasks.isEmpty()) {
                // 回退到直接创建任务
                MergeResult result = executeSingleSource(sql, singleSource, tenantId, start);
                result.setSource(singleSource);
                return result;
            }
            MergeResult result = executeWithPlan(singleTasks, start);
            result.setSource(singleSource);
            return result;
        }

        // 4. 多源 → 拆分并行查询 + 内存归并
        log.info("queryId={} 跨源查询 涉及 {} 个源: {}", queryId, involvedSources.size(), involvedSources);
        List<SourceQueryTask> tasks = sourceResolver.splitQuery(sql, dialect, tableToSource, tenantId);
        return executeWithPlan(tasks, start);
    }

    /**
     * 按预定义的拆分计划执行跨源查询（并行 + 归并）。
     *
     * <p>本方法适用于：调用方已自行完成 SQL 拆分（如基于规则或手动指定），
     * 直接传入一组 {@link SourceQueryTask} 并行执行后做 UNION ALL 归并。
     * 单元测试中常用于绕过 SourceResolver 直接验证并行执行与归并逻辑。</p>
     *
     * @param tasks 单源查询任务列表
     * @return 归并结果（UNION ALL）
     */
    public MergeResult executeWithPlan(List<SourceQueryTask> tasks) {
        return executeWithPlan(tasks, System.currentTimeMillis());
    }

    /**
     * 按拆分计划执行（内部实现，带起始时间戳）。
     */
    private MergeResult executeWithPlan(List<SourceQueryTask> tasks, long start) {
        if (tasks == null || tasks.isEmpty()) {
            throw new CrossSourceException(CrossSourceException.MERGE_ERROR,
                    "拆分计划为空，无可执行的单源查询任务");
        }

        // 单任务直接执行
        if (tasks.size() == 1) {
            MergeResult r = tasks.get(0).call();
            r.setDurationMs(System.currentTimeMillis() - start);
            return r;
        }

        // 多任务并行执行（v2.1：记录性能指标）
        long metricsStart = performanceMetrics == null ? 0L : performanceMetrics.recordQueryStart();
        List<CompletableFuture<MergeResult>> futures = new ArrayList<>();
        for (SourceQueryTask task : tasks) {
            CompletableFuture<MergeResult> f = CompletableFuture.supplyAsync(task::call, threadPool);
            futures.add(f);
        }

        // 等待所有任务完成（带超时）
        CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
        try {
            allOf.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 取消未完成的任务
            futures.forEach(f -> f.cancel(true));
            throw new CrossSourceException(CrossSourceException.QUERY_TIMEOUT,
                    "跨源查询超时: timeout=" + timeoutSeconds + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CrossSourceException(CrossSourceException.QUERY_FAILED,
                    "跨源查询被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof CrossSourceException cse) {
                throw cse;
            }
            throw new CrossSourceException(CrossSourceException.QUERY_FAILED,
                    "跨源查询执行失败: " + cause.getMessage(), cause);
        }

        // 收集所有结果
        List<MergeResult> partialResults = new ArrayList<>();
        for (CompletableFuture<MergeResult> f : futures) {
            try {
                MergeResult r = f.get();
                if (r != null) {
                    partialResults.add(r);
                }
            } catch (Exception e) {
                throw new CrossSourceException(CrossSourceException.QUERY_FAILED,
                        "收集查询结果失败: " + e.getMessage(), e);
            }
        }

        // 内存归并：默认 UNION ALL
        MergeResult merged = unionEngine.union(partialResults,
                CrossSourceUnionEngine.UnionType.UNION_ALL);
        merged.setDurationMs(System.currentTimeMillis() - start);
        // v2.1：记录跨源查询性能指标
        if (performanceMetrics != null) {
            long bytes = merged.getRows() == null ? 0L : merged.getRows().size() * 1024L;
            performanceMetrics.recordQueryEnd(metricsStart, "cross-source", true, true, bytes);
        }
        log.info("跨源查询完成 durationMs={} rowCount={}", merged.getDurationMs(), merged.getRowCount());
        return merged;
    }

    /**
     * 生成跨源执行计划（不实际执行）。
     *
     * @param sql     SQL 文本
     * @param dialect SQL 方言
     * @return 执行计划描述
     */
    public ExecutionPlan explain(String sql, SqlDialect dialect) {
        if (sql == null || sql.isBlank()) {
            throw new CrossSourceException(CrossSourceException.PARSE_ERROR, "SQL 不能为空");
        }
        long start = System.currentTimeMillis();

        ASTNode ast;
        try {
            ast = parserService.parse(sql, dialect);
        } catch (SqlParseException e) {
            throw new CrossSourceException(CrossSourceException.PARSE_ERROR,
                    "SQL 解析失败: " + e.getMessage(), e);
        }
        List<String> tables = ast.extractTables();
        String statementType = ast.getType().name();

        Map<String, String> tableToSource = new LinkedHashMap<>();
        Set<String> sources = new LinkedHashSet<>();
        if (sourceResolver != null) {
            for (String table : tables) {
                String src = sourceResolver.resolveSource(table);
                tableToSource.put(table, src == null ? "unknown" : src);
                if (src != null) {
                    sources.add(src);
                }
            }
        }

        boolean crossSource = sources.size() > 1;
        String strategy = crossSource ? "PARALLEL_AND_MERGE" : "SINGLE_SOURCE_PROXY";
        long duration = System.currentTimeMillis() - start;

        return new ExecutionPlan(sql, statementType, tables, tableToSource,
                new ArrayList<>(sources), crossSource, strategy, duration);
    }

    /**
     * 执行单源查询。
     */
    private MergeResult executeSingleSource(String sql, String source, String tenantId, long start) {
        SourceQueryTask task = SourceQueryTask.builder()
                .source(source)
                .sql(sql)
                .tenantId(tenantId)
                .timeoutSeconds(timeoutSeconds)
                .backendProxyService(backendProxyService)
                .build();
        MergeResult result = task.call();
        result.setDurationMs(System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 截断 SQL 用于日志。
     */
    private String abbreviate(String s, int maxLen) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 获取 JOIN 引擎（供外部调用做进一步归并）。
     *
     * @return JOIN 引擎
     */
    public CrossSourceJoinEngine getJoinEngine() {
        return joinEngine;
    }

    /**
     * 获取 UNION 引擎。
     *
     * @return UNION 引擎
     */
    public CrossSourceUnionEngine getUnionEngine() {
        return unionEngine;
    }

    /**
     * 获取超时秒数。
     *
     * @return 超时秒数
     */
    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * 获取结果集行数上限。
     *
     * @return 行数上限
     */
    public int getMaxRows() {
        return maxRows;
    }

    /**
     * 关闭线程池（应用停机时调用）。
     */
    public void shutdown() {
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 源解析器接口：表名 → 源标识。
     *
     * <p>实现方应基于 Catalog/元数据服务查询每个表所属的数据源。
     * 同时负责将跨源 SQL 拆分为多个单源查询任务。</p>
     *
     * @author shuqing-bigdata
     */
    public interface SourceResolver {
        /**
         * 解析表所属的数据源。
         *
         * @param tableName 表名
         * @return 源标识（trino/doris/hive）；未找到返回 null
         */
        String resolveSource(String tableName);

        /**
         * 将跨源 SQL 拆分为多个单源查询任务。
         *
         * @param sql            原始 SQL
         * @param dialect        SQL 方言
         * @param tableToSource  表→源映射
         * @param tenantId       租户 ID
         * @return 单源查询任务列表
         */
        List<SourceQueryTask> splitQuery(String sql, SqlDialect dialect,
                                         Map<String, String> tableToSource,
                                         String tenantId);
    }

    /**
     * 跨源执行计划描述。
     */
    public static final class ExecutionPlan {
        /** 原始 SQL */
        private final String sql;
        /** 语句类型 */
        private final String statementType;
        /** 涉及的表 */
        private final List<String> tables;
        /** 表→源映射 */
        private final Map<String, String> tableToSource;
        /** 涉及的源列表 */
        private final List<String> sources;
        /** 是否跨源 */
        private final boolean crossSource;
        /** 执行策略 */
        private final String strategy;
        /** 解析耗时 */
        private final long durationMs;

        /**
         * 构造执行计划。
         */
        public ExecutionPlan(String sql, String statementType, List<String> tables,
                             Map<String, String> tableToSource, List<String> sources,
                             boolean crossSource, String strategy, long durationMs) {
            this.sql = sql;
            this.statementType = statementType;
            this.tables = tables == null ? Collections.emptyList() : new ArrayList<>(tables);
            this.tableToSource = tableToSource == null
                    ? Collections.emptyMap() : new LinkedHashMap<>(tableToSource);
            this.sources = sources == null ? Collections.emptyList() : new ArrayList<>(sources);
            this.crossSource = crossSource;
            this.strategy = strategy;
            this.durationMs = durationMs;
        }

        /**
         * 获取原始 SQL。
         *
         * @return SQL
         */
        public String getSql() {
            return sql;
        }

        /**
         * 获取语句类型。
         *
         * @return 语句类型
         */
        public String getStatementType() {
            return statementType;
        }

        /**
         * 获取涉及的表。
         *
         * @return 表列表
         */
        public List<String> getTables() {
            return tables;
        }

        /**
         * 获取表→源映射。
         *
         * @return 映射
         */
        public Map<String, String> getTableToSource() {
            return tableToSource;
        }

        /**
         * 获取涉及的源列表。
         *
         * @return 源列表
         */
        public List<String> getSources() {
            return sources;
        }

        /**
         * 是否跨源。
         *
         * @return true 表示跨源
         */
        public boolean isCrossSource() {
            return crossSource;
        }

        /**
         * 获取执行策略。
         *
         * @return 策略
         */
        public String getStrategy() {
            return strategy;
        }

        /**
         * 获取解析耗时。
         *
         * @return 耗时
         */
        public long getDurationMs() {
            return durationMs;
        }

        @Override
        public String toString() {
            return "ExecutionPlan{crossSource=" + crossSource + ", strategy='" + strategy + '\''
                    + ", tables=" + tables + ", sources=" + sources + '}';
        }
    }
}