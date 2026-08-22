package com.levango7.dataenginebdp.function;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InvocationMetrics} 单元测试。
 *
 * <p>使用 {@link SimpleMeterRegistry}（Micrometer 内存实现）验证：
 * <ul>
 *   <li>record 方法创建带正确 tag 的 Counter 和 Timer</li>
 *   <li>多次 record 同一 key 复用缓存（计数累加）</li>
 *   <li>不同 tenant/function/status 生成独立指标</li>
 *   <li>warmup 方法以 warmup 状态记录一次</li>
 * </ul>
 */
@DisplayName("InvocationMetrics invocation 计量组件")
class InvocationMetricsTest {

    private static final String METER_COUNT = "serverless_invocation_count";
    private static final String METER_DURATION = "serverless_invocation_duration_seconds";

    private SimpleMeterRegistry meterRegistry;
    private InvocationMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new InvocationMetrics(meterRegistry);
    }

    @Nested
    @DisplayName("record 方法")
    class RecordMethod {

        @Test
        @DisplayName("应创建带 tenant/runtime/function/status 标签的 Counter")
        void recordCreatesCounterWithTags() {
            metrics.record("tenant-1", "fn-1", "success", 1_000_000L);

            Counter counter = meterRegistry.find(METER_COUNT)
                    .tag("tenant", "tenant-1")
                    .tag("runtime", "java")
                    .tag("function", "fn-1")
                    .tag("status", "success")
                    .counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("应创建带 tenant/runtime/function 标签的 Timer")
        void recordCreatesTimerWithTags() {
            metrics.record("tenant-1", "fn-1", "success", 500_000_000L);

            Timer timer = meterRegistry.find(METER_DURATION)
                    .tag("tenant", "tenant-1")
                    .tag("runtime", "java")
                    .tag("function", "fn-1")
                    .timer();

            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1L);
            // 500_000_000 纳秒 = 0.5 秒
            assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(0.5);
        }

        @Test
        @DisplayName("同一 key 多次 record 应累加 Counter 计数")
        void recordMultipleTimesAccumulatesCounter() {
            metrics.record("tenant-1", "fn-1", "success", 100L);
            metrics.record("tenant-1", "fn-1", "success", 200L);
            metrics.record("tenant-1", "fn-1", "success", 300L);

            Counter counter = meterRegistry.find(METER_COUNT)
                    .tag("tenant", "tenant-1")
                    .tag("runtime", "java")
                    .tag("function", "fn-1")
                    .tag("status", "success")
                    .counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(3.0);
        }

        @Test
        @DisplayName("同一 key 多次 record 应累加 Timer 次数")
        void recordMultipleTimesAccumulatesTimer() {
            metrics.record("tenant-1", "fn-1", "success", 100_000_000L);
            metrics.record("tenant-1", "fn-1", "success", 200_000_000L);

            Timer timer = meterRegistry.find(METER_DURATION)
                    .tag("tenant", "tenant-1")
                    .tag("runtime", "java")
                    .tag("function", "fn-1")
                    .timer();

            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(2L);
            assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(0.3);
        }

        @Test
        @DisplayName("不同 tenant 应生成独立 Counter")
        void recordDifferentTenantsCreatesSeparateCounters() {
            metrics.record("tenant-a", "fn-1", "success", 100L);
            metrics.record("tenant-b", "fn-1", "success", 100L);

            Counter counterA = meterRegistry.find(METER_COUNT)
                    .tag("tenant", "tenant-a").counter();
            Counter counterB = meterRegistry.find(METER_COUNT)
                    .tag("tenant", "tenant-b").counter();

            assertThat(counterA).isNotNull();
            assertThat(counterB).isNotNull();
            assertThat(counterA.count()).isEqualTo(1.0);
            assertThat(counterB.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("不同 status 应生成独立 Counter")
        void recordDifferentStatusesCreatesSeparateCounters() {
            metrics.record("tenant-1", "fn-1", "success", 100L);
            metrics.record("tenant-1", "fn-1", "error", 100L);

            Counter successCounter = meterRegistry.find(METER_COUNT)
                    .tag("tenant", "tenant-1")
                    .tag("function", "fn-1")
                    .tag("status", "success").counter();
            Counter errorCounter = meterRegistry.find(METER_COUNT)
                    .tag("tenant", "tenant-1")
                    .tag("function", "fn-1")
                    .tag("status", "error").counter();

            assertThat(successCounter).isNotNull();
            assertThat(errorCounter).isNotNull();
            assertThat(successCounter.count()).isEqualTo(1.0);
            assertThat(errorCounter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("零纳秒耗时应正常记录")
        void recordZeroDurationIsAllowed() {
            metrics.record("tenant-1", "fn-1", "success", 0L);

            Timer timer = meterRegistry.find(METER_DURATION)
                    .tag("tenant", "tenant-1")
                    .tag("function", "fn-1").timer();

            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1L);
            assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("warmup 方法")
    class WarmupMethod {

        @Test
        @DisplayName("应以 warmup 状态记录一次 Counter")
        void warmupRecordsWarmupStatus() {
            metrics.warmup("default-tenant", "default-fn");

            Counter counter = meterRegistry.find(METER_COUNT)
                    .tag("tenant", "default-tenant")
                    .tag("runtime", "java")
                    .tag("function", "default-fn")
                    .tag("status", "warmup").counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("应创建带默认标签的 Timer")
        void warmupCreatesTimer() {
            metrics.warmup("default-tenant", "default-fn");

            Timer timer = meterRegistry.find(METER_DURATION)
                    .tag("tenant", "default-tenant")
                    .tag("runtime", "java")
                    .tag("function", "default-fn").timer();

            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("warmup 后再 record success 应产生两个独立 Counter")
        void warmupThenRecordCreatesDistinctCounters() {
            metrics.warmup("default-tenant", "default-fn");
            metrics.record("default-tenant", "default-fn", "success", 100L);

            Counter warmupCounter = meterRegistry.find(METER_COUNT)
                    .tag("status", "warmup").counter();
            Counter successCounter = meterRegistry.find(METER_COUNT)
                    .tag("status", "success").counter();

            assertThat(warmupCounter).isNotNull();
            assertThat(successCounter).isNotNull();
            assertThat(warmupCounter.count()).isEqualTo(1.0);
            assertThat(successCounter.count()).isEqualTo(1.0);
        }
    }
}