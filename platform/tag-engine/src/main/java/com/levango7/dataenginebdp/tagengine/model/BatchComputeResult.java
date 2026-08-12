package com.levango7.dataenginebdp.tagengine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量计算结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchComputeResult {

    /** 各标签计算结果 */
    private List<TagComputeResult> results;

    /** 成功数 */
    private long successCount;

    /** 失败数 */
    private long failedCount;

    /** 总耗时（毫秒） */
    private long totalCostMs;
}