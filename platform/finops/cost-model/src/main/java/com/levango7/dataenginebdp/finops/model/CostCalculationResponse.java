package com.levango7.dataenginebdp.finops.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 成本计算响应。
 *
 * <p>对应 POST /api/v1/cost/calculate 响应体，含一个或多个租户/namespace 的成本结果。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostCalculationResponse {

    /** 成本结果列表（按 tenant+namespace 分组） */
    private List<CostResult> results;

    /** 汇总总成本（所有结果之和） */
    private java.math.BigDecimal grandTotal;

    /** 使用的定价配置名 */
    private String pricingConfigName;
}