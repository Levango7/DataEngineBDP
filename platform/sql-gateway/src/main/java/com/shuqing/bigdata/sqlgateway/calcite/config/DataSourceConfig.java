package com.shuqing.bigdata.sqlgateway.calcite.config;

import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 数据源配置——描述一个可接入 Calcite 联邦优化器的数据源实例。
 *
 * <p>每个数据源由唯一 {@code name} 标识，具有 {@link Type 类型}（Iceberg/Doris/Trino/IoTDB/ES）、
 * 连接信息（JDBC URL 或 REST endpoint）、以及对应的 SQL 方言。{@link OptimizerConfig}
 * 持有一组 {@code DataSourceConfig} 用于在 {@code CalciteOptimizer} 初始化时注册 Schema。</p>
 *
 * <p>典型 YAML 配置：</p>
 * <pre>
 * sql-gateway:
 *   optimizer:
 *     data-sources:
 *       - name: doris_olap
 *         type: DORIS
 *         jdbc-url: "jdbc:mysql://doris-fe:9030"
 *         dialect: DORIS
 *         properties:
 *           user: root
 *           password: "***"
 * </pre>
 *
 * @author shuqing-bigdata
 */
public class DataSourceConfig {

    /**
     * 支持的数据源类型枚举。
     */
    public enum Type {
        /** Apache Iceberg 数据湖表格式 */
        ICEBERG,
        /** Apache Doris MPP OLAP 引擎 */
        DORIS,
        /** Trino（原 Presto）联邦查询引擎 */
        TRINO,
        /** Apache IoTDB 时序数据库 */
        IOTDB,
        /** Elasticsearch 检索引擎 */
        ELASTICSEARCH;

        /**
         * 大小写无关解析类型名称，未识别时抛出 {@link IllegalArgumentException}。
         *
         * @param name 类型名称
         * @return 解析得到的类型枚举
         */
        public static Type fromString(String name) {
            Objects.requireNonNull(name, "type name");
            return valueOf(name.trim().toUpperCase());
        }
    }

    /** 数据源唯一标识名（如 doris_olap、trino_hive） */
    private String name;

    /** 数据源类型 */
    private Type type;

    /** JDBC 连接 URL（JDBC 类数据源使用） */
    private String jdbcUrl;

    /** REST endpoint URL（非 JDBC 数据源如 ES、IoTDB REST API 使用） */
    private String endpoint;

    /** SQL 方言，用于 Calcite SqlDialect 适配 */
    private SqlDialect dialect = SqlDialect.ANSI;

    /** 是否启用谓词/投影下推到该数据源，默认 true */
    private boolean pushDownEnabled = true;

    /** 是否启用该数据源的 Cost 估算参与全局优化，默认 true */
    private boolean costEstimationEnabled = true;

    /** 额外连接属性（user/password/ssl 等） */
    private Map<String, String> properties = new LinkedHashMap<>();

    public DataSourceConfig() {
    }

    public DataSourceConfig(String name, Type type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public DataSourceConfig setName(String name) {
        this.name = name;
        return this;
    }

    public Type getType() {
        return type;
    }

    public DataSourceConfig setType(Type type) {
        this.type = type;
        return this;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public DataSourceConfig setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        return this;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public DataSourceConfig setEndpoint(String endpoint) {
        this.endpoint = endpoint;
        return this;
    }

    public SqlDialect getDialect() {
        return dialect;
    }

    public DataSourceConfig setDialect(SqlDialect dialect) {
        this.dialect = dialect;
        return this;
    }

    public boolean isPushDownEnabled() {
        return pushDownEnabled;
    }

    public DataSourceConfig setPushDownEnabled(boolean pushDownEnabled) {
        this.pushDownEnabled = pushDownEnabled;
        return this;
    }

    public boolean isCostEstimationEnabled() {
        return costEstimationEnabled;
    }

    public DataSourceConfig setCostEstimationEnabled(boolean costEstimationEnabled) {
        this.costEstimationEnabled = costEstimationEnabled;
        return this;
    }

    public Map<String, String> getProperties() {
        return properties == null ? Collections.emptyMap() : properties;
    }

    public DataSourceConfig setProperties(Map<String, String> properties) {
        this.properties = properties == null ? new LinkedHashMap<>() : properties;
        return this;
    }

    /**
     * 添加一个连接属性（链式）。
     *
     * @param key   属性键
     * @param value 属性值
     * @return 当前配置
     */
    public DataSourceConfig addProperty(String key, String value) {
        if (this.properties == null) {
            this.properties = new LinkedHashMap<>();
        }
        this.properties.put(key, value);
        return this;
    }

    /**
     * 校验配置完整性。
     *
     * @return {@code true} 表示配置合法（name 与 type 非空，且 jdbcUrl 或 endpoint 至少一个非空）
     */
    public boolean isValid() {
        if (name == null || name.isBlank() || type == null) {
            return false;
        }
        return (jdbcUrl != null && !jdbcUrl.isBlank())
                || (endpoint != null && !endpoint.isBlank());
    }

    @Override
    public String toString() {
        return "DataSourceConfig{name='" + name + "', type=" + type
                + ", jdbcUrl='" + jdbcUrl + "', endpoint='" + endpoint + '\''
                + ", dialect=" + dialect
                + ", pushDown=" + pushDownEnabled
                + ", costEstimation=" + costEstimationEnabled
                + ", properties=" + properties + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DataSourceConfig that)) {
            return false;
        }
        return Objects.equals(name, that.name) && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }
}