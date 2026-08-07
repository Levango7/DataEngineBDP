package com.shuqing.bigdata.finops.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 阶梯定价档位。
 *
 * <p>表示阶梯计费中的一档：当累计用量落在 [{@link #lowerBound}, {@link #upperBound}) 区间时，
 * 该档位用量按 {@link #unitPrice} 计价。{@code upperBound} 为 null 表示上不封顶。</p>
 *
 * <p>阶梯计价支持"累计阶梯"（每档独立计价后求和）与"统一阶梯"（按累计用量命中档位统一单价），
 * 通过 {@link #cumulative} 标识。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TieredPricingTier {

    /** 档位名（如 "第一档 0-100核时"） */
    @NotBlank
    private String name;

    /** 档位下界（含） */
    @PositiveOrZero
    private double lowerBound;

    /** 档位上界（不含）；null 表示上不封顶 */
    private Double upperBound;

    /** 该档位单价（元/单位用量） */
    @PositiveOrZero
    private double unitPrice;

    /** 是否累计阶梯：true=各档独立计价求和，false=按累计用量命中档位统一单价 */
    private boolean cumulative;
}