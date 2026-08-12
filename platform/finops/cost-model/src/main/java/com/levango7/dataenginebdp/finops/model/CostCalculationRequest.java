package com.levango7.dataenginebdp.finops.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 成本计算请求。
 *
 * <p>对应 POST /api/v1/cost/calculate 请求体。可传入资源用量列表与定价配置，
 * 或指定已存在的定价配置名由服务端加载。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostCalculationRequest {

    /** 资源用量列表（采集器查询结果或外部传入） */
    @NotEmpty(message = "资源用量列表不能为空")
    @Valid
    private List<ResourceUsage> usages;

    /** 计费方式 */
    @NotNull(message = "计费方式不能为空")
    private BillingMethod billingMethod;

    /** 定价配置；若提供则使用之，否则使用 pricingConfigName 加载已存在配置 */
    @Valid
    private PricingConfig pricingConfig;

    /** 已存在的定价配置名（当 pricingConfig 为空时使用） */
    private String pricingConfigName;
}