package com.levango7.dataenginebdp.sqlgateway.crosssource;

import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteResponse;
import com.levango7.dataenginebdp.sqlgateway.service.BackendProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 单源查询任务。
 *
 * <p>封装对单个数据源（Trino/Doris）的一次查询，实现 {@link Callable} 以便
 * 通过 {@code ExecutorService} 并行执行。内置超时控制与重试机制，
 * 调用 {@link BackendProxyService} 完成实际 HTTP 代理。</p>
 *
 * <p>使用方式：</p>
 * <pre>
 *   SourceQueryTask task = SourceQueryTask.builder()
 *       .source("trino")
 *       .sql("SELECT id, name FROM users")
 *       .tenantId("tenant-001")
 *       .timeoutSeconds(30)
 *       .maxRetry(2)
 *       .backendProxyService(backendProxyService)
 *       .build();
 *   MergeResult result = task.call();
 * </pre>
 *
 * @author shuqing-bigdata
 */
public class SourceQueryTask implements Callable<MergeResult> {

    private static final Logger log = LoggerFactory.getLogger(SourceQueryTask.class);

    /** 默认超时（秒） */
    public static final long DEFAULT_TIMEOUT_SECONDS = 30L;
    /** 默认重试次数 */
    public static final int DEFAULT_MAX_RETRY = 0;

    private final String source;
    private final String sql;
    private final String tenantId;
    private final long timeoutSeconds;
    private final int maxRetry;
    private final BackendProxyService backendProxyService;

    /**
     * 受保护构造函数（供测试子类继承覆盖 {@link #call()}）。
     *
     * @param builder 构造器
     */
    protected SourceQueryTask(Builder builder) {
        this.source = builder.source;
        this.sql = builder.sql;
        this.tenantId = builder.tenantId;
        this.timeoutSeconds = builder.timeoutSeconds > 0 ? builder.timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        this.maxRetry = builder.maxRetry >= 0 ? builder.maxRetry : DEFAULT_MAX_RETRY;
        this.backendProxyService = builder.backendProxyService;
    }

