package com.shuqing.bigdata.flinkcdc.sink;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * CDC 数据目标（Sink）配置，描述变更数据写入何处以及如何写入。
 *
 * <p>支持 Kafka / Iceberg / Doris 三种目标类型，含连接信息与写入模式。
 * 任意额外属性可通过 {@link #properties} 传递，避免频繁扩展字段。</p>
 *
 * @author shuqing-bigdata
 */
public class SinkConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标类型枚举。
     */
    public enum SinkType {
        /** 写入 Kafka Topic（Debezium JSON / Canal JSON 格式）。 */
        KAFKA,
        /** 写入 Apache Iceberg 数据湖表。 */
        ICEBERG,
        /** 写入 Apache Doris OLAP 表（Stream Load）。 */
        DORIS
    }

    /**
     * 写入模式枚举。
     */
    public enum WriteMode {
        /** 仅追加（适合 Kafka Topic）。 */
        APPEND_ONLY("append-only"),
        /** 支持 INSERT/UPDATE/DELETE 的 upsert 模式（需主键）。 */
        UPSERT("upsert"),
        /** 覆盖整表（适合批式全量同步）。 */
        OVERWRITE("overwrite");

        private final String code;

        WriteMode(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /**
         * 根据字符串编码解析为枚举值（大小写不敏感）。
         *
         * @param code 写入模式编码
         * @return 对应枚举值
         * @throws IllegalArgumentException 若编码不被识别
         */
        public static WriteMode fromCode(String code) {
            Objects.requireNonNull(code, "write mode 不能为 null");
            for (WriteMode mode : values()) {
                if (mode.code.equalsIgnoreCase(code) || mode.name().equalsIgnoreCase(code)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("未知的 write mode: " + code);
        }
    }

    /** 目标名称（多 Sink 场景下唯一标识）。 */
    private String name;

    /** 目标类型。 */
    private SinkType type = SinkType.KAFKA;

    /** 目标主机（Kafka brokers / Iceberg catalog / Doris FE）。 */
    private String host = "localhost";

    /** 端口。 */
    private int port = 9092;

    /** 目标 Topic / 表名。 */
    private String topic;

    /** 用户名。 */
    private String username;

    /** 密码。 */
    private String password;

    /** 写入模式。 */
    private WriteMode writeMode = WriteMode.UPSERT;

    /** 主键列（upsert 模式必需，逗号分隔）。 */
    private String primaryKey;

    /** 输出格式（debezium-json / canal-json / json / parquet）。 */
    private String format = "debezium-json";

    /** 并行度。 */
    private int parallelism = 1;

    /** 额外属性（如 Kafka 的 acks、Iceberg 的 catalog 配置等）。 */
    private Map<String, String> properties = new HashMap<>();

    /** 默认构造器，供 YAML 反序列化。 */
    public SinkConfig() {
    }

    /**
     * 获取属性值。
     *
     * @param key 属性键
     * @return 属性值；不存在返回 {@code null}
     */
    public String property(String key) {
        return properties.get(key);
    }

    /**
     * 设置属性值。
     *
     * @param key   属性键
     * @param value 属性值
     */
    public void property(String key, String value) {
        properties.put(key, value);
    }

    // ===== getter / setter =====

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SinkType getType() {
        return type;
    }

    public void setType(SinkType type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public WriteMode getWriteMode() {
        return writeMode;
    }

    public void setWriteMode(WriteMode writeMode) {
        this.writeMode = writeMode;
    }

    public String getPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(String primaryKey) {
        this.primaryKey = primaryKey;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties == null ? new HashMap<>() : properties;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SinkConfig that)) {
            return false;
        }
        return port == that.port
                && parallelism == that.parallelism
                && Objects.equals(name, that.name)
                && type == that.type
                && Objects.equals(host, that.host)
                && Objects.equals(topic, that.topic)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password)
                && writeMode == that.writeMode
                && Objects.equals(primaryKey, that.primaryKey)
                && Objects.equals(format, that.format)
                && Objects.equals(properties, that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, host, port, topic, username, password,
                writeMode, primaryKey, format, parallelism, properties);
    }

    @Override
    public String toString() {
        return "SinkConfig{name='" + name + "', type=" + type
                + ", host='" + host + "', port=" + port
                + ", topic='" + topic + "', writeMode=" + writeMode
                + ", format='" + format + "', parallelism=" + parallelism + '}';
    }
}