package com.shuqing.bigdata.finops.service;

import com.shuqing.bigdata.finops.model.PricingConfig;
import com.shuqing.bigdata.finops.model.ResourceDimension;
import com.shuqing.bigdata.finops.model.TieredPricingTier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定价配置服务。
 *
 * <p>支持动态配置单价（通过 API 或配置文件）。内存存储（ConcurrentHashMap），
 * 生产环境可替换为 JPA 持久化。启动时加载默认定价配置。</p>
 */
@Service
public class PricingConfigService {

    private final Map<String, PricingConfig> configs = new ConcurrentHashMap<>();

    public PricingConfigService() {
        // 启动时加载默认定价配置
        configs.put("default", buildDefaultPricing());
        configs.put("gpu-differentiated", buildGpuDifferentiatedPricing());
    }

    /**
     * 获取默认定价配置。
     */
    public PricingConfig getDefault() {
        return configs.get("default");
    }

    /**
     * 按名获取定价配置。
     */
    public PricingConfig getByName(String name) {
        PricingConfig config = configs.get(name);
        if (config == null) {
            throw new IllegalArgumentException("定价配置不存在: " + name);
        }
        return config;
    }

    /**
     * 保存或更新定价配置（动态配置单价）。
     */
    public PricingConfig save(PricingConfig config) {
        configs.put(config.getName(), config);
        return config;
    }

    /**
     * 列出所有定价配置名。
     */
    public List<String> listNames() {
        return List.copyOf(configs.keySet());
    }

    /**
     * 默认定价配置：按量单价 + GPU 多卡型号差异化定价。
     */
    private PricingConfig buildDefaultPricing() {
        return PricingConfig.builder()
                .name("default")
                .unitPrices(Map.of(
                        ResourceDimension.CPU, 0.5,
                        ResourceDimension.MEMORY, 0.2,
                        ResourceDimension.STORAGE, 0.1,
                        ResourceDimension.GPU, 8.0,
                        ResourceDimension.NETWORK, 0.5
                ))
                .reservedMonthlyPrices(Map.of(
                        ResourceDimension.CPU, 200.0,
                        ResourceDimension.MEMORY, 80.0,
                        ResourceDimension.GPU, 4000.0
                ))
                .reservedQuantities(Map.of(
                        ResourceDimension.CPU, 100.0,
                        ResourceDimension.GPU, 2.0
                ))
                .gpuPrices(Map.of(
                        "A100", 12.0,
                        "V100", 6.0,
                        "Ascend910", 8.0
                ))
                .currency("CNY")
                .reservedHoursPerMonth(730.0)
                .build();
    }

    /**
     * GPU 差异化定价配置：突出 GPU 多卡型号差异化。
     */
    private PricingConfig buildGpuDifferentiatedPricing() {
        return PricingConfig.builder()
                .name("gpu-differentiated")
                .unitPrices(Map.of(
                        ResourceDimension.CPU, 0.5,
                        ResourceDimension.MEMORY, 0.2,
                        ResourceDimension.STORAGE, 0.1,
                        ResourceDimension.GPU, 8.0,
                        ResourceDimension.NETWORK, 0.5
                ))
                .gpuPrices(Map.of(
                        "A100", 12.0,
                        "V100", 6.0,
                        "Ascend910", 8.0,
                        "T4", 3.0
                ))
                .tieredPrices(Map.of(
                        ResourceDimension.CPU, List.of(
                                TieredPricingTier.builder()
                                        .name("第一档 0-100核时")
                                        .lowerBound(0).upperBound(100.0)
                                        .unitPrice(0.5).cumulative(true).build(),
                                TieredPricingTier.builder()
                                        .name("第二档 100-500核时")
                                        .lowerBound(100).upperBound(500.0)
                                        .unitPrice(0.8).cumulative(true).build(),
                                TieredPricingTier.builder()
                                        .name("第三档 500+核时")
                                        .lowerBound(500).upperBound(null)
                                        .unitPrice(1.2).cumulative(true).build()
                        )
                ))
                .currency("CNY")
                .reservedHoursPerMonth(730.0)
                .build();
    }
}