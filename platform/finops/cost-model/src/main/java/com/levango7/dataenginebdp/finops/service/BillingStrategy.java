package com.levango7.dataenginebdp.finops.service;

import com.levango7.dataenginebdp.finops.model.CostResult;
import com.levango7.dataenginebdp.finops.model.PricingConfig;
import com.levango7.dataenginebdp.finops.model.ResourceUsage;

import java.util.List;

/**
 * 计费策略接口。
 *
 * <p>三种计费方式各对应一个实现：</p>
 * <ul>
 *   <li>{@link OnDemandBillingStrategy} 按量计费</li>
 *   <li>{@link ReservedBillingStrategy} 包年计费</li>
 *   <li>{@link TieredBillingStrategy} 阶梯计费</li>
 * </ul>
 *
 * <p>策略模式使新增计费方式无需修改 {@link CostCalculationService}。</p>
 */
public interface BillingStrategy {

    /**
     * 计算一组资源用量的成本。
     *
     * @param usages       资源用量列表（同一 tenant+namespace）
     * @param pricingConfig 定价配置
     * @return 成本结果
     */
    CostResult calculate(List<ResourceUsage> usages, PricingConfig pricingConfig);
}