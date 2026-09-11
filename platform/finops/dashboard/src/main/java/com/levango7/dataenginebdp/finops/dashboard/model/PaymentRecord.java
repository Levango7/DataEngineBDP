package com.levango7.dataenginebdp.finops.dashboard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付记录实体。
 *
 * <p>P-03 出账支付分账（后半段）— 支付数据模型。</p>
 *
 * @author finops-team
 * @since 0.2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRecord {

    /** 支付记录唯一标识（UUID） */
    private String id;

    /** 关联发票 ID */
    private String invoiceId;

    /** 租户 ID */
    private String tenantId;

    /** 支付金额（元） */
    private BigDecimal amount;

    /** 支付状态：pending / success / failed / refunded */
    private String status;

    /** 支付网关名称（mock / alipay / wechat_pay / unionpay） */
    private String gateway;

    /** 支付网关交易号 */
    private String gatewayTransactionId;

    /** 支付方式（online / transfer / offline） */
    private String paymentMethod;

    /** 支付时间 */
    private Instant paidAt;

    /** 退款时间 */
    private Instant refundedAt;

    /** 失败原因 */
    private String failureReason;

    /** 创建时间 */
    private Instant createdAt;

    /** 更新时间 */
    private Instant updatedAt;
}