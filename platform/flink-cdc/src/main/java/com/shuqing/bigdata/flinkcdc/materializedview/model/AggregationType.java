package com.shuqing.bigdata.flinkcdc.materializedview.model;

import java.util.Objects;

/**
 * 物化视图聚合类型枚举，对应 Doris/SQL 标准聚合函数。
 *
 * <p>用于在物化视图定义中声明每个指标列的预计算方式。每个聚合类型
 * 描述了其在增量刷新场景下的合并语义（associative + commutative），
 * 以支持 Flink CDC 流式更新时的状态合并。</p>
 *
 * <p>合并语义说明（用于增量刷新）：</p>
 * <ul>
 *   <li>{@link #COUNT} — 计数，合并时相加；DELETE 时减 1</li>
 *   <li>{@link #SUM} — 求和，合并时相加；DELETE 时减去该行值</li>
 *   <li>{@link #AVG} — 平均值，需同时维护 sum 与 count 两个状态量</li>
 *   <li>{@link #MIN} — 最小值，合并时取较小；DELETE 需重算（无逆操作）</li>
 *   <li>{@link #MAX} — 最大值，合并时取较大；DELETE 需重算（无逆操作）</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public enum AggregationType {

    /** 计数聚合（COUNT(*)），合并语义：相加。 */
    COUNT("COUNT", "计数", true, false),

    /** 求和聚合（SUM(col)），合并语义：相加。 */
    SUM("SUM", "求和", true, false),

    /** 平均值聚合（AVG(col)），合并语义：需维护 (sum, count) 二元组。 */
    AVG("AVG", "平均值", true, true),

    /** 最小值聚合（MIN(col)），合并语义：取较小；DELETE 需全量重算。 */
    MIN("MIN", "最小值", false, false),

    /** 最大值聚合（MAX(col)），合并语义：取较大；DELETE 需全量重算。 */
    MAX("MAX", "最大值", false, false);

    /** SQL 函数名（大写）。 */
    private final String sqlFunction;

    /** 中文描述。 */
    private final String description;

    /** 是否支持增量合并（INSERT/UPDATE 可增量更新而无需全量重算）。 */
    private final boolean incrementalMergeable;

    /** 是否需要辅助状态（AVG 需要同时维护 sum 和 count）。 */
    private final boolean requiresAuxState;

    AggregationType(String sqlFunction, String description,
                    boolean incrementalMergeable, boolean requiresAuxState) {
        this.sqlFunction = sqlFunction;
        this.description = description;
        this.incrementalMergeable = incrementalMergeable;
        this.requiresAuxState = requiresAuxState;
    }

    /**
     * 获取 SQL 函数名（大写，如 {@code "COUNT"}）。
     *
     * @return SQL 函数名
     */
    public String sqlFunction() {
        return sqlFunction;
    }

    /**
     * 获取中文描述。
     *
     * @return 中文描述
     */
    public String description() {
        return description;
    }

    /**
     * 判断该聚合类型是否支持增量合并。
     *
     * <p>COUNT/SUM/AVG 支持增量合并（INSERT 时直接更新状态）；
     * MIN/MAX 不支持增量合并（DELETE 后需全量重算）。</p>
     *
     * @return 若支持增量合并返回 true
     */
    public boolean isIncrementalMergeable() {
        return incrementalMergeable;
    }

    /**
     * 判断该聚合类型是否需要辅助状态量。
     *
     * <p>仅 AVG 需要辅助状态（sum + count），其他类型单状态量即可。</p>
     *
     * @return 若需要辅助状态返回 true
     */
    public boolean requiresAuxState() {
        return requiresAuxState;
    }

    /**
     * 根据 SQL 函数名解析为枚举值（大小写不敏感）。
     *
     * @param name SQL 函数名（如 {@code "count"}, {@code "SUM"}）
     * @return 对应枚举值
     * @throws NullPointerException     若 name 为 null
     * @throws IllegalArgumentException 若函数名不被识别
     */
    public static AggregationType fromName(String name) {
        Objects.requireNonNull(name, "聚合类型名称不能为 null");
        String normalized = name.trim().toUpperCase();
        for (AggregationType type : values()) {
            if (type.sqlFunction.equals(normalized) || type.name().equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的聚合类型: " + name);
    }

    /**
     * 生成对该列的 SQL 聚合表达式。
     *
     * <p>对于 COUNT，使用 {@code COUNT(*)}（忽略列名，统计行数）；
     * 其他类型使用 {@code FUNC(col)}。</p>
     *
     * @param column 列名（COUNT 时可为 null）
     * @return SQL 聚合表达式，如 {@code "SUM(amount)"}
     */
    public String sqlExpression(String column) {
        if (this == COUNT) {
            return "COUNT(*)";
        }
        Objects.requireNonNull(column, "列名不能为 null（COUNT 除外）");
        return sqlFunction + "(" + column + ")";
    }
}