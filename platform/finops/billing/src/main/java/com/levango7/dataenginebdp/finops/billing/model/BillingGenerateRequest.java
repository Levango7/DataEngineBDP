package com.levango7.dataenginebdp.finops.billing.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 账单生成请求。
 *
 * <p>POST /api/finops/v1/billing/generate 的请求体。
 * 租户 ID 从 TenantContext 获取（不信任请求参数），此处仅传账期与采集窗口。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingGenerateRequest {

    /** 账期（格式 yyyy-MM，如 2026-08）；不传则取采集窗口对应的自然月 */
    private String billingPeriod;

    /** 采集窗口起始时间（UTC，含）；不传则取账期首日零点 */
    private Instant start;

    /** 采集窗口结束时间（UTC，不含）；不传则取账期次月首日零点 */
    private Instant end;

    /** Kubernetes namespace（不传则采集该租户全部 namespace） */
    private String namespace;

    /** 定价配置名（不传则用默认配置 default） */
    private String pricingConfigName;

    /** 是否覆盖已存在的同租户同账期账单（默认 false，重复生成返回已存在账单） */
    private boolean overwrite;
}