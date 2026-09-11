package com.levango7.dataenginebdp.finops.dashboard.service;

import com.levango7.dataenginebdp.finops.dashboard.model.PaymentRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 支付服务。
 *
 * <p>P-03 出账支付分账（后半段）— 支付服务骨架。</p>
 *
 * <p>支付服务封装支付网关调用，提供统一的支付接口。
 * 支付网关实现通过 Spring 依赖注入自动选择（Mock/支付宝/微信/银联）。</p>
 *
 * <p><b>TODO 待实现：</b>
 * <ul>
 *   <li>TODO: 支付记录持久化到数据库</li>
 *   <li>TODO: 支付回调处理</li>
 *   <li>TODO: 支付超时处理</li>
 *   <li>TODO: 对账机制（与支付网关对账）</li>
 * </ul></p>
 *
 * @author finops-team
 * @since 0.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentGateway paymentGateway;
    private final InvoiceService invoiceService;
    private final SplitNotificationService splitNotificationService;

    /**
     * 发起支付。
     *
     * @param invoiceId    发票 ID
     * @param paymentMethod 支付方式
     * @return 支付记录
     */
    public PaymentRecord initiatePayment(String invoiceId, String paymentMethod) {
        log.info("发起支付: invoice={}, method={}, gateway={}",
                invoiceId, paymentMethod, paymentGateway.getGatewayName());

        // TODO: 查询发票获取金额和租户
        // Invoice invoice = invoiceRepository.findById(invoiceId)
        //         .orElseThrow(() -> new IllegalArgumentException("发票不存在"));
        // BigDecimal amount = invoice.getAmount();
        // String tenantId = invoice.getTenantId();

        // 骨架：使用占位值
        BigDecimal amount = BigDecimal.ZERO; // TODO: 从发票获取
        String tenantId = "unknown"; // TODO: 从发票获取

        PaymentRecord record = paymentGateway.initiatePayment(
                invoiceId, tenantId, amount, paymentMethod);

        // TODO: 持久化支付记录
        // paymentRepository.save(record);

        // 如果支付成功，触发后续流程
        if ("success".equals(record.getStatus())) {
            handlePaymentSuccess(record);
        }

        return record;
    }

    /**
     * 处理支付成功。
     *
     * <p>支付成功后触发：
     * <ol>
     *   <li>标记发票已付款</li>
     *   <li>触发分账通知闭环</li>
     * </ol></p>
     */
    private void handlePaymentSuccess(PaymentRecord record) {
        log.info("支付成功处理: paymentId={}, invoice={}",
                record.getId(), record.getInvoiceId());

        // 1. 标记发票已付款
        invoiceService.markAsPaid(record.getInvoiceId());

        // 2. 触发分账通知闭环
        // TODO: 查询发票后调用
        // splitNotificationService.notifyPaymentCompleted(invoice);

        log.warn("TODO: handlePaymentSuccess 分账通知触发待实现");
    }

    /**
     * 查询支付状态。
     */
    public PaymentRecord queryPaymentStatus(String paymentId) {
        return paymentGateway.queryPaymentStatus(paymentId);
    }

    /**
     * 退款。
     */
    public PaymentRecord refund(String paymentId, BigDecimal refundAmount) {
        log.info("退款: paymentId={}, amount={}", paymentId, refundAmount);
        PaymentRecord record = paymentGateway.refund(paymentId, refundAmount);

        // TODO: 退款后处理（更新发票状态、撤销分账等）
        // TODO: 持久化退款记录

        return record;
    }
}