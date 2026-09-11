package com.levango7.dataenginebdp.finops.dashboard.service;

import com.levango7.dataenginebdp.finops.dashboard.model.PaymentRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Mock 支付网关实现。
 *
 * <p>P-03 出账支付分账（后半段）— 支付网关 Mock 实现。</p>
 *
 * <p>用于开发/测试环境，模拟支付流程：
 * <ul>
 *   <li>initiatePayment: 直接返回 success 状态</li>
 *   <li>queryPaymentStatus: 返回内存中存储的支付记录</li>
 *   <li>refund: 直接返回 refunded 状态</li>
 * </ul></p>
 *
 * <p>激活方式：配置 {@code finops.payment.gateway=mock}（默认）</p>
 *
 * <p><b>TODO 待实现：</b>
 * <ul>
 *   <li>TODO: 添加支付延迟模拟（可配置延迟时间）</li>
 *   <li>TODO: 添加支付失败率模拟（可配置失败率）</li>
 *   <li>TODO: 添加支付回调通知模拟</li>
 * </ul></p>
 *
 * @author finops-team
 * @since 0.2.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "finops.payment.gateway", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    /** 内存存储：paymentId → PaymentRecord */
    private final Map<String, PaymentRecord> store = new ConcurrentHashMap<>();

    @Override
    public PaymentRecord initiatePayment(String invoiceId, String tenantId,
                                         BigDecimal amount, String paymentMethod) {
        log.info("[Mock支付] 发起支付: invoice={}, tenant={}, amount={}, method={}",
                invoiceId, tenantId, amount, paymentMethod);

        PaymentRecord record = PaymentRecord.builder()
                .id(UUID.randomUUID().toString())
                .invoiceId(invoiceId)
                .tenantId(tenantId)
                .amount(amount)
                .status("success")  // Mock 直接成功
                .gateway("mock")
                .gatewayTransactionId("MOCK-" + UUID.randomUUID().toString().substring(0, 8))
                .paymentMethod(paymentMethod)
                .paidAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        store.put(record.getId(), record);
        log.info("[Mock支付] 支付成功: paymentId={}, txId={}",
                record.getId(), record.getGatewayTransactionId());
        return record;
    }

    @Override
    public PaymentRecord queryPaymentStatus(String paymentId) {
        log.info("[Mock支付] 查询支付状态: paymentId={}", paymentId);
        return store.get(paymentId);
    }

    @Override
    public PaymentRecord refund(String paymentId, BigDecimal refundAmount) {
        log.info("[Mock支付] 退款: paymentId={}, amount={}", paymentId, refundAmount);
        PaymentRecord record = store.get(paymentId);
        if (record == null) {
            throw new IllegalArgumentException("支付记录不存在: " + paymentId);
        }
        record.setStatus("refunded");
        record.setRefundedAt(Instant.now());
        record.setUpdatedAt(Instant.now());
        store.put(paymentId, record);
        return record;
    }

    @Override
    public String getGatewayName() {
        return "mock";
    }
}