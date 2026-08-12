package com.levango7.dataenginebdp.finops.service;

import com.levango7.dataenginebdp.finops.model.BillingMethod;
import com.levango7.dataenginebdp.finops.model.CostResult;
import com.levango7.dataenginebdp.finops.model.PricingConfig;
import com.levango7.dataenginebdp.finops.model.ResourceDimension;
import com.levango7.dataenginebdp.finops.model.ResourceUsage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按量计费策略。
 *
 * <p>计算公式：成本 = 实时用量 × 单价。GPU 维度按型号差异化定价
 * （从 {@link PricingConfig#getGpuPrices()} 取型号单价）。</p>
 */
@Component
public class OnDemandBillingStrategy implements BillingStrategy {

    private static final int COST_SCALE = 4;

    @Override
    public CostResult calculate(List<ResourceUsage> usages, PricingConfig pricingConfig) {
        // 按维度聚合用量
        Map<ResourceDimension, Double> dimensionUsages = new HashMap<>();
        // GPU 按型号聚合用量
        Map<String, Double> gpuModelUsages = new HashMap<>();
        // 维度成本
        Map<ResourceDimension, BigDecimal> dimensionCosts = new HashMap<>();
        // GPU 按型号成本
        Map<String, BigDecimal> gpuModelCosts = new HashMap<>();

        BigDecimal total = BigDecimal.ZERO;
        Instant start = null;
        Instant end = null;
        String tenant = null;
        String namespace = null;

        for (ResourceUsage u : usages) {
            tenant = u.getTenant();
            namespace = u.getNamespace();
            if (start == null || u.getStart().isBefore(start)) {
                start = u.getStart();
            }
            if (end == null || u.getEnd().isAfter(end)) {
                end = u.getEnd();
            }

            dimensionUsages.merge(u.getDimension(), u.getAmount(), Double::sum);

            if (u.getDimension() == ResourceDimension.GPU) {
                String model = u.getGpuModel() == null ? "default" : u.getGpuModel();
                gpuModelUsages.merge(model, u.getAmount(), Double::sum);
                // GPU 按型号单价
                Double price = pricingConfig.getGpuPrices() == null
                        ? null : pricingConfig.getGpuPrices().get(model);
                if (price == null && pricingConfig.getUnitPrices() != null) {
                    price = pricingConfig.getUnitPrices().get(ResourceDimension.GPU);
                }
                if (price == null) {
                    price = 0.0;
                }
                BigDecimal cost = BigDecimal.valueOf(u.getAmount())
                        .multiply(BigDecimal.valueOf(price))
                        .setScale(COST_SCALE, RoundingMode.HALF_UP);
                gpuModelCosts.merge(model, cost, BigDecimal::add);
                total = total.add(cost);
            } else {
                Double price = pricingConfig.getUnitPrices() == null
                        ? null : pricingConfig.getUnitPrices().get(u.getDimension());
                if (price == null) {
                    price = 0.0;
                }
                BigDecimal cost = BigDecimal.valueOf(u.getAmount())
                        .multiply(BigDecimal.valueOf(price))
                        .setScale(COST_SCALE, RoundingMode.HALF_UP);
                dimensionCosts.merge(u.getDimension(), cost, BigDecimal::add);
                total = total.add(cost);
            }
        }

        // GPU 维度汇总成本
        BigDecimal gpuTotal = gpuModelCosts.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (gpuTotal.compareTo(BigDecimal.ZERO) > 0) {
            dimensionCosts.put(ResourceDimension.GPU, gpuTotal);
        }

        return CostResult.builder()
                .tenant(tenant)
                .namespace(namespace)
                .billingMethod(BillingMethod.ON_DEMAND)
                .start(start)
                .end(end)
                .totalCost(total.setScale(COST_SCALE, RoundingMode.HALF_UP))
                .dimensionCosts(dimensionCosts)
                .dimensionUsages(dimensionUsages)
                .gpuModelCosts(gpuModelCosts)
                .note("按量计费：实时用量 × 单价")
                .build();
    }
}