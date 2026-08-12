package com.levango7.dataenginebdp.flinkcdc.materializedview.refresh;

import com.levango7.dataenginebdp.flinkcdc.materializedview.config.MaterializedViewConfig;
import com.levango7.dataenginebdp.flinkcdc.materializedview.model.MaterializedViewDef;
import com.levango7.dataenginebdp.flinkcdc.materializedview.trigger.RefreshEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 物化视图刷新执行器：接收 {@link RefreshEvent}，执行实际的物化视图刷新操作。
 *
 * <p>刷新流程：</p>
 * <ol>
 *   <li>根据事件中的视图名称查找 {@link MaterializedViewDef}</li>
 *   <li>生成刷新 SQL（全量重算或增量合并）</li>
 *   <li>通过 Doris Stream Load / JDBC 执行刷新</li>
 *   <li>记录刷新结果（成功/失败、耗时、重试次数）</li>
 * </ol>
 *
 * <p>失败重试：根据 {@link com.levango7.dataenginebdp.flinkcdc.materializedview.model.RefreshPolicy#isAutoRetry()}
 * 配置，最多重试 {@link com.levango7.dataenginebdp.flinkcdc.materializedview.model.RefreshPolicy#getMaxRetries()} 次。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * ViewRefresher refresher = new ViewRefresher(viewDefRegistry, config, sqlExecutor);
 * refresher.refresh(RefreshEvent.scheduled("mv_order_summary"));
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public class ViewRefresher {

    private static final Logger log = LoggerFactory.getLogger(ViewRefresher.class);

    /** 物化视图定义查找函数：viewName → MaterializedViewDef。 */
    private final Function<String, MaterializedViewDef> viewDefResolver;

    /** 全局配置。 */
    private final MaterializedViewConfig config;

    /** SQL 执行器：接收 SQL 字符串，返回是否成功。 */
    private final Function<String, Boolean> sqlExecutor;

    /** 刷新结果记录：视图名称 → 最近一次结果。 */
    private final ConcurrentHashMap<String, RefreshResult> lastResults = new ConcurrentHashMap<>();

    /** 正在刷新中的视图计数（用于并发控制）。 */
    private final AtomicInteger activeRefreshCount = new AtomicInteger(0);

    /**
     * 刷新结果记录。
     */
    public static class RefreshResult {
        /** 是否成功。 */
        private final boolean success;
        /** 刷新耗时（毫秒）。 */
        private final long durationMs;
        /** 重试次数。 */
        private final int retryCount;
        /** 错误信息（失败时）。 */
        private final String errorMessage;
        /** 完成时间戳。 */
        private final Instant completedAt;

        public RefreshResult(boolean success, long durationMs, int retryCount,
                             String errorMessage, Instant completedAt) {
            this.success = success;
            this.durationMs = durationMs;
            this.retryCount = retryCount;
            this.errorMessage = errorMessage;
            this.completedAt = completedAt;
        }

        public boolean isSuccess() {
            return success;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public Instant getCompletedAt() {
            return completedAt;
        }

        @Override
        public String toString() {
            return "RefreshResult{success=" + success + ", durationMs=" + durationMs
                    + ", retryCount=" + retryCount
                    + ", errorMessage='" + errorMessage + "'"
                    + ", completedAt=" + completedAt + '}';
        }
    }

    /**
     * 构造器。
     *
     * @param viewDefResolver 物化视图定义查找函数
     * @param config          全局配置
     * @param sqlExecutor     SQL 执行器（接收 SQL，返回是否执行成功）
     */
    public ViewRefresher(Function<String, MaterializedViewDef> viewDefResolver,
                         MaterializedViewConfig config,
                         Function<String, Boolean> sqlExecutor) {
        this.viewDefResolver = Objects.requireNonNull(viewDefResolver, "视图定义查找函数不能为 null");
        this.config = Objects.requireNonNull(config, "配置不能为 null");
        this.sqlExecutor = Objects.requireNonNull(sqlExecutor, "SQL 执行器不能为 null");
    }

    /**
     * 执行一次物化视图刷新。
     *
     * @param event 刷新事件
     * @return 刷新结果；若视图未找到返回失败结果
     */
    public RefreshResult refresh(RefreshEvent event) {
        Objects.requireNonNull(event, "RefreshEvent 不能为 null");
        String viewName = event.getViewName();
        log.info("开始刷新物化视图: {}，来源: {}，原因: {}", viewName, event.getSource(), event.getReason());

        MaterializedViewDef def = event.getViewDef() != null
                ? event.getViewDef()
                : viewDefResolver.apply(viewName);
        if (def == null) {
            RefreshResult result = new RefreshResult(false, 0, 0,
                    "物化视图定义未找到: " + viewName, Instant.now());
            lastResults.put(viewName, result);
            log.error("物化视图定义未找到: {}", viewName);
            return result;
        }

        activeRefreshCount.incrementAndGet();
        try {
            return doRefreshWithRetry(viewName, def);
        } finally {
            activeRefreshCount.decrementAndGet();
        }
    }

    /**
     * 带重试的刷新执行。
     *
     * @param viewName 视图名称
     * @param def      视图定义
     * @return 刷新结果
     */
    private RefreshResult doRefreshWithRetry(String viewName, MaterializedViewDef def) {
        boolean autoRetry = def.getRefreshPolicy() != null && def.getRefreshPolicy().isAutoRetry();
        int maxRetries = def.getRefreshPolicy() != null ? def.getRefreshPolicy().getMaxRetries() : 0;

        int attempt = 0;
        long startTime = System.currentTimeMillis();
        String lastError = null;

        while (true) {
            try {
                String sql = buildRefreshSql(def);
                log.debug("执行刷新 SQL (attempt={}): {}", attempt, sql);
                boolean success = sqlExecutor.apply(sql);
                if (success) {
                    long duration = System.currentTimeMillis() - startTime;
                    RefreshResult result = new RefreshResult(true, duration, attempt, null, Instant.now());
                    lastResults.put(viewName, result);
                    log.info("物化视图刷新成功: {}，耗时 {} ms，重试 {} 次", viewName, duration, attempt);
                    return result;
                }
                lastError = "SQL 执行返回 false";
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("物化视图刷新异常 (attempt={}): {}，错误: {}", attempt, viewName, lastError, e);
            }

            attempt++;
            if (!autoRetry || attempt > maxRetries) {
                long duration = System.currentTimeMillis() - startTime;
                RefreshResult result = new RefreshResult(false, duration, attempt - 1,
                        lastError, Instant.now());
                lastResults.put(viewName, result);
                log.error("物化视图刷新失败: {}，耗时 {} ms，重试 {} 次，错误: {}",
                        viewName, duration, attempt - 1, lastError);
                return result;
            }
            log.info("物化视图刷新重试: {}，第 {} 次", viewName, attempt);
        }
    }

    /**
     * 构建刷新 SQL。
     *
     * <p>当前实现为全量重算：先 TRUNCATE 目标表，再 INSERT...SELECT 从源表重算。
     * 后续可扩展为增量合并 SQL。</p>
     *
     * @param def 物化视图定义
     * @return 刷新 SQL
     */
    String buildRefreshSql(MaterializedViewDef def) {
        String target = "`" + def.getDatabase() + "`.`" + def.getTargetTable() + "`";
        String selectSql = def.toSelectSql();
        return "TRUNCATE TABLE " + target + "; INSERT INTO " + target + " " + selectSql + ";";
    }

    /**
     * 获取指定视图的最近一次刷新结果。
     *
     * @param viewName 视图名称
     * @return 刷新结果；若从未刷新返回 null
     */
    public RefreshResult getLastResult(String viewName) {
        return lastResults.get(viewName);
    }

    /**
     * 获取当前正在刷新的视图数量。
     *
     * @return 活跃刷新数
     */
    public int getActiveRefreshCount() {
        return activeRefreshCount.get();
    }
}