    /**

     * 执行单源查询。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>根据 {@code source} 选择 {@code proxyToTrino} 或 {@code proxyToDoris}；</li>
     *   <li>通过 {@code Mono.block(Duration)} 同步阻塞等待结果，超时抛 {@link CrossSourceException}；</li>
     *   <li>失败时按 {@code maxRetry} 重试；</li>
     *   <li>将 {@link SqlExecuteResponse} 转换为 {@link MergeResult} 返回。</li>
     * </ol>
     *
     * @return 归并结果
     * @throws CrossSourceException 查询超时或失败
     */
    @Override
    public MergeResult call() {
        if (backendProxyService == null) {
            throw new CrossSourceException(CrossSourceException.UNSUPPORTED,
                    "BackendProxyService 未注入，无法执行单源查询: source=" + source);
        }
        if (sql == null || sql.isBlank()) {
            throw new CrossSourceException(CrossSourceException.PARSE_ERROR,
                    "SQL 不能为空: source=" + source);
        }

        long start = System.currentTimeMillis();
        Exception lastError = null;

        int attempts = maxRetry + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                SqlExecuteResponse resp = executeOnce();
                long duration = System.currentTimeMillis() - start;
                return toMergeResult(resp, duration);
            } catch (CrossSourceException e) {
                // 超时错误不重试
                if (CrossSourceException.QUERY_TIMEOUT.equals(e.getErrorCode())) {
                    throw e;
                }
                lastError = e;
                log.warn("单源查询失败 attempt={}/{} source={} err={}",
                        attempt, attempts, source, e.getMessage());
            } catch (Exception e) {
                lastError = e;
                log.warn("单源查询异常 attempt={}/{} source={} err={}",
                        attempt, attempts, source, e.toString());
            }
        }

        long duration = System.currentTimeMillis() - start;
        throw new CrossSourceException(CrossSourceException.QUERY_FAILED,
                "单源查询失败（已重试 " + maxRetry + " 次）: source=" + source
                        + ", durationMs=" + duration + ", err=" + (lastError == null ? "unknown" : lastError.getMessage()),
                lastError);
    }

    /**
     * 执行一次查询（不重试）。
     *
     * @return 后端响应
     */
    private SqlExecuteResponse executeOnce() {
        try {
            SqlExecuteResponse resp;
            if ("doris".equalsIgnoreCase(source)) {
                resp = backendProxyService.proxyToDoris(sql, tenantId)
                        .block(Duration.ofSeconds(timeoutSeconds));
            } else if ("trino".equalsIgnoreCase(source)
                    || "hive".equalsIgnoreCase(source)) {
                // Hive 通过 Trino 代理（Trino 配置 hive connector）
                resp = backendProxyService.proxyToTrino(sql, tenantId)
                        .block(Duration.ofSeconds(timeoutSeconds));
            } else {
                // 默认走 Trino
                resp = backendProxyService.proxyToTrino(sql, tenantId)
                        .block(Duration.ofSeconds(timeoutSeconds));
            }
            if (resp == null) {
                throw new CrossSourceException(CrossSourceException.QUERY_FAILED,
                        "后端返回空响应: source=" + source);
            }
            if ("FAILED".equalsIgnoreCase(resp.getStatus())
                    || "DEGRADED".equalsIgnoreCase(resp.getStatus())) {
                throw new CrossSourceException(CrossSourceException.QUERY_FAILED,
                        "后端返回失败状态: source=" + source + ", status=" + resp.getStatus());
            }
            return resp;
        } catch (IllegalStateException e) {
            // Mono.block 超时抛 IllegalStateException
            throw new CrossSourceException(CrossSourceException.QUERY_TIMEOUT,
                    "单源查询超时: source=" + source + ", timeout=" + timeoutSeconds + "s", e);
        }
    }

    /**
     * 将 SqlExecuteResponse 转为 MergeResult。
     *
     * @param resp     后端响应
     * @param duration 耗时
     * @return 归并结果
     */
    private MergeResult toMergeResult(SqlExecuteResponse resp, long duration) {
        List<String> cols = resp.getColumns() == null
                ? new ArrayList<>() : new ArrayList<>(resp.getColumns());
        List<List<Object>> rows = resp.getRows() == null
                ? new ArrayList<>() : new ArrayList<>(resp.getRows());
        return new MergeResult(cols, rows, source, duration);
    }

    /**
     * 获取源标识。
     *
     * @return 源标识
     */
    public String getSource() {
        return source;
    }

    /**
     * 获取 SQL。
     *
     * @return SQL
     */
    public String getSql() {
        return sql;
    }

    /**
     * 构造器模式。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder */
    public static final class Builder {
        private String source;
        private String sql;
        private String tenantId;
        private long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        private int maxRetry = DEFAULT_MAX_RETRY;
        private BackendProxyService backendProxyService;

        /**
         * 设置源标识。
         *
         * @param source 源标识
         * @return this
         */
        public Builder source(String source) {
            this.source = source;
            return this;
        }

        /**
         * 设置 SQL。
         *
         * @param sql SQL
         * @return this
         */
        public Builder sql(String sql) {
            this.sql = sql;
            return this;
        }

        /**
         * 设置租户 ID。
         *
         * @param tenantId 租户 ID
         * @return this
         */
        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        /**
         * 设置超时（秒）。
         *
         * @param seconds 超时秒数
         * @return this
         */
        public Builder timeoutSeconds(long seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }

        /**
         * 设置最大重试次数。
         *
         * @param maxRetry 重试次数
         * @return this
         */
        public Builder maxRetry(int maxRetry) {
            this.maxRetry = maxRetry;
            return this;
        }

        /**
         * 注入后端代理服务。
         *
         * @param service 后端代理服务
         * @return this
         */
        public Builder backendProxyService(BackendProxyService service) {
            this.backendProxyService = service;
            return this;
        }

        /**
         * 构建 SourceQueryTask。
         *
         * @return 任务实例
         */
        public SourceQueryTask build() {
            return new SourceQueryTask(this);
        }
    }
}