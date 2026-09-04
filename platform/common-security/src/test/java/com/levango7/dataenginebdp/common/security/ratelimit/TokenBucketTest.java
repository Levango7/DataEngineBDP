package com.levango7.dataenginebdp.common.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenBucket 单元测试：虚拟时钟注入，精确控制时间推进。
 */
class TokenBucketTest {

    /** 可手动推进的虚拟时钟。 */
    private static final class FakeClock implements TokenBucket.NanoClock {
        final AtomicLong nanos = new AtomicLong(0);

        @Override
        public long nowNanos() {
            return nanos.get();
        }

        void advanceSeconds(double seconds) {
            nanos.addAndGet((long) (seconds * 1_000_000_000L));
        }
    }

    @Test
    @DisplayName("满桶冷启动：容量内请求全部放行")
    void coldStartAllowsUpToCapacity() {
        FakeClock clock = new FakeClock();
        // 容量 5，每秒补 1
        TokenBucket bucket = new TokenBucket(5, 1.0, clock);
        for (int i = 0; i < 5; i++) {
            assertThat(bucket.tryConsume()).as("第 %d 次应放行", i + 1).isTrue();
        }
        assertThat(bucket.tryConsume()).as("超出容量应拒绝").isFalse();
    }

    @Test
    @DisplayName("超限后按稳态速率恢复：每秒补 1 个令牌")
    void refillsAtSteadyRate() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(2, 1.0, clock);

        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).as("桶空应拒绝").isFalse();

        clock.advanceSeconds(1.0);
        assertThat(bucket.tryConsume()).as("补 1 个令牌后应放行").isTrue();
        assertThat(bucket.tryConsume()).as("又空了应拒绝").isFalse();

        clock.advanceSeconds(0.5);
        assertThat(bucket.tryConsume()).as("半秒只补 0.5 个，不足 1 应拒绝").isFalse();
        clock.advanceSeconds(0.5);
        assertThat(bucket.tryConsume()).as("凑满 1 个后应放行").isTrue();
    }

    @Test
    @DisplayName("长时间静默后补充不超过容量上限")
    void refillCappedAtCapacity() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(3, 10.0, clock);

        clock.advanceSeconds(100); // 静默很久，补充远超容量
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).as("容量 3 封顶，第 4 次拒绝").isFalse();
    }

    @Test
    @DisplayName("持续以不超过稳态速率消费永不拒绝")
    void steadyConsumptionNeverRejected() {
        FakeClock clock = new FakeClock();
        // 每秒补 10 个令牌，每 0.1 秒消费 1 个 = 10/s，恰好等于补充速率
        TokenBucket bucket = new TokenBucket(10, 10.0, clock);
        for (int i = 0; i < 1000; i++) {
            clock.advanceSeconds(0.1);
            assertThat(bucket.tryConsume())
                    .as("第 %d 轮：速率匹配稳态不应拒绝", i).isTrue();
        }
    }
}
