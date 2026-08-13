package com.levango7.dataenginebdp.finops.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 单日查询账单点（趋势图数据）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyBillingPoint {

    /** 业务日期（yyyy-MM-dd，UTC）。 */
    private String day;

    /** 当日扫描字节数（估算+真实）。 */
    private long bytesScanned;

    /** 当日扫描 TB（展示用）。 */
    private double tbScanned;

    /** 当日查询次数。 */
    private int queryCount;

    /** 当日成本（分层定价结果）。 */
    private BigDecimal cost;
}