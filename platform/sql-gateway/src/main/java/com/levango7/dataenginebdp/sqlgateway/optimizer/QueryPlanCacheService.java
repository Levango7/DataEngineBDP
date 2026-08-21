package com.levango7.dataenginebdp.sqlgateway.optimizer;

import com.levango7.dataenginebdp.sqlgateway.metering.PerformanceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Function;

/**
 * 查询计划缓存服务（v2.1 性能调优）。
 *
 * <p>缓存 Calcite 优化后的查询计划（逻辑计划/物理计划），避免相同 SQL 模板重复执行
 * 解析 → 校验 → 优化开销。计划缓存命中率目标 80%+，可显著降低查询延迟。</p>
 *
 * <p>缓存键：{@code engine + "|" + normalizedSql + "|" + tenantId} 的 SHA-256 指纹。
 * 计划失效场景（表结构变更、统计信息更新）通过版本号机制处理：
 * 调用 {@link #invalidateAll()} 在元数据变更时清空全部计划缓存。</p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * RelNode plan = planCacheService.getOrCompute(cacheKey, () -> calciteOptimizer.optimize(sql));
 * }</pre>
 *
 * @author shuqing-bigdata
 */
@Component
public class QueryPlanCacheService {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanCacheService.class);

    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> planCache;
    private final PerformanceMetrics performanceMetrics;

    @Autowired
    public QueryPlanCacheService(
            @Qualifier("sqlPlanCache")
            com.github.benmanes.caffeine.cache.Cache<Object, Object> planCache,
            PerformanceMetrics performanceMetrics) {
        this.planCache = planCache;
        this.performanceMetrics = performanceMetrics;
    }

    /**
     * 获取计划：缓存命中则直接返回；未命中则调用 {@code loader} 计算并写入缓存。
     *
     * @param engine    目标引擎
     * @param sql       SQL 文本
     * @param tenantId  租户 ID（隔离键）
     * @param loader    计划加载函数（缓存未命中时调用，开销大）
     * @param <T>       计划类型
     * @return 查询计划
     */
    public <T> T getOrCompute(String engine, String sql, String tenantId,
                              Function<String, T> loader) {
        String cacheKey = buildPlanCacheKey(engine, sql, tenantId);
        Object cached = planCache.getIfPresent(cacheKey);
        if (cached != null) {
            performanceMetrics.recordPlanCacheHit();
            log.debug("查询计划缓存命中 key={}", cacheKey);
            @SuppressWarnings("unchecked")
            T typed = (T) cached;
            return typed;
        }
        performanceMetrics.recordPlanCacheMiss();
        T plan = loader.apply(sql);
        if (plan != null) {
            planCache.put(cacheKey, plan);
            log.debug("查询计划已缓存 key={}", cacheKey);
        }
        return plan;
    }

    /**
     * 失效指定 SQL 的计划缓存（表结构变更时调用）。
     */
    public void invalidate(String engine, String sql, String tenantId) {
        planCache.invalidate(buildPlanCacheKey(engine, sql, tenantId));
    }

    /**
     * 清空全部计划缓存（元数据批量变更时调用）。
     */
    public void invalidateAll() {
        planCache.invalidateAll();
        log.info("查询计划缓存已全部清空");
    }

    /**
     * 当前计划缓存条数（监控用）。
     */
    public long size() {
        return planCache.estimatedSize();
    }

    /**
     * 构建计划缓存键：SHA-256(engine|normalizedSql|tenantId)。
     */
    private String buildPlanCacheKey(String engine, String sql, String tenantId) {
        String raw = (engine == null ? "" : engine) + "|"
                + (sql == null ? "" : sql.trim()) + "|"
                + (tenantId == null ? "" : tenantId);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
