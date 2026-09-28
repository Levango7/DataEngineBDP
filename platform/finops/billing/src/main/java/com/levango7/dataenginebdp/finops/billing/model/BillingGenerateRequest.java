package com.levango7.dataenginebdp.finops.billing.model;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

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

    /** 定价配置名；当前仅支持内置单价表（不传或传 default），其他取值一律报 400，不静默忽略 */
    private String pricingConfigName;

    /**
     * 是否覆盖已存在的同租户同账期账单（默认 false，重复生成返回已存在账单）。
     *
     * <p>必须用包装类型而非原始 {@code boolean}：Spring Boot 4（Jackson 3）对请求体中
     * 缺失的原始类型按 null 处理并抛 FAIL_ON_NULL_FOR_PRIMITIVES，
     * 使"省略该字段"这一合法调用直接绑定失败（实测：open-api-catalog 之外的调用方
     * 一旦不带 overwrite 就收不到 400/403 之外的有用错误信息）。</p>
     */
    private Boolean overwrite;

    /** null 安全的覆盖标志读取器：缺省视为 false。 */
    public boolean isOverwrite() {
        return Boolean.TRUE.equals(overwrite);
    }

    /**
     * 计量汇入的用量明细（如 open-api-catalog 的 API 调用量）。
     *
     * <p>契约由 {@link UsageRecord} 定义：字段名不匹配即反序列化失败（400），
     * 防止上游改用量的字段名被静默忽略。这些明细与 Prometheus 采集的资源维度
     * 合并计价，不覆盖后者。</p>
     */
    @Valid
    private List<UsageRecord> usageData;
}