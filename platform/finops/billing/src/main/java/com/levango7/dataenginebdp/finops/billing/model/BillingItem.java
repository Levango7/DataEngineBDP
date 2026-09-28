package com.levango7.dataenginebdp.finops.billing.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 账单明细项。
 *
 * <p>表示账单中某一资源维度的单项费用，含资源类型、用量、单价与金额。
 * 一张账单包含多个明细项（CPU/内存/存储/GPU/网络/查询扫描数据等维度）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingItem {

    /** 资源类型（CPU/MEMORY/STORAGE/GPU/NETWORK/SCANNED_DATA） */
    private String resourceType;

    /** Kubernetes namespace */
    private String namespace;

    /** 用量数值（单位由资源类型决定：CPU 核时、内存/存储 GB·时、GPU 卡时、网络 GB、扫描 TB） */
    private double usage;

    /** 单价（人民币元，精度 0.0001） */
    private BigDecimal unitPrice;

    /** 金额 = 用量 × 单价（人民币元，精度 0.0001） */
    private BigDecimal amount;

    /** GPU 型号（仅当 resourceType=GPU 时有意义，如 A100/V100/Ascend910） */
    private String gpuModel;

    /** 来源引用（计量汇入项如 API ID；Prometheus 采集项为空），用于账单对账追溯 */
    private String sourceRef;
}