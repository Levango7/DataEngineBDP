package com.levango7.dataenginebdp.rule.engine.orchestrator.retry;

import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * 重试执行器。
 *
 * <p>封装"执行-失败-退避-再执行"循环，将重试逻辑与业务调用解耦。
 * 支持自定义可重试异常判定，避免对不可恢复错误（如参数校验失败）盲目重试。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>sleep 通过可注入 Sleeper 实现，便于单元测试用假时钟；</li>
 *   <li>maxAttempts 含首次执行，例如 maxAttempts=3 表示最多执行 3 次（1 次首执行 + 2 次重试）。</li>
 * </ul>
 * </p>
 */
public class RetryExecutor {

    /** 可注入的睡眠函数，便于测试 */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** 默认使用 Thread.sleep */
    private static final Sleeper DEFAULT_SLEEPER = millis -> Thread.sleep(millis);

    private final Sleeper sleeper;

    public RetryExecutor() {
        this(DEFAULT_SLEEPER);
    }

    public RetryExecutor(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    /**
     * 执行带重试的可调用任务。
     *
     * @param task         待执行任务
     * @param policy       重试策略
     * @param maxAttempts  最大尝试次数（含首次）
     * @param retryable    判定异常是否可重试；返回 false 则立即抛出不再重试
     * @param <T>          返回类型
     * @return 任务返回值
     * @throws Exception 任务在所有重试后仍失败时抛出最后一次异常
     */
    public <T> T execute(Callable<T> task, RetryPolicy policy, int maxAttempts,
                         Predicate<Exception> retryable) throws Exception {
        Exception lastException = null;
        int attempt = 0;
        while (attempt < maxAttempts) {
            attempt++;
            try {
                return task.call();
            } catch (Exception e) {
                lastException = e;
                if (!retryable.test(e)) {
                    throw e;
                }
                if (attempt >= maxAttempts) {
                    break;
                }
                long backoff = policy.nextBackoffMs(attempt);
                if (backoff > 0) {
                    sleeper.sleep(backoff);
                }
            }
        }
        throw lastException;
    }

    /**
     * 执行带重试的任务，所有异常均可重试。
     *
     * @param task        待执行任务
     * @param policy      重试策略
     * @param maxAttempts 最大尝试次数
     * @param <T>         返回类型
     * @return 任务返回值
     * @throws Exception 任务最终失败时抛出
     */
    public <T> T execute(Callable<T> task, RetryPolicy policy, int maxAttempts) throws Exception {
        return execute(task, policy, maxAttempts, e -> true);
    }
}