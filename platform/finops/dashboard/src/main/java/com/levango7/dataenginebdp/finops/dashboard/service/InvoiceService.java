package com.levango7.dataenginebdp.finops.dashboard.service;

import com.levango7.dataenginebdp.finops.dashboard.model.Invoice;
import com.levango7.dataenginebdp.finops.dashboard.model.BillSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 出账服务（发票生成与发送）。
 *
 * <p>P-03 出账支付分账（后半段）— 出账服务骨架。</p>
 *
 * <p>出账流程：
 * <pre>
 *   账单聚合（BillingAggregatorService）
 *     → 生成发票（generateInvoice）  ← 本类
 *     → 发送发票（sendInvoice）      ← 本类
 *     → 收款（PaymentGateway）       ← PaymentService
 *     → 分账通知（SplitNotificationService）
 * </pre>
 * </p>
 *
 * <p><b>TODO 待实现：</b>
 * <ul>
 *   <li>TODO: 发票 PDF 生成（集成 PDF 库）</li>
 *   <li>TODO: 发票邮件模板渲染</li>
 *   <li>TODO: 发票编号自动生成规则（需全局序列）</li>
 *   <li>TODO: 税率配置化（不同租户/套餐可能不同税率）</li>
 *   <li>TODO: 发票持久化到数据库</li>
 *   <li>TODO: 发票重发机制</li>
 *   <li>TODO: 与合同管理联动（关联合同 ID）</li>
 * </ul></p>
 *
 * @author finops-team
 * @since 0.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    /** 默认税率（6% 增值税） */
    @Value("${finops.invoice.tax-rate:0.06}")
    private double taxRate;

    /** 默认付款期限（天） */
    @Value("${finops.invoice.due-days:30}")
    private int dueDays;

    /** 发票编号前缀 */
    @Value("${finops.invoice.no-prefix:INV}")
    private String invoiceNoPrefix;

    // TODO: 注入 InvoiceRepository（待创建）
    // private final InvoiceRepository invoiceRepository;

    /**
     * 根据账单汇总生成发票。
     *
     * @param billSummary 账单汇总（来自 BillingAggregatorService）
     * @param tenantId    租户 ID
     * @return 生成的发票
     */
    public Invoice generateInvoice(BillSummary billSummary, String tenantId) {
        log.info("开始生成发票: tenant={}, month={}", tenantId, billSummary.getBillingMonth());

        // TODO: 校验是否已生成过该月发票（幂等性）
        // TODO: 从合同管理获取税率和付款条款

        BigDecimal totalAmount = billSummary.getTotalCost() != null
                ? billSummary.getTotalCost() : BigDecimal.ZERO;
        BigDecimal taxAmount = totalAmount.multiply(BigDecimal.valueOf(taxRate))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal amountExcludingTax = totalAmount.subtract(taxAmount)
                .setScale(2, RoundingMode.HALF_UP);

        String invoiceNo = generateInvoiceNo(billSummary.getBillingMonth());
        LocalDate issueDate = LocalDate.now();
        LocalDate dueDate = issueDate.plusDays(dueDays);

        Invoice invoice = Invoice.builder()
                .id(UUID.randomUUID().toString())
                .tenantId(tenantId)
                .invoiceNo(invoiceNo)
                .billingMonth(billSummary.getBillingMonth())
                .amount(totalAmount)
                .taxAmount(taxAmount)
                .amountExcludingTax(amountExcludingTax)
                .status("draft")
                .dueDate(dueDate)
                .issueDate(issueDate)
                .items(buildInvoiceItems(billSummary))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        log.info("发票已生成: no={}, tenant={}, amount={}, tax={}",
                invoiceNo, tenantId, totalAmount, taxAmount);

        // TODO: 持久化发票到数据库
        // invoiceRepository.save(invoice);

        return invoice;
    }

    /**
     * 发送发票给客户。
     *
     * @param invoice      待发送的发票
     * @param recipientEmail 收件人邮箱
     * @return 发送后的发票（状态更新为 sent）
     */
    public Invoice sendInvoice(Invoice invoice, String recipientEmail) {
        log.info("开始发送发票: no={}, tenant={}, email={}",
                invoice.getInvoiceNo(), invoice.getTenantId(), recipientEmail);

        // TODO: 生成发票 PDF
        // byte[] pdf = generatePdf(invoice);

        // TODO: 渲染邮件模板
        // String emailBody = renderEmailTemplate(invoice);

        // TODO: 发送邮件（集成邮件服务）
        // emailService.send(recipientEmail, "发票通知", emailBody, pdf);

        // 更新发票状态
        invoice.setRecipientEmail(recipientEmail);
        invoice.setStatus("sent");
        invoice.setSentAt(Instant.now());
        invoice.setUpdatedAt(Instant.now());

        log.info("发票已发送: no={}, tenant={}", invoice.getInvoiceNo(), invoice.getTenantId());

        // TODO: 更新数据库
        // invoiceRepository.save(invoice);

        return invoice;
    }

    /**
     * 标记发票为已付款。
     *
     * @param invoiceId 发票 ID
     * @return 更新后的发票
     */
    public Invoice markAsPaid(String invoiceId) {
        log.info("标记发票已付款: id={}", invoiceId);

        // TODO: 从数据库查询发票
        // Invoice invoice = invoiceRepository.findById(invoiceId)
        //         .orElseThrow(() -> new IllegalArgumentException("发票不存在: " + invoiceId));

        // TODO: 校验发票状态（仅 sent 状态可标记为 paid）
        // TODO: 更新发票状态
        // invoice.setStatus("paid");
        // invoice.setPaidAt(Instant.now());
        // invoiceRepository.save(invoice);

        // TODO: 触发分账通知
        // splitNotificationService.notifyPaymentCompleted(invoice);

        log.warn("TODO: markAsPaid 尚未实现，当前为骨架");
        return null;
    }

    /**
     * 生成发票编号。
     *
     * <p>格式：INV-{yyyy}-{MM}-{序号}，如 INV-2026-09-0001</p>
     *
     * TODO: 改为全局序列（数据库或 Redis），保证唯一性
     */
    private String generateInvoiceNo(String billingMonth) {
        // TODO: 从数据库获取当月序号 + 1
        int sequence = 1; // 骨架：固定为 1
        return String.format("%s-%s-%04d", invoiceNoPrefix,
                billingMonth.replace("-", "-"), sequence);
    }

    /**
     * 根据账单汇总构建发票明细项。
     *
     * TODO: 从账单明细拆分为多个发票项（按资源维度）
     */
    private java.util.List<Invoice.InvoiceItem> buildInvoiceItems(BillSummary billSummary) {
        // TODO: 按资源维度拆分明细
        // 骨架：返回空列表，待实现
        return java.util.List.of();
    }
}