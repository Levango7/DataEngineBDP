package com.levango7.dataenginebdp.sqlgateway.calcite.rule;

/**
 * 谓词类型枚举——用于谓词下推规则中对谓词进行分类。
 *
 * <p>本枚举对应 SQL 查询中常见的四类可下推谓词，以及不支持下推的复杂谓词：</p>
 * <ul>
 *   <li>{@link #EQUALITY}：等值谓词，如 {@code id = 100}</li>
 *   <li>{@link #RANGE}：范围谓词，如 {@code age > 18}、{@code age < 65}、
 *       {@code age >= 18}、{@code age <= 65}</li>
 *   <li>{@link #IN}：IN 谓词，如 {@code status IN ('A', 'B', 'C')}</li>
 *   <li>{@link #LIKE}：LIKE 谓词，如 {@code name LIKE '张%'}</li>
 *   <li>{@link #IS_NULL}：NULL 检查谓词，如 {@code col IS NULL}、{@code col IS NOT NULL}</li>
 *   <li>{@link #UNSUPPORTED}：不支持下推的谓词，如 UDF 调用、OR 连接的复合条件、
 *       子查询、复杂表达式等</li>
 * </ul>
 *
 * <p>谓词分类是谓词下推的第一步：只有被分类为前五类的谓词才可能下推到数据源，
 * {@link #UNSUPPORTED} 类谓词始终保留在联邦层 Filter 节点执行。</p>
 *
 * @author shuqing-bigdata
 */
public enum PredicateType {
    /** 等值谓词：= */
    EQUALITY,
    /** 范围谓词：<, >, <=, >=, BETWEEN */
    RANGE,
    /** IN 谓词：IN (...)、NOT IN (...) */
    IN,
    /** LIKE 谓词：LIKE、NOT LIKE */
    LIKE,
    /** NULL 检查谓词：IS NULL、IS NOT NULL */
    IS_NULL,
    /** 不支持下推的谓词：UDF、OR 复合条件、子查询、复杂表达式等 */
    UNSUPPORTED;

    /**
     * 判断该谓词类型是否可下推。
     *
     * <p>{@link #UNSUPPORTED} 始终返回 {@code false}，其余类型返回 {@code true}。
     * 实际是否下推还需结合数据源适配器的支持情况（见
     * {@link PredicatePushDownRule#isPushable(PredicateType, com.levango7.dataenginebdp.sqlgateway.calcite.adapter.BaseAdapter)}）。</p>
     *
     * @return {@code true} 表示该类型可能下推
     */
    public boolean isPushable() {
        return this != UNSUPPORTED;
    }

    /**
     * 获取谓词类型的简短描述（用于统计与日志）。
     *
     * @return 描述字符串
     */
    public String description() {
        return switch (this) {
            case EQUALITY -> "等值(=)";
            case RANGE -> "范围(<,>,<=,>=)";
            case IN -> "IN";
            case LIKE -> "LIKE";
            case IS_NULL -> "NULL检查";
            case UNSUPPORTED -> "不支持";
        };
    }
}