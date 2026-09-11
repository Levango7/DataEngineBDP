package com.levango7.dataenginebdp.finops.dashboard.service;

import com.levango7.dataenginebdp.finops.dashboard.model.AllocationItem;
import com.levango7.dataenginebdp.finops.dashboard.model.Invoice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分账通知服务（分账闭环通知）。
 *
 * <p>P-03 出账支付分账（后半段）— 分账通知闭环骨架。</p>
 *
 * <p>分账通知闭环流程：
 * <pre>
 *   账单聚合 → 发票生成 → 发票发送 → 客户付款
 *     → 分账执行（AllocationService）
 *     → 分账通知（本类）  ← 通知各子工作空间分账结果
 *     → 通知租户管理员
 *     → 记录审计日志
 * </pre>
 * </p>
 *
 * <p><b>TODO 待实现：</b>
 * <ul>
 *   <li>TODO: 邮件通知实现（集成邮件服务）</li>
 *   <li>TODO: 站内信通知实现</li>
 *   <li>TODO: Webhook 通知实现（可配置回调 URL）</li>
 *   <li>TODO: 通知模板渲染</li>
 *   <li>TODO: 通知重试机制（失败自动重试 3 次）</li>
 *   <li>TODO: 通知偏好配置（租户可选择通知方式）</li>
 *   <li>TODO: 审计日志持久化</li>
 * </ul></p>
 *
 * @author finops-team
 * @since 0.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SplitNotificationService {

    /** 是否启用分账通知 */
    @Value("${finops.split.notification.enabled:true}")
    private boolean notificationEnabled;

    /** 通知方式：email / inapp / webhook / all */
    @Value("${finops.split.notification.method:all}")
    private String notificationMethod;

    // TODO: 注入邮件服务
    // private final EmailService emailService;

    // TODO: 注入站内信服务
    // private final InAppMessageService messageService;

    // TODO: 注入 Webhook 服务
    // private final WebhookService webhookService;

    /**
     * 通知分账执行完成。
     *
     * <p>在分账执行后调用，通知各子工作空间分账结果。</p>
     *
     * @param tenantId      租户 ID
     * @param invoice       关联发票
     * @param allocationItems 分账结果列表
     */
    public void notifySplitCompleted(String tenantId, Invoice invoice,
                                     List<AllocationItem> allocationItems) {
        if (!notificationEnabled) {
            log.info("分账通知已禁用，跳过: tenant={}, invoice={}", tenantId, invoice.getInvoiceNo());
            return;
        }

        log.info("开始发送分账通知: tenant={}, invoice={}, 子工作空间数={}",
                tenantId, invoice.getInvoiceNo(), allocationItems.size());

        for (AllocationItem item : allocationItems) {
            notifySingleSubWorkspace(tenantId, invoice, item);
        }

        // 通知租户管理员（汇总通知）
        notifyTenantAdmin(tenantId, invoice, allocationItems);

        // 记录审计日志
        logAudit(tenantId, invoice, allocationItems);

        log.info("分账通知发送完成: tenant={}, invoice={}", tenantId, invoice.getInvoiceNo());
    }

    /**
     * 通知单个子工作空间分账结果。
     *
     * TODO: 实现邮件/站内信/Webhook 通知
     */
    private void notifySingleSubWorkspace(String tenantId, Invoice invoice, AllocationItem item) {
        log.info("通知子工作空间分账结果: tenant={}, sub={}, ratio={}, amount={}",
                tenantId, item.getSubWorkspace(), item.getRatio(), item.getAllocatedCost());

        // TODO: 根据通知方式发送
        if ("email".equals(notificationMethod) || "all".equals(notificationMethod)) {
            // TODO: emailService.send(...)
            log.debug("TODO: 邮件通知待实现");
        }

        if ("inapp".equals(notificationMethod) || "all".equals(notificationMethod)) {
            // TODO: messageService.send(...)
            log.debug("TODO: 站内信通知待实现");
        }

        if ("webhook".equals(notificationMethod) || "all".equals(notificationMethod)) {
            // TODO: webhookService.call(...)
            log.debug("TODO: Webhook 通知待实现");
        }
    }

    /**
     * 通知租户管理员（分账汇总通知）。
     *
     * TODO: 实现管理员汇总通知
     */
    private void notifyTenantAdmin(String tenantId, Invoice invoice,
                                   List<AllocationItem> allocationItems) {
        log.info("通知租户管理员分账汇总: tenant={}, invoice={}, 总分账项={}",
                tenantId, invoice.getInvoiceNo(), allocationItems.size());
        // TODO: 发送汇总通知邮件/站内信
    }

    /**
     * 通知支付完成（触发分账流程）。
     *
     * <p>在发票付款后调用，触发分账执行 + 通知闭环。</p>
     *
     * @param invoice 已付款的发票
     */
    public void notifyPaymentCompleted(Invoice invoice) {
        log.info("支付完成通知: tenant={}, invoice={}, amount={}",
                invoice.getTenantId(), invoice.getInvoiceNo(), invoice.getAmount());

        // TODO: 触发分账执行
        // List<AllocationItem> items = allocationService.execute(...);

        // TODO: 触发分账通知
        // notifySplitCompleted(invoice.getTenantId(), invoice, items);

        log.warn("TODO: notifyPaymentCompleted 尚未实现，当前为骨架");
    }

    /**
     * 记录审计日志。
     *
     * TODO: 持久化到审计日志表
     */
    private void logAudit(String tenantId, Invoice invoice,
                          List<AllocationItem> allocationItems) {
        log.info("[审计] 分账通知已发送: tenant={}, invoice={}, items={}",
                tenantId, invoice.getInvoiceNo(), allocationItems.size());
        // TODO: 持久化审计日志
    }
}