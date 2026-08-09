package com.levango7.dataenginebdp.function;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * invocation 计量组件 · 数擎大数据平台 T025.
 *
 * <p>按 tenant 隔离记录函数 invocation 计量：
 * <ul>
 *   <li>Prometheus 指标：serverless_invocation_count / serverless_invocation_duration_seconds</li>
 *   <li>Loki 日志：结构化 JSON 日志，由 Promtail 采集写入 Loki</li>
 * </ul></p>
 *
 * <p>tenant 隔离：所有指标均带 tenant 标签，支持 PromQL 按租户聚合查询。</p>
 */
@Component
public class InvocationMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(InvocationMetrics.class);

    private static final String RUNTIME = "java";

    private final MeterRegistry meterRegistry;

    /** Counter 缓存：key = tenant|function|status. */
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();

    /** Timer 缓存：key = tenant|function. */
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    /**
     * 构造函数（Spring 注入 MeterRegistry）.
     *
     * @param meterRegistry Micrometer 指标注册表
     */
    public InvocationMetrics(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录一次 invocation.
     *
     * @param tenantId     租户 ID
     * @param functionName 函数名
     * @param status       调用状态（success/error）
     * @param durationNanos 调用耗时（纳秒）
     */
    public void record(final String tenantId,
                       final String functionName,
                       final String status,
                       final long durationNanos) {
        // 1. Prometheus 指标
        String counterKey = tenantId + "|" + functionName + "|" + status;
        Counter counter = counterCache.computeIfAbsent(counterKey, k ->
            Counter.builder("serverless_invocation_count")
                .description("Serverless 函数调用总次数")
                .tag("tenant", tenantId)
                .tag("runtime", RUNTIME)
                .tag("function", functionName)
                .tag("status", status)
                .register(meterRegistry)
        );
        counter.increment();

        String timerKey = tenantId + "|" + functionName;
        Timer timer = timerCache.computeIfAbsent(timerKey, k ->
            Timer.builder("serverless_invocation_duration_seconds")
                .description("Serverless 函数调用延迟（秒）")
                .tag("tenant", tenantId)
                .tag("runtime", RUNTIME)
                .tag("function", functionName)
                .register(meterRegistry)
        );
        timer.record(Duration.ofNanos(durationNanos));

        // 2. Loki 日志：结构化 JSON，由 Promtail 采集
        //    LogQL 查询示例：{tenant="xxx"} |= "invocation"
        LOG.info("{{\"type\":\"invocation\",\"tenant\":\"{}\",\"runtime\":\"{}\","
                + "\"function\":\"{}\",\"status\":\"{}\",\"duration_seconds\":{}}}",
                tenantId, RUNTIME, functionName, status,
                String.format("%.6f", durationNanos / 1_000_000_000.0));
    }

    /**
     * 预热：初始化默认指标（降低首次请求开销）.
     *
     * @param defaultTenant 默认租户 ID
     * @param defaultFunction 默认函数名
     */
    public void warmup(final String defaultTenant, final String defaultFunction) {
        record(defaultTenant, defaultFunction, "warmup", 0L);
        LOG.info("Java invocation metrics warmup done: tenant={}, function={}",
                defaultTenant, defaultFunction);
    }
}