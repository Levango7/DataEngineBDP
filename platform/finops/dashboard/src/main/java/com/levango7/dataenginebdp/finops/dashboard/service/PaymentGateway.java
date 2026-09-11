package com.levango7.dataenginebdp.finops.dashboard.service;

import com.levango7.dataenginebdp.finops.dashboard.model.PaymentRecord;

/**
 * 支付网关接口。
 *
 * <p>P-03 出账支付分账（后半段）— 支付网关抽象接口。</p>
 *
 * <p>支持多种支付网关实现：
 * <ul>
 *   <li>{@link MockPaymentGateway} — Mock 实现（开发/测试用）</li>
 *   <li>TODO: AlipayPaymentGateway — 支付宝支付</li>
 *   <li>TODO: WechatPayGateway — 微信支付</li>
 *   <li>TODO: UnionPayGateway — 银联支付</li>
 *   <li>TODO: BankTransferGateway — 银行转账</li>
 * </ul></p>
 *
 * <p>切换方式：通过配置 {@code finops.payment.gateway} 选择实现，
 * 或通过 Spring Profile 激活不同实现。</p>
 *
 * @author finops-team
 * @since 0.2.0
 */
public interface PaymentGateway {

    /**
     * 发起支付。
     *
     * @param invoiceId  关联发票 ID
     * @param tenantId   租户 ID
     * @param amount     支付金额（元）
     * @param paymentMethod 支付方式（online / transfer / offline）
     * @return 支付记录
     */
    PaymentRecord initiatePayment(String invoiceId, String tenantId,
                                  java.math.BigDecimal amount, String paymentMethod);

    /**
     * 查询支付状态。
     *
     * @param paymentId 支付记录 ID
     * @return 支付记录（含最新状态）
     */
    PaymentRecord queryPaymentStatus(String paymentId);

    /**
     * 退款。
     *
     * @param paymentId 支付记录 ID
     * @param refundAmount 退款金额
     * @return 更新后的支付记录
     */
    PaymentRecord refund(String paymentId, java.math.BigDecimal refundAmount);

    /**
     * 获取网关名称。
     *
     * @return 网关标识（mock / alipay / wechat_pay / unionpay）
     */
    String getGatewayName();
}