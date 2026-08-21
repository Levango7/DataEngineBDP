package com.shuqing.bigdata.finops.service;

import com.shuqing.bigdata.finops.model.BillingMethod;
import com.shuqing.bigdata.finops.model.CostResult;
import com.shuqing.bigdata.finops.model.PricingConfig;
import com.shuqing.bigdata.finops.model.ResourceDimension;
import com.shuqing.bigdata.finops.model.ResourceUsage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 包年（预留实例）计费策略。
 *
 * <p>计算逻辑：预留实例按月价分摊到小时，分摊率 = 预留数量 / 实际使用数量。
 * 若实际用量 ≤ 预留数量，全部按预留价计费；超出部分按按量单价计费（混合计费）。</p>
 *
 * <p>分摊公式：</p>
 * <ul>
 *   <li>预留覆盖用量 = min(实际用量, 预留数量)</li>
 *   <li>预留成本 = 预留覆盖用量 × (月价 / 月小时数)</li>
 *   <li>超出用量 = max(0, 实际用量 - 预留数量)</li>
 *   <li>超出成本 = 超出用量 × 按量单价</li>
 *   <li>总成本 = 预留成本 + 超出成本</li>
 * </ul>
 */
@Component
public class ReservedBillingStrategy implements BillingStrategy {

    private static final int COST_SCALE = 4;
    private static final double DEFAULT_HOURS_PER_MONTH = 730.0;

    @Override
    public CostResult calculate(List<ResourceUsage> usages, PricingConfig pricingConfig) {
        Map<ResourceDimension, Double> dimensionUsages = new HashMap<>();
        Map<ResourceDimension, BigDecimal> dimensionCosts = new HashMap<>();
        Map<String, BigDecimal> gpuModelCosts = new HashMap<>();

        BigDecimal total = BigDecimal.ZERO;
        Instant start = null;
        Instant end = null;
        String tenant = null;
        String namespace = null;
        StringBuilder note = new StringBuilder("包年计费：预留实例分摊");

        double hoursPerMonth = pricingConfig.getReservedHoursPerMonth() > 0
                ? pricingConfig.getReservedHoursPerMonth() : DEFAULT_HOURS_PER_MONTH;

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

            double actualUsage = u.getAmount();
            double reservedQty = 0.0;
            if (pricingConfig.getReservedQuantities() != null) {
                reservedQty = pricingConfig.getReservedQuantities()
                        .getOrDefault(u.getDimension(), 0.0);
            }
            double reservedMonthlyPrice = 0.0;
            if (pricingConfig.getReservedMonthlyPrices() != null) {
                reservedMonthlyPrice = pricingConfig.getReservedMonthlyPrices()
                        .getOrDefault(u.getDimension(), 0.0);
            }
            Double onDemandPrice = pricingConfig.getUnitPrices() == null
                    ? null : pricingConfig.getUnitPrices().get(u.getDimension());
            if (onDemandPrice == null) {
                onDemandPrice = 0.0;
            }

            // 预留覆盖用量
            double coveredUsage = Math.min(actualUsage, reservedQty);
            // 超出用量
            double excessUsage = Math.max(0.0, actualUsage - reservedQty);

            BigDecimal reservedCost = BigDecimal.valueOf(coveredUsage)
                    .multiply(BigDecimal.valueOf(reservedMonthlyPrice))
                    .divide(BigDecimal.valueOf(hoursPerMonth), COST_SCALE, RoundingMode.HALF_UP);
            BigDecimal excessCost = BigDecimal.valueOf(excessUsage)
                    .multiply(BigDecimal.valueOf(onDemandPrice))
                    .setScale(COST_SCALE, RoundingMode.HALF_UP);
            BigDecimal cost = reservedCost.add(excessCost);

            dimensionCosts.merge(u.getDimension(), cost, BigDecimal::add);
            total = total.add(cost);

            if (reservedQty > 0) {
                note.append(String.format("; %s 预留%.2f 实用%.2f 超出%.2f",
                        u.getDimension(), reservedQty, actualUsage, excessUsage));
            }
        }

        return CostResult.builder()
                .tenant(tenant)
                .namespace(namespace)
                .billingMethod(BillingMethod.RESERVED)
                .start(start)
                .end(end)
                .totalCost(total.setScale(COST_SCALE, RoundingMode.HALF_UP))
                .dimensionCosts(dimensionCosts)
                .dimensionUsages(dimensionUsages)
                .gpuModelCosts(gpuModelCosts)
                .note(note.toString())
                .build();
    }
}