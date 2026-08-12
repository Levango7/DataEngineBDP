package com.levango7.dataenginebdp.finops.dashboard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 账单汇总项。
 *
 * <p>按 tenant / namespace / 工作空间聚合的成本汇总，用于账单汇总导出。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillSummary {

    /** 聚合维度（TENANT / NAMESPACE / WORKSPACE） */
    private String groupBy;

    /** 聚合键（如 tenant-a / ns-1 / ws-team1） */
    private String groupKey;

    /** 总成本（元） */
    private BigDecimal totalCost;

    /** 按维度成本：dimension → 成本 */
    private Map<String, BigDecimal> dimensionCosts;

    /** 资源数量 */
    private int resourceCount;

    /** 子项明细数量 */
    private int detailCount;
}