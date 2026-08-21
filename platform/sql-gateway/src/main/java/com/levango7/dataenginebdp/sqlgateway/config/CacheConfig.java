package com.levango7.dataenginebdp.sqlgateway.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * SQL 查询结果与查询计划缓存配置（v2.1 性能调优）。
 *
 * <p>多级 Caffeine 本地缓存：</p>
 * <ul>
 *   <li>{@link #SQL_QUERY_CACHE}：查询结果缓存，5000 条 / 120s 写后过期 / 60s 访问刷新 / LRU 淘汰，
 *       目标命中率 80%+；</li>
 *   <li>{@link #SQL_PLAN_CACHE}：查询计划缓存（逻辑计划/物理计划），10000 条 / 600s 写后过期 /
 *       300s 访问刷新，避免重复 Calcite 优化开销；</li>
 *   <li>{@link #CATALOG_META_CACHE}：Catalog 元数据缓存，2000 条 / 1800s 过期，减少元数据 RPC。</li>
 * </ul>
 *
 * <p>缓存键含 engine+sql+tenantId（租户隔离，防跨租户泄漏）。
 * 所有缓存开启 {@code recordStats()} 并通过 {@link CaffeineCacheMetrics} 绑定 Micrometer，
 * 暴露 hit rate / load latency / eviction 等指标到 Prometheus。</p>
 *
 * @author shuqing-bigdata
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** 查询结果缓存名（仅 SUCCESS 只读 SQL 结果）。 */
    public static final String SQL_QUERY_CACHE = "sqlQuery";
    /** 查询计划缓存名（Calcite 逻辑/物理计划）。 */
    public static final String SQL_PLAN_CACHE = "sqlPlan";
    /** Catalog 元数据缓存名。 */
    public static final String CATALOG_META_CACHE = "catalogMeta";

    /** 只读 SQL 前缀白名单（写操作永不缓存，防脏读）。 */
    private static final String[] READ_ONLY_PREFIXES = {
            "SELECT", "SHOW", "DESC", "DESCRIBE", "WITH", "EXPLAIN"
    };

    /** 延迟注入的 MeterRegistry（避免 Configuration 强依赖 Actuator）。 */
    private static volatile MeterRegistry meterRegistryRef;

    /**
     * 查询结果缓存：5000 条 / 120s 写后过期 / 60s 访问刷新。
     *
     * <p>容量从 1000 提升到 5000，TTL 从 60s 提升到 120s，并启用访问刷新策略，
     * 热点查询在 TTL 内被访问可续期，目标命中率 80%+。
     * 通过 {@link Scheduler#systemScheduler()} 让过期淘汰及时触发。</p>
     */
    @Bean
    public com.github.benmanes.caffeine.cache.Cache<Object, Object> sqlQueryCache(
            @Value("${sql-gateway.cache.query.max-size:5000}") int maxSize,
            @Value("${sql-gateway.cache.query.expire-after-write-seconds:120}") long expireWrite,
            @Value("${sql-gateway.cache.query.expire-after-access-seconds:60}") long expireAccess) {
        return buildMonitoredCache(SQL_QUERY_CACHE, maxSize, expireWrite, expireAccess);
    }

    /**
     * 查询计划缓存：10000 条 / 600s 写后过期 / 300s 访问刷新。
     *
     * <p>缓存 Calcite 优化后的 RelNode 物理计划，避免相同 SQL 模板重复优化开销。
     * 计划缓存容量更大、TTL 更长（计划相对稳定，表结构变更时通过版本号失效）。</p>
     */
    @Bean
    public com.github.benmanes.caffeine.cache.Cache<Object, Object> sqlPlanCache(
            @Value("${sql-gateway.cache.plan.max-size:10000}") int maxSize,
            @Value("${sql-gateway.cache.plan.expire-after-write-seconds:600}") long expireWrite,
            @Value("${sql-gateway.cache.plan.expire-after-access-seconds:300}") long expireAccess) {
        return buildMonitoredCache(SQL_PLAN_CACHE, maxSize, expireWrite, expireAccess);
    }

    /**
     * Catalog 元数据缓存：2000 条 / 1800s 过期。
     */
    @Bean
    public com.github.benmanes.caffeine.cache.Cache<Object, Object> catalogMetaCache(
            @Value("${sql-gateway.cache.catalog.max-size:2000}") int maxSize,
            @Value("${sql-gateway.cache.catalog.expire-after-write-seconds:1800}") long expireWrite,
            @Value("${sql-gateway.cache.catalog.expire-after-access-seconds:900}") long expireAccess) {
        return buildMonitoredCache(CATALOG_META_CACHE, maxSize, expireWrite, expireAccess);
    }

    /**
     * Spring CacheManager：注册多级缓存，统一通过 {@code @Cacheable} 注解使用。
     *
     * <p>使用 {@link CaffeineCacheManager#registerCustomCache(String, com.github.benmanes.caffeine.cache.Cache)}
     * 注册每个缓存名对应的原生 Caffeine 实例（保留各自的 stats/scheduler/监控绑定）。</p>
     */
    @Bean
    public CacheManager cacheManager(
            com.github.benmanes.caffeine.cache.Cache<Object, Object> sqlQueryCache,
            com.github.benmanes.caffeine.cache.Cache<Object, Object> sqlPlanCache,
            com.github.benmanes.caffeine.cache.Cache<Object, Object> catalogMetaCache) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);
        manager.registerCustomCache(SQL_QUERY_CACHE, sqlQueryCache);
        manager.registerCustomCache(SQL_PLAN_CACHE, sqlPlanCache);
        manager.registerCustomCache(CATALOG_META_CACHE, catalogMetaCache);
        return manager;
    }

    /**
     * 构建带 Micrometer 监控的 Caffeine 缓存。
     */
    private com.github.benmanes.caffeine.cache.Cache<Object, Object> buildMonitoredCache(
            String name, int maxSize, long expireWrite, long expireAccess) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        builder.maximumSize(maxSize);
        builder.expireAfterWrite(expireWrite, TimeUnit.SECONDS);
        builder.expireAfterAccess(expireAccess, TimeUnit.SECONDS);
        builder.scheduler(Scheduler.systemScheduler());
        builder.recordStats();
        com.github.benmanes.caffeine.cache.Cache<Object, Object> cache = builder.build();
        // Micrometer 绑定（可选；MeterRegistry 未注入时跳过监控）
        MeterRegistry registry = meterRegistryRef;
        if (registry != null) {
            CaffeineCacheMetrics.monitor(registry, cache, name);
        }
        return cache;
    }

    /**
     * 注入 MeterRegistry（延迟绑定，避免循环依赖）。
     */
    @Autowired
    public void setMeterRegistry(org.springframework.beans.factory.ObjectProvider<MeterRegistry> provider) {
        MeterRegistry reg = provider.getIfAvailable();
        if (reg != null) {
            meterRegistryRef = reg;
        }
    }

    /**
     * 判断 SQL 是否只读（可缓存）。
     *
     * <p>跳过前导空白/注释后检查前缀；非只读 SQL 永不缓存（防脏读）。</p>
     */
    public static boolean isReadOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        String trimmed = sql.trim();
        // 跳过行注释
        while (trimmed.startsWith("--")) {
            int nl = trimmed.indexOf('\n');
            if (nl < 0) {
                return false;
            }
            trimmed = trimmed.substring(nl + 1).trim();
        }
        // 跳过块注释
        while (trimmed.startsWith("/*")) {
            int end = trimmed.indexOf("*/");
            if (end < 0) {
                return false;
            }
            trimmed = trimmed.substring(end + 2).trim();
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        for (String prefix : READ_ONLY_PREFIXES) {
            if (upper.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
