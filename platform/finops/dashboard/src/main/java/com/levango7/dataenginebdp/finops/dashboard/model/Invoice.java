package com.levango7.dataenginebdp.finops.dashboard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 出账发票实体。
 *
 * <p>P-03 出账支付分账（后半段）— 发票数据模型。</p>
 *
 * <p>出账流程：账单聚合 → 生成发票 → 发送发票 → 收款 → 分账通知</p>
 *
 * @author finops-team
 * @since 0.2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Invoice {

    /** 发票唯一标识（UUID） */
    private String id;

    /** 租户 ID */
    private String tenantId;

    /** 发票编号（业务可读，如 INV-2026-09-0001） */
    private String invoiceNo;

    /** 账单月份（yyyy-MM） */
    private String billingMonth;

    /** 发票金额（元，含税） */
    private BigDecimal amount;

    /** 税额（元） */
    private BigDecimal taxAmount;

    /** 不含税金额（元） */
    private BigDecimal amountExcludingTax;

    /** 发票状态：draft / issued / sent / paid / overdue / cancelled */
    private String status;

    /** 付款截止日期 */
    private LocalDate dueDate;

    /** 发票开具日期 */
    private LocalDate issueDate;

    /** 付款时间 */
    private Instant paidAt;

    /** 发送时间 */
    private Instant sentAt;

    /** 收件人邮箱 */
    private String recipientEmail;

    /** 发票明细项 */
    private List<InvoiceItem> items;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private Instant createdAt;

    /** 更新时间 */
    private Instant updatedAt;

    /**
     * 发票明细项。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvoiceItem {
        /** 明细名称 */
        private String name;
        /** 资源维度（CPU/MEMORY/STORAGE/GPU/NETWORK/SCANNED_DATA） */
        private String dimension;
        /** 用量 */
        private BigDecimal quantity;
        /** 单价 */
        private BigDecimal unitPrice;
        /** 金额 */
        private BigDecimal amount;
    }
}