package com.levango7.dataenginebdp.finops.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 定价配置。
 *
 * <p>支持动态配置单价（通过 API 或配置文件）。按维度配置单价，
 * GPU 维度按型号差异化定价（{@link #gpuPrices}）。</p>
 *
 * <p>三种计费方式共用本配置：</p>
 * <ul>
 *   <li>按量：直接使用 {@link #unitPrices} 的单价</li>
 *   <li>包年：使用 {@link #reservedMonthlyPrices} 的月价分摊到小时</li>
 *   <li>阶梯：使用 {@link #tieredPrices} 的阶梯档位</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingConfig {

    /** 配置名（便于多套定价方案管理） */
    @NotBlank
    private String name;

    /** 按量计费单价（元/单位用量）：dimension → 单价 */
    @NotNull
    private java.util.Map<ResourceDimension, Double> unitPrices;

    /** 包年预留实例月价（元/月）：dimension → 月价 */
    private java.util.Map<ResourceDimension, Double> reservedMonthlyPrices;

    /** 预留实例已购买数量（用于分摊）：dimension → 数量 */
    private java.util.Map<ResourceDimension, Double> reservedQuantities;

    /** 阶梯定价档位：dimension → 阶梯列表 */
    private java.util.Map<ResourceDimension, List<TieredPricingTier>> tieredPrices;

    /** GPU 按型号单价（元/卡时）：gpuModel → 单价，如 A100=12.0, V100=6.0, Ascend910=8.0 */
    private java.util.Map<String, Double> gpuPrices;

    /** 货币单位（默认 CNY） */
    @NotBlank
    private String currency;

    /** 包年分摊的小时数（默认 730 小时/月） */
    @Positive
    private double reservedHoursPerMonth;
}