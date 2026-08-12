package com.levango7.dataenginebdp.finops.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 成本计算结果。
 *
 * <p>表示对一组资源用量按指定计费方式计算后的成本，含明细与汇总。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostResult {

    /** 租户 ID */
    private String tenant;

    /** namespace */
    private String namespace;

    /** 计费方式 */
    private BillingMethod billingMethod;

    /** 成本窗口起始时间（UTC） */
    private Instant start;

    /** 成本窗口结束时间（UTC） */
    private Instant end;

    /** 总成本（人民币元，精度 0.0001） */
    private BigDecimal totalCost;

    /** 按维度的成本明细：dimension → 该维度成本 */
    private Map<ResourceDimension, BigDecimal> dimensionCosts;

    /** 按维度用量明细：dimension → 用量数值 */
    private Map<ResourceDimension, Double> dimensionUsages;

    /** GPU 按型号成本明细：gpuModel → 成本（仅 GPU 维度有值） */
    private Map<String, BigDecimal> gpuModelCosts;

    /** 计算说明（如阶梯命中档位、预留实例分摊率等） */
    private String note;
}