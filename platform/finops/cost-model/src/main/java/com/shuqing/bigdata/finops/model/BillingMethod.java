package com.shuqing.bigdata.finops.model;

/**
 * 计费方式枚举。
 *
 * <p>支持三种计费方式：</p>
 * <ul>
 *   <li>{@link #ON_DEMAND} 按量计费：实时用量 × 单价</li>
 *   <li>{@link #RESERVED} 包年计费：预留实例分摊</li>
 *   <li>{@link #TIERED} 阶梯计费：累计用量阶梯计价</li>
 * </ul>
 */
public enum BillingMethod {

    /** 按量计费：实时用量 × 单价 */
    ON_DEMAND,

    /** 包年计费：预留实例分摊 */
    RESERVED,

    /** 阶梯计费：累计用量阶梯计价 */
    TIERED
}