package com.shuqing.bigdata.rule.engine.orchestrator.retry;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 指数退避策略。
 *
 * <p>第 n 次重试等待 {@code baseMs * multiplier^(n-1)}，并设置上限 capMs
 * 防止指数爆炸。适用于网络抖动、下游服务恢复等随时间改善的故障。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>使用 long 运算并在每步与 capMs 取 min，避免溢出；</li>
 *   <li>multiplier 默认 2.0，可配置为 1.5 等更平滑增长。</li>
 * </ul>
 * </p>
 */
@Data
@AllArgsConstructor
public class ExponentialBackoff implements RetryPolicy {

    /** 基准等待时长（毫秒） */
    private final long baseMs;

    /** 乘数因子，>1 才有指数增长意义 */
    private final double multiplier;

    /** 等待上限（毫秒） */
    private final long capMs;

    /**
     * 默认构造：base=500ms，multiplier=2.0，cap=30s。
     */
    public ExponentialBackoff() {
        this(500L, 2.0, 30_000L);
    }

    @Override
    public long nextBackoffMs(int attempt) {
        if (attempt <= 1) {
            return Math.min(baseMs, capMs);
        }
        // 使用 double 计算再回退到 long，避免溢出
        double value = baseMs * Math.pow(multiplier, attempt - 1);
        if (value >= capMs || value < 0 || Double.isInfinite(value)) {
            return capMs;
        }
        return (long) Math.min(value, capMs);
    }

    @Override
    public String name() {
        return "EXPONENTIAL";
    }
}