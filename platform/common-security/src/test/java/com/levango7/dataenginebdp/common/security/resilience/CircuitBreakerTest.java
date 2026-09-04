package com.levango7.dataenginebdp.common.security.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CircuitBreaker 三态机测试：虚拟时钟注入精确控制冷却。
 */
class CircuitBreakerTest {

    /** 可手动推进的虚拟时钟。 */
    private static final class FakeClock implements CircuitBreaker.NanoClock {
        final AtomicLong nanos = new AtomicLong(0);

        @Override
        public long nowNanos() {
            return nanos.get();
        }

        void advance(Duration d) {
            nanos.addAndGet(d.toNanos());
        }
    }

    // 默认参数：窗口 10，失败率阈值 50%，最小样本 5，OPEN 冷却 30s
    private CircuitBreaker newBreaker(FakeClock clock) {
        return new CircuitBreaker("test", 10, 0.5, 5, Duration.ofSeconds(30), clock);
    }

    @Test
    @DisplayName("CLOSED：失败率达标且样本够时跳闸为 OPEN")
    void tripsOnFailureRate() {
        CircuitBreaker cb = newBreaker(new FakeClock());
        // 交替成功/失败：失败率恒 50%。
        // 第 6 次调用时样本 6 ≥ 5、失败率 3/6=50% ≥ 阈值 → 第 3 次失败后即跳闸。
        for (int i = 0; i < 3; i++) {
            assertThat(cb.tryAcquire()).isTrue();
            cb.recordSuccess();
            assertThat(cb.tryAcquire()).isTrue();
            cb.recordFailure();
        }
        assertThat(cb.getState())
                .as("样本 6 ≥ 5，失败率 50% ≥ 阈值，应已跳闸")
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.tryAcquire()).as("OPEN 快速失败").isFalse();
    }

    @Test
    @DisplayName("失败率低于阈值不跳闸：5 样本中 2 失败 = 40% < 50%")
    void noTripBelowFailureRate() {
        CircuitBreaker cb = newBreaker(new FakeClock());
        cb.recordSuccess();
        cb.recordSuccess();
        cb.recordSuccess();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        // 补一次成功维持低失败率，样本 6
        cb.recordSuccess();
        assertThat(cb.getState())
                .as("失败率 2/6=33% < 50%，保持 CLOSED")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("样本不足时不跳闸：4 次全失败但 minimumNumberOfCalls=5")
    void noTripBelowMinimumCalls() {
        CircuitBreaker cb = newBreaker(new FakeClock());
        for (int i = 0; i < 4; i++) {
            assertThat(cb.tryAcquire()).isTrue();
            cb.recordFailure();
        }
        // 失败率 100% 但样本 4 < 5：不跳闸
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("OPEN 冷却期满 → HALF_OPEN 试探成功 → 复位 CLOSED")
    void halfOpenSuccessRecovers() {
        FakeClock clock = new FakeClock();
        CircuitBreaker cb = newBreaker(clock);
        // 打满跳闸
        for (int i = 0; i < 5; i++) {
            cb.tryAcquire();
            cb.recordFailure();
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 冷却未满：快速失败
        clock.advance(Duration.ofSeconds(10));
        assertThat(cb.tryAcquire()).isFalse();

        // 冷却期满：HALF_OPEN 试探
        clock.advance(Duration.ofSeconds(25));
        assertThat(cb.tryAcquire()).isTrue();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 试探成功：复位 CLOSED
        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("HALF_OPEN 试探失败 → 重回 OPEN 且冷却重置")
    void halfOpenFailureReopens() {
        FakeClock clock = new FakeClock();
        CircuitBreaker cb = newBreaker(clock);
        for (int i = 0; i < 5; i++) {
            cb.tryAcquire();
            cb.recordFailure();
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 冷却期满进入 HALF_OPEN
        clock.advance(Duration.ofSeconds(31));
        assertThat(cb.tryAcquire()).isTrue();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 试探失败：重回 OPEN
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 冷却已重置：再等 10s 仍快速失败
        clock.advance(Duration.ofSeconds(10));
        assertThat(cb.tryAcquire()).isFalse();

        // 再等 21s（合计 31s）冷却满：可再试探
        clock.advance(Duration.ofSeconds(21));
        assertThat(cb.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("恢复后窗口清零：复位后需重新积累样本才会再跳闸")
    void windowResetAfterRecovery() {
        FakeClock clock = new FakeClock();
        CircuitBreaker cb = newBreaker(clock);
        for (int i = 0; i < 5; i++) {
            cb.tryAcquire();
            cb.recordFailure();
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(Duration.ofSeconds(31));
        assertThat(cb.tryAcquire()).isTrue();
        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 窗口已清零：1 次失败不会立刻再跳闸（样本 1 < 5）
        cb.tryAcquire();
        cb.recordFailure();
        assertThat(cb.getState())
                .as("复位后窗口清零，单次失败不跳闸")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
