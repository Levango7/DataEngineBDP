package com.levango7.dataenginebdp.rule.engine.orchestrator.retry;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 固定退避策略。
 *
 * <p>每次重试前等待固定时长。适用于失败原因与时间无关的瞬时故障，
 * 例如外部 API 偶发 503。</p>
 *
 * <p>设计说明：intervalMs 上限 60_000，避免误配过大间隔导致调度卡死。</p>
 */
@Data
@AllArgsConstructor
public class FixedBackoff implements RetryPolicy {

    /** 固定等待时长（毫秒） */
    private final long intervalMs;

    /**
     * 默认构造：1 秒固定退避。
     */
    public FixedBackoff() {
        this(1000L);
    }

    @Override
    public long nextBackoffMs(int attempt) {
        return Math.min(intervalMs, 60_000L);
    }

    @Override
    public String name() {
        return "FIXED";
    }
}