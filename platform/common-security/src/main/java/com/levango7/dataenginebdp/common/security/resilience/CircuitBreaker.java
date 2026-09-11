package com.levango7.dataenginebdp.common.security.resilience;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简单熔断器（CLOSED → OPEN → HALF_OPEN 三态机）。
 *
 * <p>零外部依赖（与 ratelimit 令牌桶同风格的轻量实现），核心语义：</p>
 * <ul>
 *   <li><b>CLOSED</b>：正常放行，滑动窗口内失败率 ≥ {@code failureRateThreshold}%
 *       且样本数 ≥ {@code minimumNumberOfCalls} 时跳闸为 OPEN</li>
 *   <li><b>OPEN</b>：快速失败（不发起调用、不等超时），冷却 {@code openDuration}
 *       后进入 HALF_OPEN</li>
 *   <li><b>HALF_OPEN</b>：放行试探调用，成功即闭合复位，失败则重回 OPEN</li>
 * </ul>
 *
 * <p>线程安全：状态 CAS；滑动窗口使用 {@link ConcurrentLinkedQueue} 保留最近
 * {@code windowSize} 次调用结果（true=成功，false=失败），每次 record 时入队并
 * 在超限时出队——保证窗口内样本精确反映最近 N 次调用，避免旧样本污染失败率。</p>
 */
public final class CircuitBreaker {

    /** 熔断状态。 */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int windowSize;
    private final double failureRateThreshold;
    private final int minimumNumberOfCalls;
    private final Duration openDuration;
    private final NanoClock clock;

    // 滑动窗口：保留最近 windowSize 次调用结果（true=成功，false=失败）
    private final ConcurrentLinkedQueue<Boolean> slidingWindow = new ConcurrentLinkedQueue<>();

    // 状态机
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicLong openedAtNanos = new AtomicLong(0);

    /** 纳秒时钟抽象（测试可注入）。 */
    public interface NanoClock {
        long nowNanos();
    }

    /** 真实系统纳秒时钟。 */
    public static final NanoClock SYSTEM_NANOS = System::nanoTime;

    /**
     * 构造熔断器。
     *
     * @param name                  熔断器名（日志标识，如 "seatunnel"）
     * @param windowSize            滑动窗口大小（最近 N 次调用）
     * @param failureRateThreshold  跳闸失败率阈值 [0.0, 1.0]
     * @param minimumNumberOfCalls  跳闸最小样本数（防止少量调用误跳闸）
     * @param openDuration          OPEN 冷却时长（后进入 HALF_OPEN 试探）
     * @param clock                 时钟源
     */
    public CircuitBreaker(String name, int windowSize, double failureRateThreshold,
                          int minimumNumberOfCalls, Duration openDuration, NanoClock clock) {
        this.name = name;
        this.windowSize = windowSize;
        this.failureRateThreshold = failureRateThreshold;
        this.minimumNumberOfCalls = minimumNumberOfCalls;
        this.openDuration = openDuration;
        this.clock = clock;
    }

    /**
     * 是否允许发起调用。
     *
     * <p>OPEN 冷却期满后自动进入 HALF_OPEN 放行一次试探。</p>
     *
     * @return true=放行；false=熔断中快速失败
     */
    public boolean tryAcquire() {
        State current = state.get();
        if (current == State.OPEN) {
            long opened = openedAtNanos.get();
            if (clock.nowNanos() - opened < openDuration.toNanos()) {
                return false;  // 冷却未满：快速失败
            }
            // 冷却期满：OPEN → HALF_OPEN（CAS 防多线程同时试探）
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                return true;   // 本次即试探调用
            }
            return state.get() == State.HALF_OPEN ? false : tryAcquire();
        }
        return true;  // CLOSED / HALF_OPEN 放行
    }

    /**
     * 记录一次成功调用。
     */
    public void recordSuccess() {
        recordResult(true);

        // HALF_OPEN 试探成功：复位 CLOSED 并清零窗口
        if (state.get() == State.HALF_OPEN) {
            state.compareAndSet(State.HALF_OPEN, State.CLOSED);
            resetWindow();
        }
    }

    /**
     * 记录一次失败调用。
     */
    public void recordFailure() {
        recordResult(false);

        State current = state.get();
        if (current == State.HALF_OPEN) {
            // 试探失败：重回 OPEN 并重置冷却
            state.set(State.OPEN);
            openedAtNanos.set(clock.nowNanos());
            resetWindow();
            return;
        }
        if (current == State.CLOSED) {
            evaluateTrip();
        }
    }

    /** 当前状态（诊断用）。 */
    public State getState() {
        return state.get();
    }

    /** 最近窗口失败率（诊断用，0.0~1.0）。 */
    public double getFailureRate() {
        int total = slidingWindow.size();
        if (total == 0) {
            return 0.0;
        }
        int failures = 0;
        for (Boolean result : slidingWindow) {
            if (!result) {
                failures++;
            }
        }
        return (double) failures / total;
    }

    /** 熔断器名。 */
    public String getName() {
        return name;
    }

    /* ------------------------------ 内部 ------------------------------ */

    /**
     * 记录一次调用结果到滑动窗口。
     *
     * <p>入队最新结果，并在窗口超限时从队首淘汰最旧样本，保证窗口大小 ≤ {@code windowSize}。</p>
     *
     * @param success true=成功；false=失败
     */
    private void recordResult(boolean success) {
        slidingWindow.offer(success);
        // 淘汰超出窗口大小的最旧样本
        while (slidingWindow.size() > windowSize) {
            slidingWindow.poll();
        }
    }

    /**
     * 评估是否需要跳闸为 OPEN（CLOSED 状态下）。
     */
    private void evaluateTrip() {
        int total = slidingWindow.size();
        if (total < minimumNumberOfCalls) {
            return;
        }
        int failures = 0;
        for (Boolean result : slidingWindow) {
            if (!result) {
                failures++;
            }
        }
        double failureRate = (double) failures / total;
        if (failureRate >= failureRateThreshold) {
            if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                openedAtNanos.set(clock.nowNanos());
            }
        }
    }

    private void resetWindow() {
        slidingWindow.clear();
    }
}
