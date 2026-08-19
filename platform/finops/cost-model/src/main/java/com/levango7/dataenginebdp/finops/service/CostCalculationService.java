package com.levango7.dataenginebdp.finops.service;

import com.levango7.dataenginebdp.finops.model.BillingMethod;
import com.levango7.dataenginebdp.finops.model.CostCalculationRequest;
import com.levango7.dataenginebdp.finops.model.CostCalculationResponse;
import com.levango7.dataenginebdp.finops.model.CostResult;
import com.levango7.dataenginebdp.finops.model.PricingConfig;
import com.levango7.dataenginebdp.finops.model.ResourceUsage;
import com.levango7.dataenginebdp.common.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 成本计算服务。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>根据计费方式选择对应 {@link BillingStrategy}；</li>
 *   <li>按 tenant+namespace 分组用量，逐组计算成本；</li>
 *   <li>强制租户隔离：仅计算当前请求租户（来自 JWT）的用量，过滤其他租户数据。</li>
 * </ul>
 */
@Service
public class CostCalculationService {

    private static final Logger log = LoggerFactory.getLogger(CostCalculationService.class);

    private final Map<BillingMethod, BillingStrategy> strategies;
    private final PricingConfigService pricingConfigService;

    public CostCalculationService(List<BillingStrategy> strategyList,
                                  PricingConfigService pricingConfigService) {
        this.pricingConfigService = pricingConfigService;
        // 将策略列表按 BillingMethod 映射，便于 O(1) 查找
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(this::resolveMethod, s -> s));
        log.info("已加载计费策略: {}", strategies.keySet());
    }

    /**
     * 计算成本。
     *
     * @param request 成本计算请求
     * @return 成本计算响应
     */
    public CostCalculationResponse calculate(CostCalculationRequest request) {
        // 1. 解析定价配置：优先使用请求中的，否则按名加载
        PricingConfig pricing = resolvePricing(request);

        // 2. 租户隔离：仅保留当前租户的用量
        List<ResourceUsage> filtered = enforceTenantIsolation(request.getUsages());

        // 3. 按 tenant+namespace 分组
        Map<String, List<ResourceUsage>> groups = filtered.stream()
                .collect(Collectors.groupingBy(
                        u -> u.getTenant() + "/" + u.getNamespace()));

        // 4. 选择计费策略
        BillingStrategy strategy = strategies.get(request.getBillingMethod());
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的计费方式: " + request.getBillingMethod());
        }

        // 5. 逐组计算
        List<CostResult> results = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (List<ResourceUsage> group : groups.values()) {
            CostResult result = strategy.calculate(group, pricing);
            results.add(result);
            grandTotal = grandTotal.add(result.getTotalCost());
        }

        return CostCalculationResponse.builder()
                .results(results)
                .grandTotal(grandTotal)
                .pricingConfigName(pricing.getName())
                .build();
    }

    /**
     * 解析定价配置。
     */
    private PricingConfig resolvePricing(CostCalculationRequest request) {
        if (request.getPricingConfig() != null) {
            return request.getPricingConfig();
        }
        if (request.getPricingConfigName() != null) {
            return pricingConfigService.getByName(request.getPricingConfigName());
        }
        // 兜底：使用默认配置
        return pricingConfigService.getDefault();
    }

    /**
     * 强制租户隔离：仅保留当前请求租户的用量。
     *
     * <p>从 JWT 解析的 tenantId 写入 {@link TenantContext}，此处过滤掉
     * 不属于当前租户的用量，确保 tenant 间成本数据不可见。</p>
     */
    private List<ResourceUsage> enforceTenantIsolation(List<ResourceUsage> usages) {
        String currentTenant = TenantContext.getTenantId();
        if (currentTenant == null) {
            // 未携带租户上下文（如内部调用），放行全部
            return usages;
        }
        List<ResourceUsage> filtered = usages.stream()
                .filter(u -> Objects.equals(u.getTenant(), currentTenant))
                .collect(Collectors.toList());
        if (filtered.size() < usages.size()) {
            log.warn("租户隔离过滤: 原始 {} 条, 保留 {} 条 (tenant={})",
                    usages.size(), filtered.size(), currentTenant);
        }
        return filtered;
    }

    /**
     * 由策略实例反查 BillingMethod。
     */
    private BillingMethod resolveMethod(BillingStrategy strategy) {
        if (strategy instanceof OnDemandBillingStrategy) {
            return BillingMethod.ON_DEMAND;
        }
        if (strategy instanceof ReservedBillingStrategy) {
            return BillingMethod.RESERVED;
        }
        if (strategy instanceof TieredBillingStrategy) {
            return BillingMethod.TIERED;
        }
        throw new IllegalStateException("未知计费策略类型: " + strategy.getClass());
    }
}