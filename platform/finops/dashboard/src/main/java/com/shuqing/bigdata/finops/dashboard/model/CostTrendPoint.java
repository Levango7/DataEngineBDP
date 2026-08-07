package com.shuqing.bigdata.finops.dashboard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 成本趋势看板数据点。
 *
 * <p>按时间粒度（小时/天/月）聚合的成本时间序列，用于成本趋势折线图。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostTrendPoint {

    /** 时间戳（UTC） */
    private Instant timestamp;

    /** 总成本（元） */
    private BigDecimal totalCost;

    /** CPU 成本（元） */
    private BigDecimal cpuCost;

    /** 内存成本（元） */
    private BigDecimal memoryCost;

    /** 存储成本（元） */
    private BigDecimal storageCost;

    /** GPU 成本（元） */
    private BigDecimal gpuCost;

    /** 网络成本（元） */
    private BigDecimal networkCost;

    /** 时间粒度（HOUR / DAY / MONTH） */
    private String granularity;
}