package com.shuqing.bigdata.finops.dashboard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 分账结果项。
 *
 * <p>表示将一笔总成本按分账比例分配到子工作空间后的分项。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AllocationItem {

    /** 父工作空间（或 namespace） */
    private String parentWorkspace;

    /** 子工作空间名 */
    private String subWorkspace;

    /** 分账比例（0-1） */
    private double ratio;

    /** 分账前总成本（元） */
    private BigDecimal originalCost;

    /** 分账后成本（元） */
    private BigDecimal allocatedCost;

    /** 按维度分账后成本：dimension → 成本 */
    private Map<String, BigDecimal> dimensionAllocatedCosts;

    /** 分账维度（namespace / workspace_label） */
    private String dimension;
}