package com.levango7.dataenginebdp.finops.service;

import com.levango7.dataenginebdp.finops.model.BillingMethod;
import com.levango7.dataenginebdp.finops.model.CostResult;
import com.levango7.dataenginebdp.finops.model.PricingConfig;
import com.levango7.dataenginebdp.finops.model.ResourceDimension;
import com.levango7.dataenginebdp.finops.model.ResourceUsage;
import com.levango7.dataenginebdp.finops.model.TieredPricingTier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶梯计费策略。
 *
 * <p>支持两种阶梯模式（由 {@link TieredPricingTier#isCumulative()} 标识）：</p>
 * <ul>
 *   <li>累计阶梯（cumulative=true）：各档独立计价后求和。例如累计用量 150 落在
 *       档1[0,100)@1.0 与档2[100,∞)@2.0，则成本 = 100×1.0 + 50×2.0 = 200</li>
 *   <li>统一阶梯（cumulative=false）：按累计用量命中档位统一单价。
 *       例如累计用量 150 命中档2[100,∞)@2.0，则成本 = 150×2.0 = 300</li>
 * </ul>
 */
@Component
public class TieredBillingStrategy implements BillingStrategy {

    private static final int COST_SCALE = 4;

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
        StringBuilder note = new StringBuilder("阶梯计费：累计用量阶梯计价");

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

            List<TieredPricingTier> tiers = pricingConfig.getTieredPrices() == null
                    ? null : pricingConfig.getTieredPrices().get(u.getDimension());
            BigDecimal cost;
            if (tiers == null || tiers.isEmpty()) {
                // 无阶梯配置，回退到按量单价
                Double price = pricingConfig.getUnitPrices() == null
                        ? null : pricingConfig.getUnitPrices().get(u.getDimension());
                if (price == null) {
                    price = 0.0;
                }
                cost = BigDecimal.valueOf(u.getAmount())
                        .multiply(BigDecimal.valueOf(price))
                        .setScale(COST_SCALE, RoundingMode.HALF_UP);
                note.append(String.format("; %s 无阶梯配置回退按量单价%.4f", u.getDimension(), price));
            } else {
                cost = computeTieredCost(u.getAmount(), tiers);
                note.append(String.format("; %s 用量%.2f 阶梯成本%s",
                        u.getDimension(), u.getAmount(), cost));
            }

            dimensionCosts.merge(u.getDimension(), cost, BigDecimal::add);
            total = total.add(cost);
        }

        return CostResult.builder()
                .tenant(tenant)
                .namespace(namespace)
                .billingMethod(BillingMethod.TIERED)
                .start(start)
                .end(end)
                .totalCost(total.setScale(COST_SCALE, RoundingMode.HALF_UP))
                .dimensionCosts(dimensionCosts)
                .dimensionUsages(dimensionUsages)
                .gpuModelCosts(gpuModelCosts)
                .note(note.toString())
                .build();
    }

    /**
     * 按阶梯档位计算成本。
     *
     * @param totalUsage 累计用量
     * @param tiers      阶梯档位列表（按 lowerBound 升序）
     * @return 成本
     */
    private BigDecimal computeTieredCost(double totalUsage, List<TieredPricingTier> tiers) {
        // 判断是否累计阶梯（以第一个档位的 cumulative 标识为准）
        boolean cumulative = !tiers.isEmpty() && tiers.get(0).isCumulative();

        if (!cumulative) {
            // 统一阶梯：找到命中的档位
            for (TieredPricingTier tier : tiers) {
                double upper = tier.getUpperBound() == null
                        ? Double.MAX_VALUE : tier.getUpperBound();
                if (totalUsage >= tier.getLowerBound() && totalUsage < upper) {
                    return BigDecimal.valueOf(totalUsage)
                            .multiply(BigDecimal.valueOf(tier.getUnitPrice()))
                            .setScale(COST_SCALE, RoundingMode.HALF_UP);
                }
            }
            // 未命中任何档位（理论上不应发生），按最后一档单价
            TieredPricingTier last = tiers.get(tiers.size() - 1);
            return BigDecimal.valueOf(totalUsage)
                    .multiply(BigDecimal.valueOf(last.getUnitPrice()))
                    .setScale(COST_SCALE, RoundingMode.HALF_UP);
        }

        // 累计阶梯：各档独立计价求和
        BigDecimal cost = BigDecimal.ZERO;
        double remaining = totalUsage;
        for (TieredPricingTier tier : tiers) {
            if (remaining <= 0) {
                break;
            }
            double tierLower = tier.getLowerBound();
            double tierUpper = tier.getUpperBound() == null
                    ? Double.MAX_VALUE : tier.getUpperBound();
            // 该档覆盖的用量区间长度
            double tierWidth = tierUpper - tierLower;
            // 该档实际计价用量 = min(剩余用量, 该档可用宽度)
            double tierUsage = Math.min(remaining, tierWidth);
            if (tierUsage > 0) {
                cost = cost.add(BigDecimal.valueOf(tierUsage)
                        .multiply(BigDecimal.valueOf(tier.getUnitPrice()))
                        .setScale(COST_SCALE, RoundingMode.HALF_UP));
                remaining -= tierUsage;
            }
        }
        return cost;
    }
}