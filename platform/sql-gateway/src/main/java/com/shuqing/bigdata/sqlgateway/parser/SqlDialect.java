package com.shuqing.bigdata.sqlgateway.parser;

/**
 * SQL 方言枚举。
 *
 * <p>支持 ANSI 标准 SQL 以及 Hive、Doris、Trino 三种大数据引擎方言。</p>
 *
 * @author shuqing-bigdata
 */
public enum SqlDialect {

    /**
     * ANSI 标准 SQL（默认）。
     */
    ANSI,

    /**
     * Apache Hive 方言：支持 {@code STORED AS ORC}、分区语法、{@code LIMIT} 等扩展。
     */
    HIVE,

    /**
     * Apache Doris 方言：支持 {@code DISTRIBUTED BY HASH}、{@code PROPERTIES} 等扩展。
     */
    DORIS,

    /**
     * Trino（原 PrestoSQL）方言：支持 {@code WITH} CTE、{@code CROSS JOIN}、{@code ARRAY} 函数等。
     */
    TRINO;

    /**
     * 大小写无关地解析方言名称，未识别时返回 {@link #ANSI}。
     *
     * @param name 方言名称
     * @return 解析得到的方言枚举
     */
    public static SqlDialect fromString(String name) {
        if (name == null || name.isBlank()) {
            return ANSI;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ANSI;
        }
    }
}