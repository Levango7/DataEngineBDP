package com.levango7.dataenginebdp.sqlgateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.annotation.Autowired;
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
 * @author shuqing-bigdata
 */
@Configuration
public class MetricsBinder {

    private final org.springframework.beans.factory.ObjectProvider<MeterRegistry> registryProvider;

    private final org.springframework.beans.factory.ObjectProvider<
            com.github.benmanes.caffeine.cache.Cache<Object, Object>> sqlQueryCacheProvider;

    private final org.springframework.beans.factory.ObjectProvider<
            com.github.benmanes.caffeine.cache.Cache<Object, Object>> sqlPlanCacheProvider;

    private final org.springframework.beans.factory.ObjectProvider<
            com.github.benmanes.caffeine.cache.Cache<Object, Object>> catalogMetaCacheProvider;

    public MetricsBinder(
            org.springframework.beans.factory.ObjectProvider<MeterRegistry> registryProvider,
            org.springframework.beans.factory.ObjectProvider<
                    com.github.benmanes.caffeine.cache.Cache<Object, Object>> sqlQueryCacheProvider,
            org.springframework.beans.factory.ObjectProvider<
                    com.github.benmanes.caffeine.cache.Cache<Object, Object>> sqlPlanCacheProvider,
            org.springframework.beans.factory.ObjectProvider<
                    com.github.benmanes.caffeine.cache.Cache<Object, Object>> catalogMetaCacheProvider) {
        this.registryProvider = registryProvider;
        this.sqlQueryCacheProvider = sqlQueryCacheProvider;
        this.sqlPlanCacheProvider = sqlPlanCacheProvider;
        this.catalogMetaCacheProvider = catalogMetaCacheProvider;
    }

    @PostConstruct
    public void bind() {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        bindOne(registry, sqlQueryCacheProvider.getIfAvailable(), CacheConfig.SQL_QUERY_CACHE);
        bindOne(registry, sqlPlanCacheProvider.getIfAvailable(), CacheConfig.SQL_PLAN_CACHE);
        bindOne(registry, catalogMetaCacheProvider.getIfAvailable(), CacheConfig.CATALOG_META_CACHE);
    }

    private void bindOne(MeterRegistry registry,
                         com.github.benmanes.caffeine.cache.Cache<Object, Object> cache,
                         String name) {
        if (cache == null) {
            return;
        }
        CaffeineCacheMetrics.monitor(registry, cache, name);
    }
}
