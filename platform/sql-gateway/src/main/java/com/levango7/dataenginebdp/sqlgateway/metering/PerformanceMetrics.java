package com.levango7.dataenginebdp.sqlgateway.metering;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQL 网关性能指标收集器（v2.1 性能调优）。
 *
 * <p>通过 Micrometer 暴露以下 Prometheus 指标，用于监控查询延迟、缓存命中率、吞吐量：</p>
 * <ul>
 *   <li>{@code sql_gateway_query_duration_seconds{engine,type=cross|single}}：
 *       查询延迟分布（P50/P95/P99），区分跨源/单源；</li>
 *   <li>{@code sql_gateway_query_total{engine,type,result}}：查询计数（成功/失败/降级）；</li>
 *   <li>{@code sql_gateway_cache_hit_total{cache}} / {@code sql_gateway_cache_miss_total{cache}}：
 *       缓存命中/未命中计数（用于计算命中率，目标 80%+）；</li>
 *   <li>{@code sql_gateway_throughput_bytes_total{engine}}：吞吐字节总量；</li>
 *   <li>{@code sql_gateway_concurrent_queries}：当前并发查询数（Gauge）。</li>
 * </ul>
 *
 * <p>所有指标通过 Spring Boot Actuator 自动暴露到 {@code /actuator/prometheus} 端点，
 * 供 Prometheus + Grafana 监控面板使用。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class PerformanceMetrics {

    private final MeterRegistry registry;
    private final AtomicLong concurrentQueries = new AtomicLong(0);

    @Autowired
    public PerformanceMetrics(org.springframework.beans.factory.ObjectProvider<MeterRegistry> provider) {
        this.registry = provider.getIfAvailable();
        if (registry != null) {
            registry.gauge("sql_gateway_concurrent_queries", concurrentQueries);
        }
    }

    /**
     * 开始一次查询，返回开始时间戳（用于后续 {@link #recordQueryEnd}）。
     */
    public long recordQueryStart() {
        concurrentQueries.incrementAndGet();
        return System.nanoTime();
    }

    /**
     * 结束一次查询，记录延迟、计数、吞吐量。
     *
     * @param startNanos  {@link #recordQueryStart()} 返回的时间戳
     * @param engine      目标引擎（trino/doris）
     * @param crossSource 是否跨源查询
     * @param success     是否成功（false 表示降级或失败）
     * @param bytes       扫描字节（可空）
     */
    public void recordQueryEnd(long startNanos, String engine, boolean crossSource,
                               boolean success, Long bytes) {
        if (registry == null) {
            concurrentQueries.decrementAndGet();
            return;
        }
        try {
            long durationNanos = System.nanoTime() - startNanos;
            String type = crossSource ? "cross" : "single";
            String result = success ? "success" : "degraded";

            Timer.builder("sql_gateway_query_duration_seconds")
                    .tags(Tags.of("engine", engine == null ? "unknown" : engine,
                            "type", type))
                    .description("SQL 查询延迟分布")
                    .register(registry)
                    .record(Duration.ofNanos(durationNanos));

            Counter.builder("sql_gateway_query_total")
                    .tags(Tags.of("engine", engine == null ? "unknown" : engine,
                            "type", type, "result", result))
                    .description("SQL 查询总次数")
                    .register(registry)
                    .increment();

            if (bytes != null && bytes > 0) {
                Counter.builder("sql_gateway_throughput_bytes_total")
                        .tags(Tags.of("engine", engine == null ? "unknown" : engine))
                        .description("SQL 查询吞吐字节总量")
                        .register(registry)
                        .increment(bytes);
            }
        } finally {
            concurrentQueries.decrementAndGet();
        }
    }

    /**
     * 记数缓存命中。
     */
    public void recordCacheHit(String cacheName) {
        if (registry == null) {
            return;
        }
        Counter.builder("sql_gateway_cache_hit_total")
                .tags(Tags.of("cache", cacheName))
                .description("缓存命中次数")
                .register(registry)
                .increment();
    }

    /**
     * 计数缓存未命中。
     */
    public void recordCacheMiss(String cacheName) {
        if (registry == null) {
            return;
        }
        Counter.builder("sql_gateway_cache_miss_total")
                .tags(Tags.of("cache", cacheName))
                .description("缓存未命中次数")
                .register(registry)
                .increment();
    }

    /**
     * 记数查询计划缓存命中（避免重复 Calcite 优化）。
     */
    public void recordPlanCacheHit() {
        recordCacheHit("sqlPlan");
    }

    /**
     * 计数查询计划缓存未命中。
     */
    public void recordPlanCacheMiss() {
        recordCacheMiss("sqlPlan");
    }

    /**
     * 当前并发查询数（监控用）。
     */
    public long currentConcurrent() {
        return concurrentQueries.get();
    }
}