package com.levango7.dataenginebdp.ruleengine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量规则执行结果（任务 F）。
 *
 * <p>单条失败隔离：每条规则独立 status，批次汇总成功/失败计数。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchRuleExecutionResult {

    /** 逐条结果。 */
    @Builder.Default
    private List<RuleExecutionResult> results = new ArrayList<>();

    /** 成功条数。 */
    private int successCount;

    /** 失败条数。 */
    private int failedCount;

    /** 总耗时 ms。 */
    private long totalDurationMs;

    /** 完成时间。 */
    private LocalDateTime executedAt;
}
