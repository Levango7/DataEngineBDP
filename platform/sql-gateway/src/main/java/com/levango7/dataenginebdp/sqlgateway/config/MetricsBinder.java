package com.levango7.dataenginebdp.sqlgateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 缓存指标绑定器（v2.1 性能调优）。
 *
 * <p>Spring 容器启动完成后，将各 Caffeine 缓存实例绑定到 {@link MeterRegistry}，
 * 暴露以下 Prometheus 指标（按缓存名 tag 区分）：</p>
 * <ul>
 *   <li>{@code cache_gets_total{cache,name,result=hit|miss}}：命中/未命中次数；</li>
 *   <li>{@code cache_load_duration_seconds{cache,name}}：加载耗时分布；</li>
 *   <li>{@code cache_evictions_total{cache,name}}：淘汰次数；</li>
 *   <li>{@code cache_size{cache,name}}：当前缓存条数。</li>
 * </ul>
 *
 * <p>命中率可通过 {@code hit / (hit + miss)} 计算，目标 80%+。</p>
 *
 * <p>实现说明：通过 {@link CacheManager} 按缓存名逐个解析并取原生 Caffeine 实例绑定。
 * 不可直接按类型注入单个 {@code Cache<Object,Object>}——容器中存在
 * sqlQuery/sqlPlan/catalogMeta 三个同泛型 Bean，{@code ObjectProvider.getIfAvailable()}
 * 会因无法唯一判定抛 {@code NoUniqueBeanDefinitionException}，导致应用启动失败。</p>
 *
 * @author shuqing-bigdata
 */
@Configuration
public class MetricsBinder {

    private final org.springframework.beans.factory.ObjectProvider<MeterRegistry> registryProvider;

    private final org.springframework.beans.factory.ObjectProvider<CacheManager> cacheManagerProvider;

    public MetricsBinder(
            org.springframework.beans.factory.ObjectProvider<MeterRegistry> registryProvider,
            org.springframework.beans.factory.ObjectProvider<CacheManager> cacheManagerProvider) {
        this.registryProvider = registryProvider;
        this.cacheManagerProvider = cacheManagerProvider;
    }

    /**
     * 将各命名缓存的原生 Caffeine 实例绑定到 Micrometer。
     */
    @PostConstruct
    public void bind() {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        CacheManager manager = cacheManagerProvider.getIfAvailable();
        if (manager == null) {
            return;
        }
        bindOne(registry, manager.getCache(CacheConfig.SQL_QUERY_CACHE), CacheConfig.SQL_QUERY_CACHE);
        bindOne(registry, manager.getCache(CacheConfig.SQL_PLAN_CACHE), CacheConfig.SQL_PLAN_CACHE);
        bindOne(registry, manager.getCache(CacheConfig.CATALOG_META_CACHE), CacheConfig.CATALOG_META_CACHE);
    }

    /**
     * 绑定单个 Spring Cache（仅处理 Caffeine 实现）。
     *
     * @param registry 指标注册表
     * @param cache    Spring 缓存抽象实例（可空）
     * @param name     缓存名（作为 metric tag）
     */
    private void bindOne(MeterRegistry registry, Cache cache, String name) {
        if (cache == null) {
            return;
        }
        if (cache.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> nativeCaffeine) {
            @SuppressWarnings("unchecked")
            com.github.benmanes.caffeine.cache.Cache<Object, Object> caffeine =
                    (com.github.benmanes.caffeine.cache.Cache<Object, Object>) nativeCaffeine;
            CaffeineCacheMetrics.monitor(registry, caffeine, name);
        }
    }
}
