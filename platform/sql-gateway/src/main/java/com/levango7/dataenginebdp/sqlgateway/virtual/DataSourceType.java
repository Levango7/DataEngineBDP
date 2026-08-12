package com.levango7.dataenginebdp.sqlgateway.virtual;

/**
 * 虚拟表外部数据源类型枚举。
 *
 * <p>定义五种受支持的外部数据源类型，每种类型对应一种
 * {@link com.levango7.dataenginebdp.sqlgateway.virtual.adapter.VirtualAdapter} 实现：</p>
 *
 * <ul>
 *   <li>{@link #MYSQL}：MySQL 数据库，通过 JDBC + HikariCP 连接池访问；</li>
 *   <li>{@link #ORACLE}：Oracle 数据库，通过 JDBC + HikariCP 连接池访问；</li>
 *   <li>{@link #JDBC}：通用 JDBC 数据源（如 PostgreSQL、SQL Server 等）；</li>
 *   <li>{@link #KAFKA}：Kafka topic，将消息映射为虚拟表行；</li>
 *   <li>{@link #REST}：REST API，将响应 JSON 映射为虚拟表数据。</li>
 * </ul>
 *
 * <p>枚举值与 JPA Entity 中以字符串形式持久化，便于跨数据库迁移与人工排查。</p>
 *
 * @author shuqing-bigdata
 */
public enum DataSourceType {

    /**
     * MySQL 数据源。
     */
    MYSQL,

    /**
     * Oracle 数据源。
     */
    ORACLE,

    /**
     * 通用 JDBC 数据源（PostgreSQL、SQL Server、DM 等）。
     */
    JDBC,

    /**
     * Kafka topic 数据源，消息按配置规则映射为虚拟表行。
     */
    KAFKA,

    /**
     * REST API 数据源，HTTP 响应 JSON 按配置规则映射为虚拟表数据。
     */
    REST;

    /**
     * 将字符串安全地解析为枚举值，忽略大小写。
     *
     * @param value 字符串值
     * @return 对应枚举值
     * @throws IllegalArgumentException 若字符串不匹配任何枚举值
     */
    public static DataSourceType fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("数据源类型不能为空");
        }
        try {
            return DataSourceType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的数据源类型: " + value
                    + "，支持: MYSQL, ORACLE, JDBC, KAFKA, REST");
        }
    }

    /**
     * 判断是否为 JDBC 类数据源（MySQL/Oracle/JDBC）。
     *
     * @return {@code true} 表示该类型可通过 JDBC 驱动访问
     */
    public boolean isJdbcLike() {
        return this == MYSQL || this == ORACLE || this == JDBC;
    }
}