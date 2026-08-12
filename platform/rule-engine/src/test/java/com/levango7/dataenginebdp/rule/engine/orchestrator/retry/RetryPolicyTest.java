package com.levango7.dataenginebdp.rule.engine.orchestrator.retry;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 重试策略与执行器单元测试。
 */
class RetryPolicyTest {

    @Test
    void fixedBackoff_shouldReturnConstantInterval() {
        FixedBackoff policy = new FixedBackoff(500);
        assertEquals(500, policy.nextBackoffMs(1));
        assertEquals(500, policy.nextBackoffMs(5));
        assertEquals("FIXED", policy.name());
    }

    @Test
    void exponentialBackoff_shouldGrowExponentially() {
        ExponentialBackoff policy = new ExponentialBackoff(100, 2.0, 10_000);
        assertEquals(100, policy.nextBackoffMs(1));
        assertEquals(200, policy.nextBackoffMs(2));
        assertEquals(400, policy.nextBackoffMs(3));
        assertEquals(800, policy.nextBackoffMs(4));
        assertEquals("EXPONENTIAL", policy.name());
    }

    @Test
    void exponentialBackoff_shouldCapAtMax() {
        ExponentialBackoff policy = new ExponentialBackoff(100, 2.0, 500);
        assertEquals(500, policy.nextBackoffMs(10));
    }

    @Test
    void retryExecutor_shouldSucceedOnFirstAttempt() throws Exception {
        RetryExecutor executor = new RetryExecutor();
        String result = executor.execute(() -> "ok", new FixedBackoff(10), 3);
        assertEquals("ok", result);
    }

    @Test
    void retryExecutor_shouldRetryAndSucceed() throws Exception {
        RetryExecutor executor = new RetryExecutor(millis -> {
        }); // no-op sleeper
        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> task = () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("transient");
            }
            return "ok";
        };
        String result = executor.execute(task, new FixedBackoff(1), 5);
        assertEquals("ok", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void retryExecutor_shouldFailAfterMaxAttempts() {
        RetryExecutor executor = new RetryExecutor(millis -> {
        });
        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> task = () -> {
            attempts.incrementAndGet();
            throw new RuntimeException("always fail");
        };
        assertThrows(RuntimeException.class, () -> executor.execute(task, new FixedBackoff(1), 3));
        assertEquals(3, attempts.get());
    }

    @Test
    void retryExecutor_shouldNotRetryOnNonRetryable() throws Exception {
        RetryExecutor executor = new RetryExecutor(millis -> {
        });
        AtomicInteger attempts = new AtomicInteger(0);
        Callable<String> task = () -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("not retryable");
        };
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(task, new FixedBackoff(1), 3,
                        e -> !(e instanceof IllegalArgumentException)));
        assertEquals(1, attempts.get());
    }
}