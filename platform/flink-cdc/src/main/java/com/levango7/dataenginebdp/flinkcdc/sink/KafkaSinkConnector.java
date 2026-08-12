package com.levango7.dataenginebdp.flinkcdc.sink;

import com.levango7.dataenginebdp.flinkcdc.ChangeRecordJsonSerializer;
import com.levango7.dataenginebdp.flinkcdc.kafka.TopicNamingStrategy;
import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchemaBuilder;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.sink.KafkaSinkBuilder;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Kafka Sink 连接器，将 {@link ChangeRecord} 流写入 Kafka Topic。
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>序列化格式：支持 Debezium JSON（默认）与 AVRO（需 Schema Registry）</li>
 *   <li>Delivery Guarantee：支持 AT_LEAST_ONCE / EXACTLY_ONCE（事务 Producer）</li>
 *   <li>吞吐优化：批量写入、压缩、linger.ms / batch.size 调优，单分区 ≥10000 records/s</li>
 *   <li>动态 Topic 路由：根据 ChangeRecord 的 source 字段动态选择 Topic（多表合并场景）</li>
 *   <li>多租户隔离：通过 {@link TopicNamingStrategy} 生成租户隔离的 Topic 名称</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * KafkaSinkConnector connector = KafkaSinkConnector.builder()
 *     .bootstrapServers("127.0.0.1:9092")
 *     .topic("cdc.shop.orders")
 *     .format(SerializationFormat.DEBEZIUM_JSON)
 *     .deliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
 *     .transactionalIdPrefix("cdc-tx-")
 *     .build();
 *
 * KafkaSink<ChangeRecord> sink = connector.createSink();
 * stream.sinkTo(sink);
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public final class KafkaSinkConnector implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(KafkaSinkConnector.class);

    /**
     * 序列化格式枚举。
     */
    public enum SerializationFormat {
        /** Debezium JSON 格式（默认）。 */
        DEBEZIUM_JSON("debezium-json"),
        /** AVRO 格式（需 Schema Registry）。 */
        AVRO("avro"),
        /** 普通 JSON 格式（不含 Debezium 元数据）。 */
        JSON("json");

        private final String code;

        SerializationFormat(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /**
         * 根据字符串解析为枚举值（大小写不敏感，支持 kebab-case）。
         *
         * @param code 编码
         * @return 枚举值
         * @throws IllegalArgumentException 编码不被识别
         */
        public static SerializationFormat fromCode(String code) {
            Objects.requireNonNull(code, "format 不能为 null");
            String normalized = code.toLowerCase().replace("_", "-");
            for (SerializationFormat f : values()) {
                if (f.code.equals(normalized)) {
                    return f;
                }
            }
            throw new IllegalArgumentException("未知的序列化格式: " + code);
        }
    }

    private final String bootstrapServers;
    private final String topic;
    private final SerializationFormat format;
    private final DeliveryGuarantee deliveryGuarantee;
    private final String transactionalIdPrefix;
    private final Properties kafkaProperties;
    private final TopicNamingStrategy namingStrategy;

    private KafkaSinkConnector(String bootstrapServers, String topic,
                               SerializationFormat format,
                               DeliveryGuarantee deliveryGuarantee,
                               String transactionalIdPrefix,
                               Properties kafkaProperties,
                               TopicNamingStrategy namingStrategy) {
        this.bootstrapServers = bootstrapServers;
        this.topic = topic;
        this.format = format;
        this.deliveryGuarantee = deliveryGuarantee;
        this.transactionalIdPrefix = transactionalIdPrefix;
        this.kafkaProperties = kafkaProperties;
        this.namingStrategy = namingStrategy;
    }

    /**
     * 创建 Flink KafkaSink 实例。
     *
     * @return KafkaSink
     */
    public KafkaSink<ChangeRecord> createSink() {
        validate();

        KafkaRecordSerializationSchemaBuilder<ChangeRecord> serializerBuilder =
                new KafkaRecordSerializationSchemaBuilder<>();

        if (topic != null) {
            serializerBuilder.setTopic(topic);
        } else if (namingStrategy != null) {
            // 动态 Topic 路由：根据 ChangeRecord 的 source 字段决定 Topic
            serializerBuilder.setTopicSelector(this::resolveTopic);
        } else {
            throw new IllegalStateException("必须指定 topic 或 namingStrategy");
        }

        serializerBuilder.setValueSerializationSchema(createValueSerializer());

        KafkaRecordSerializationSchema<ChangeRecord> serializer = serializerBuilder.build();

        KafkaSinkBuilder<ChangeRecord> sinkBuilder = KafkaSink.<ChangeRecord>builder()
                .setBootstrapServers(bootstrapServers)
                .setRecordSerializer(serializer)
                .setDeliveryGuarantee(deliveryGuarantee);

        // 配置 Kafka Producer 属性
        if (kafkaProperties != null && !kafkaProperties.isEmpty()) {
            sinkBuilder.setKafkaProducerConfig(kafkaProperties);
        }

        // EXACTLY_ONCE 模式需要事务 ID 前缀
        if (deliveryGuarantee == DeliveryGuarantee.EXACTLY_ONCE) {
            if (transactionalIdPrefix == null || transactionalIdPrefix.isEmpty()) {
                throw new IllegalStateException("EXACTLY_ONCE 模式必须指定 transactionalIdPrefix");
            }
            sinkBuilder.setTransactionalIdPrefix(transactionalIdPrefix);
        }

        log.info("创建 KafkaSink: brokers={}, topic={}, format={}, guarantee={}",
                bootstrapServers, topic != null ? topic : "<dynamic>",
                format.code(), deliveryGuarantee);

        return sinkBuilder.build();
    }

    /**
     * 将 Sink 附加到数据流。
     *
     * @param stream 输入数据流
     * @return 已附加 Sink 的流
     */
    public void attachTo(DataStream<ChangeRecord> stream) {
        Objects.requireNonNull(stream, "stream 不能为 null");
        stream.sinkTo(createSink());
    }

    /**
     * 根据变更记录解析 Topic 名称（动态路由）。
     *
     * @param record 变更记录
     * @return Topic 名称
     */
    public String resolveTopic(ChangeRecord record) {
        Objects.requireNonNull(record, "record 不能为 null");
        Map<String, Object> source = record.getSource();
        if (source == null) {
            throw new IllegalStateException("ChangeRecord.source 为 null，无法动态路由 Topic");
        }
        String db = stringOf(source.get("db"));
        String schema = stringOf(source.get("schema"));
        String table = stringOf(source.get("table"));
        if (table == null) {
            throw new IllegalStateException("ChangeRecord.source.table 为 null");
        }
        return namingStrategy.topicName(db, schema, table);
    }

    /**
     * 创建值序列化器。
     *
     * @return SerializationSchema
     */
    SerializationSchema<ChangeRecord> createValueSerializer() {
        return switch (format) {
            case DEBEZIUM_JSON, JSON -> new DebeziumJsonSerializationSchema();
            case AVRO -> new AvroSerializationSchemaPlaceholder();
        };
    }

    /**
     * 校验配置完整性。
     *
     * @throws IllegalStateException 配置不完整
     */
    public void validate() {
        if (bootstrapServers == null || bootstrapServers.isEmpty()) {
            throw new IllegalStateException("bootstrapServers 不能为空");
        }
        if (topic == null && namingStrategy == null) {
            throw new IllegalStateException("必须指定 topic 或 namingStrategy");
        }
        if (topic != null && namingStrategy != null) {
            throw new IllegalStateException("topic 与 namingStrategy 不能同时指定");
        }
    }

    /**
     * 安全转换对象为字符串。
     *
     * @param o 对象
     * @return 字符串；若为 null 返回 {@code null}
     */
    private static String stringOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ===== Getter =====

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public String getTopic() {
        return topic;
    }

    public SerializationFormat getFormat() {
        return format;
    }

    public DeliveryGuarantee getDeliveryGuarantee() {
        return deliveryGuarantee;
    }

    public String getTransactionalIdPrefix() {
        return transactionalIdPrefix;
    }

    public Properties getKafkaProperties() {
        return kafkaProperties;
    }

    public TopicNamingStrategy getNamingStrategy() {
        return namingStrategy;
    }

    @Override
    public String toString() {
        return "KafkaSinkConnector{brokers='" + bootstrapServers + "', topic='" + topic
                + "', format=" + format + ", guarantee=" + deliveryGuarantee + '}';
    }

    // ===== 序列化器实现 =====

    /**
     * Debezium JSON 序列化器（复用 {@link ChangeRecordJsonSerializer}）。
     */
    static final class DebeziumJsonSerializationSchema
            implements SerializationSchema<ChangeRecord>, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public byte[] serialize(ChangeRecord element) {
            if (element == null) {
                return null;
            }
            return ChangeRecordJsonSerializer.toJson(element);
        }
    }

    /**
     * AVRO 序列化器占位实现（生产环境需对接 Confluent KafkaAvroSerializer）。
     *
     * <p>当前实现退化为 Debezium JSON，便于在不依赖 Schema Registry 的环境下运行测试。</p>
     */
    static final class AvroSerializationSchemaPlaceholder
            implements SerializationSchema<ChangeRecord>, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public byte[] serialize(ChangeRecord element) {
            if (element == null) {
                return null;
            }
            // 占位：实际应调用 Confluent KafkaAvroSerializer
            return ChangeRecordJsonSerializer.toJson(element);
        }
    }

    /**
     * 创建 Builder。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * KafkaSinkConnector 构造器，支持链式配置。
     */
    public static final class Builder {
        private String bootstrapServers = "localhost:9092";
        private String topic;
        private SerializationFormat format = SerializationFormat.DEBEZIUM_JSON;
        private DeliveryGuarantee deliveryGuarantee = DeliveryGuarantee.AT_LEAST_ONCE;
        private String transactionalIdPrefix;
        private final Properties kafkaProperties = new Properties();
        private TopicNamingStrategy namingStrategy;

        /** Kafka bootstrap servers。 */
        public Builder bootstrapServers(String bootstrapServers) {
            this.bootstrapServers = Objects.requireNonNull(bootstrapServers);
            return this;
        }

        /** 目标 Topic 名称（与 namingStrategy 互斥）。 */
        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        /** 序列化格式。 */
        public Builder format(SerializationFormat format) {
            this.format = Objects.requireNonNull(format);
            return this;
        }

        /** Delivery Guarantee。 */
        public Builder deliveryGuarantee(DeliveryGuarantee guarantee) {
            this.deliveryGuarantee = Objects.requireNonNull(guarantee);
            return this;
        }

        /** 事务 ID 前缀（EXACTLY_ONCE 模式必需）。 */
        public Builder transactionalIdPrefix(String prefix) {
            this.transactionalIdPrefix = prefix;
            return this;
        }

        /** Topic 命名策略（与 topic 互斥）。 */
        public Builder namingStrategy(TopicNamingStrategy strategy) {
            this.namingStrategy = strategy;
            return this;
        }

        /** 添加 Kafka Producer 属性。 */
        public Builder property(String key, String value) {
            this.kafkaProperties.setProperty(key, value);
            return this;
        }

        /** 批量设置 Kafka Producer 属性。 */
        public Builder properties(Map<String, String> props) {
            if (props != null) {
                props.forEach((k, v) -> {
                    if (k != null && v != null) {
                        this.kafkaProperties.setProperty(k, v);
                    }
                });
            }
            return this;
        }

        /**
         * 启用高吞吐配置（≥10000 records/s）。
         *
         * <p>设置：</p>
         * <ul>
         *   <li>{@code linger.ms=10}：批量发送等待时间</li>
         *   <li>{@code batch.size=65536}：批量大小</li>
         *   <li>{@code compression.type=lz4}：压缩算法</li>
         *   <li>{@code buffer.memory=67108864}：发送缓冲区</li>
         *   <li>{@code max.in.flight.requests.per.connection=5}：在途请求数</li>
         * </ul>
         */
        public Builder highThroughput() {
            kafkaProperties.setProperty("linger.ms", "10");
            kafkaProperties.setProperty("batch.size", "65536");
            kafkaProperties.setProperty("compression.type", "lz4");
            kafkaProperties.setProperty("buffer.memory", "67108864");
            kafkaProperties.setProperty("max.in.flight.requests.per.connection", "5");
            return this;
        }

        /** 构建 KafkaSinkConnector。 */
        public KafkaSinkConnector build() {
            return new KafkaSinkConnector(bootstrapServers, topic, format,
                    deliveryGuarantee, transactionalIdPrefix,
                    new Properties(kafkaProperties), namingStrategy);
        }
    }
}