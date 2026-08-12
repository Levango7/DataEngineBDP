package com.levango7.dataenginebdp.rule.engine.orchestrator.retry;

/**
 * 重试策略接口。
 *
 * <p>定义在第 {@code attempt} 次失败后下一次重试前的等待时长计算方式。
 * attempt 从 1 开始计数（1 表示首次执行失败后准备第一次重试）。</p>
 *
 * <p>实现方应保证返回值非负，且对超大 attempt 不会溢出。</p>
 */
public interface RetryPolicy {

    /**
     * 计算下一次重试前的退避时长（毫秒）。
     *
     * @param attempt 即将进行的重试次序，从 1 开始
     * @return 等待毫秒数，>=0
     */
    long nextBackoffMs(int attempt);

    /**
     * 策略名称，用于序列化与日志。
     *
     * @return 策略名
     */
    String name();
}