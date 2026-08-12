package com.levango7.dataenginebdp.sqlgateway.virtual.materialize;

/**
 * 物化策略枚举。
 *
 * <p>定义虚拟表数据的物化方式，影响数据存储与刷新行为：</p>
 *
 * <ul>
 *   <li>{@link #NONE}：不物化，每次查询都直接访问外部源（默认）；</li>
 *   <li>{@link #FULL}：全量物化，定时将外部源全部数据刷新到本地物化表；</li>
 *   <li>{@link #INCREMENTAL}：增量物化，仅刷新上次刷新后变更的数据；</li>
 *   <li>{@link #MANUAL}：手动刷新，由用户通过 API 触发刷新。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public enum MaterializationStrategy {

    /**
     * 不物化：每次查询直接访问外部源。
     */
    NONE,

    /**
     * 全量物化：定时将外部源全部数据刷新到本地物化表。
     */
    FULL,

    /**
     * 增量物化：仅刷新上次刷新后变更的数据。
     */
    INCREMENTAL,

    /**
     * 手动刷新：由用户通过 API 触发刷新。
     */
    MANUAL;

    /**
     * 将字符串安全地解析为枚举值，忽略大小写。
     *
     * @param value 字符串值
     * @return 对应枚举值
     * @throws IllegalArgumentException 若字符串不匹配任何枚举值
     */
    public static MaterializationStrategy fromString(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return MaterializationStrategy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的物化策略: " + value
                    + "，支持: NONE, FULL, INCREMENTAL, MANUAL");
        }
    }

    /**
     * 判断是否需要定时刷新。
     *
     * @return {@code true} 表示 FULL 或 INCREMENTAL 需要定时刷新
     */
    public boolean isScheduled() {
        return this == FULL || this == INCREMENTAL;
    }
}