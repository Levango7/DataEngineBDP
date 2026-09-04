package com.levango7.dataenginebdp.common.security.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 单桶令牌桶（线程安全，无外部依赖）。
 *
 * <p>经典令牌桶：以 {@code refillRatePerSecond} 的速率向桶内补充令牌，
 * 每个请求消耗 1 个；桶空则拒绝。补充按"惰性结算"实现——不启动任何定时器，
 * 每次 tryConsume 时根据距上次结算的墙钟时长一次性补足。</p>
 *
 * <p>实现说明：tokens（剩余令牌×1000 定点数）与 lastRefillNanos（完整纳秒时间戳）
 * 需要作为一个整体原子更新，因此用 synchronized 而非单变量 CAS 打包——
 * 打包成单个 long 会把纳秒时间戳截断为 32 位，与完整 now 求差会得到错乱的
 * elapsed（历史 bug：初始时间戳 0 与真实纳钟求差导致桶永远满）。
 * 单桶临界区仅几行算术，竞争维度本身已是低热点（每租户/每 IP 一桶），
 * synchronized 足够且正确性优先。</p>
 *
 * <p>时钟源通过 {@link NanoClock} 注入，测试可注入虚拟时钟精确控制时间推进。</p>
 */
public final class TokenBucket {

    private final double refillRatePerSecond;
    private final int capacity;

    private double tokensMilli;   // 剩余令牌 ×1000（定点，避免浮点累积误差）
    private long lastRefillNanos;

    private final NanoClock clock;

    /** 纳秒时钟抽象（测试可注入）。 */
    public interface NanoClock {
        long nowNanos();
    }

    /** 真实系统纳秒时钟。 */
    public static final NanoClock SYSTEM_NANOS = System::nanoTime;

    /**
     * 构造令牌桶（初始即满桶，允许冷启动突发）。
     *
     * @param capacity              桶容量（突发上限）
     * @param refillRatePerSecond   每秒稳态补充速率
     * @param clock                 时钟源
     */
    public TokenBucket(int capacity, double refillRatePerSecond, NanoClock clock) {
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.clock = clock;
        this.tokensMilli = capacity * 1000.0;
        this.lastRefillNanos = clock.nowNanos();
    }

    /**
     * 尝试消耗一个令牌。
     *
     * @return true=放行；false=超限
     */
    public synchronized boolean tryConsume() {
        long now = clock.nowNanos();
        double elapsedSeconds = Math.max(0, now - lastRefillNanos) / 1_000_000_000.0;
        lastRefillNanos = now;

        // 惰性补充，容量封顶
        tokensMilli = Math.min(capacity * 1000.0,
                tokensMilli + elapsedSeconds * refillRatePerSecond * 1000.0);

        if (tokensMilli < 1000.0) {
            return false;
        }
        tokensMilli -= 1000.0;
        return true;
    }

    /** 桶容量（突发上限）。 */
    public int capacity() {
        return capacity;
    }

    /** 当前近似剩余令牌数（诊断用）。 */
    public synchronized double availableTokens() {
        double elapsed = Math.max(0, clock.nowNanos() - lastRefillNanos) / 1_000_000_000.0;
        return Math.min(capacity, (tokensMilli + elapsed * refillRatePerSecond * 1000.0) / 1000.0);
    }
}
