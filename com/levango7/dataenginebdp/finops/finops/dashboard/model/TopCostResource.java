package com.shuqing.bigdata.finops.dashboard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Top10 成本资源看板数据项。
 *
 * <p>按总成本降序排列的前 N 个资源（默认 N=10），用于 Top10 看板展示。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopCostResource {

    /** 资源 ID */
    private String resourceId;

    /** 资源类型 */
    private String resourceType;

    /** 租户 ID */
    private String tenant;

    /** namespace */
    private String namespace;

    /** 工作空间 */
    private String workspace;

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

    /** 占总成本百分比 */
    private double percentage;

    /** 窗口起始时间 */
    private Instant start;

    /** 窗口结束时间 */
    private Instant end;
}