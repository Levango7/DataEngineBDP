package com.levango7.dataenginebdp.finops.billing.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 账单生成响应。
 *
 * <p>POST /api/finops/v1/billing/generate 的响应体，
 * 返回生成的账单完整信息（含明细项）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingGenerateResponse {

    /** 账单 ID */
    private String id;

    /** 租户 ID */
    private String tenantId;

    /** 账期 */
    private String billingPeriod;

    /** 账单明细项列表 */
    private List<BillingItem> items;

    /** 账单总金额（人民币元） */
    private BigDecimal totalAmount;

    /** 账单状态 */
    private String status;

    /** 账单生成时间（UTC） */
    private Instant generatedAt;

    /** 账期起始时间（UTC，含） */
    private Instant periodStart;

    /** 账期结束时间（UTC，不含） */
    private Instant periodEnd;

    /** 备注/说明 */
    private String note;
}