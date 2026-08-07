package com.shuqing.bigdata.flinkcdc.sink;

import com.shuqing.bigdata.flinkcdc.kafka.TopicNamingStrategy;
import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link KafkaSinkConnector} 单元测试。
 *
 * @author shuqing-bigdata
 */
class KafkaSinkConnectorTest {

    @Nested
    @DisplayName("SerializationFormat 枚举")
    class FormatTest {

        @Test
        @DisplayName("fromCode — 正确解析各格式")
        void fromCode_shouldParseAllFormats() {
            assertThat(KafkaSinkConnector.SerializationFormat.fromCode("debezium-json"))
                    .isEqualTo(KafkaSinkConnector.SerializationFormat.DEBEZIUM_JSON);
            assertThat(KafkaSinkConnector.SerializationFormat.fromCode("avro"))
                    .isEqualTo(KafkaSinkConnector.SerializationFormat.AVRO);
            assertThat(KafkaSinkConnector.SerializationFormat.fromCode("json"))
                    .isEqualTo(KafkaSinkConnector.SerializationFormat.JSON);
        }

        @Test
        @DisplayName("fromCode — 大小写不敏感")
        void fromCode_shouldBeCaseInsensitive() {
            assertThat(KafkaSinkConnector.SerializationFormat.fromCode("AVRO"))
                    .isEqualTo(KafkaSinkConnector.SerializationFormat.AVRO);
            assertThat(KafkaSinkConnector.SerializationFormat.fromCode("Debezium-Json"))
                    .isEqualTo(KafkaSinkConnector.SerializationFormat.DEBEZIUM_JSON);
        }

        @Test
        @DisplayName("fromCode — 下划线转横线")
        void fromCode_shouldNormalizeUnderscore() {
            assertThat(KafkaSinkConnector.SerializationFormat.fromCode("debezium_json"))
                    .isEqualTo(KafkaSinkConnector.SerializationFormat.DEBEZIUM_JSON);
        }

        @Test
        @DisplayName("fromCode — 未知格式抛出异常")
        void fromCode_unknown_shouldThrow() {
            assertThatThrownBy(() -> KafkaSinkConnector.SerializationFormat.fromCode("xml"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("fromCode — null 抛出 NPE")
        void fromCode_null_shouldThrowNpe() {
            assertThatThrownBy(() -> KafkaSinkConnector.SerializationFormat.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("code — 返回正确编码")
        void code_shouldReturnCorrectCodes() {
            assertThat(KafkaSinkConnector.SerializationFormat.DEBEZIUM_JSON.code())
                    .isEqualTo("debezium-json");
            assertThat(KafkaSinkConnector.SerializationFormat.AVRO.code()).isEqualTo("avro");
            assertThat(KafkaSinkConnector.SerializationFormat.JSON.code()).isEqualTo("json");
        }
    }

    @Nested
    @DisplayName("Builder 配置")
    class BuilderTest {

        @Test
        @DisplayName("默认配置 — 正确")
        void defaults() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .topic("test-topic")
                    .build();

            assertThat(connector.getBootstrapServers()).isEqualTo("localhost:9092");
            assertThat(connector.getTopic()).isEqualTo("test-topic");
            assertThat(connector.getFormat()).isEqualTo(KafkaSinkConnector.SerializationFormat.DEBEZIUM_JSON);
            assertThat(connector.getDeliveryGuarantee()).isEqualTo(DeliveryGuarantee.AT_LEAST_ONCE);
        }

        @Test
        @DisplayName("自定义配置 — 正确生效")
        void customConfig() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("broker1:9092,broker2:9092")
                    .topic("shop.orders")
                    .format(KafkaSinkConnector.SerializationFormat.AVRO)
                    .deliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
                    .transactionalIdPrefix("cdc-tx-")
                    .build();

            assertThat(connector.getBootstrapServers()).isEqualTo("broker1:9092,broker2:9092");
            assertThat(connector.getTopic()).isEqualTo("shop.orders");
            assertThat(connector.getFormat()).isEqualTo(KafkaSinkConnector.SerializationFormat.AVRO);
            assertThat(connector.getDeliveryGuarantee()).isEqualTo(DeliveryGuarantee.EXACTLY_ONCE);
            assertThat(connector.getTransactionalIdPrefix()).isEqualTo("cdc-tx-");
        }

        @Test
        @DisplayName("highThroughput — 设置高吞吐配置")
        void highThroughput() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .topic("test-topic")
                    .highThroughput()
                    .build();

            assertThat(connector.getKafkaProperties().getProperty("linger.ms")).isEqualTo("10");
            assertThat(connector.getKafkaProperties().getProperty("batch.size")).isEqualTo("65536");
            assertThat(connector.getKafkaProperties().getProperty("compression.type")).isEqualTo("lz4");
            assertThat(connector.getKafkaProperties().getProperty("buffer.memory")).isEqualTo("67108864");
            assertThat(connector.getKafkaProperties().getProperty("max.in.flight.requests.per.connection"))
                    .isEqualTo("5");
        }

        @Test
        @DisplayName("property — 添加单个属性")
        void property() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .topic("test-topic")
                    .property("acks", "1")
                    .property("linger.ms", "5")
                    .build();

            assertThat(connector.getKafkaProperties().getProperty("acks")).isEqualTo("1");
            assertThat(connector.getKafkaProperties().getProperty("linger.ms")).isEqualTo("5");
        }

        @Test
        @DisplayName("properties — 批量设置属性")
        void properties() {
            Map<String, String> props = new LinkedHashMap<>();
            props.put("acks", "0");
            props.put("compression.type", "gzip");

            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .topic("test-topic")
                    .properties(props)
                    .build();

            assertThat(connector.getKafkaProperties().getProperty("acks")).isEqualTo("0");
            assertThat(connector.getKafkaProperties().getProperty("compression.type")).isEqualTo("gzip");
        }

        @Test
        @DisplayName("null bootstrapServers — 抛出 NPE")
        void nullBootstrapServers() {
            assertThatThrownBy(() -> KafkaSinkConnector.builder().bootstrapServers(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("配置校验")
    class ValidationTest {

        @Test
        @DisplayName("topic 与 namingStrategy 同时指定 — 抛出异常")
        void topicAndNamingStrategy() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .topic("test-topic")
                    .namingStrategy(TopicNamingStrategy.defaultStrategy())
                    .build();

            assertThatThrownBy(connector::validate)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("既无 topic 也无 namingStrategy — 抛出异常")
        void neitherTopicNorStrategy() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .build();

            assertThatThrownBy(connector::validate)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("仅指定 topic — 校验通过")
        void onlyTopic() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .topic("test-topic")
                    .build();

            connector.validate();
        }

        @Test
        @DisplayName("仅指定 namingStrategy — 校验通过")
        void onlyStrategy() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .namingStrategy(TopicNamingStrategy.defaultStrategy())
                    .build();

            connector.validate();
        }
    }

    @Nested
    @DisplayName("动态 Topic 路由")
    class DynamicTopicRoutingTest {

        @Test
        @DisplayName("根据 ChangeRecord.source 解析 Topic — 默认策略")
        void resolveTopic_defaultStrategy() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .namingStrategy(TopicNamingStrategy.defaultStrategy())
                    .build();

            Map<String, Object> source = new LinkedHashMap<>();
            source.put("db", "shop");
            source.put("table", "orders");
            ChangeRecord record = new ChangeRecord(null, Map.of("id", 1), "c", source, 100L);

            String topic = connector.resolveTopic(record);
            assertThat(topic).isEqualTo("shop.orders");
        }

        @Test
        @DisplayName("根据 ChangeRecord.source 解析 Topic — 多租户策略")
        void resolveTopic_multiTenantStrategy() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .namingStrategy(TopicNamingStrategy.multiTenant("tenant-a"))
                    .build();

            Map<String, Object> source = new LinkedHashMap<>();
            source.put("db", "shop");
            source.put("schema", "dbo");
            source.put("table", "orders");
            ChangeRecord record = new ChangeRecord(null, Map.of("id", 1), "c", source, 100L);

            String topic = connector.resolveTopic(record);
            assertThat(topic).isEqualTo("tenant-a.shop.dbo.orders");
        }

        @Test
        @DisplayName("source 为 null — 抛出异常")
        void nullSource() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .namingStrategy(TopicNamingStrategy.defaultStrategy())
                    .build();

            ChangeRecord record = new ChangeRecord(null, Map.of("id", 1), "c", null, 100L);

            assertThatThrownBy(() -> connector.resolveTopic(record))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("source.table 为 null — 抛出异常")
        void nullTable() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .namingStrategy(TopicNamingStrategy.defaultStrategy())
                    .build();

            Map<String, Object> source = new LinkedHashMap<>();
            source.put("db", "shop");
            ChangeRecord record = new ChangeRecord(null, Map.of("id", 1), "c", source, 100L);

            assertThatThrownBy(() -> connector.resolveTopic(record))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("null record — 抛出 NPE")
        void nullRecord() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .namingStrategy(TopicNamingStrategy.defaultStrategy())
                    .build();

            assertThatThrownBy(() -> connector.resolveTopic(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("值序列化器")
    class ValueSerializerTest {

        @Test
        @DisplayName("DebeziumJsonSerializationSchema — 正确序列化")
        void debeziumJsonSerializer() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .topic("test-topic")
                    .format(KafkaSinkConnector.SerializationFormat.DEBEZIUM_JSON)
                    .build();

            var serializer = connector.createValueSerializer();
            ChangeRecord record = new ChangeRecord(null, Map.of("id", 1), "c", null, 100L);

            byte[] bytes = serializer.serialize(record);
            String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            assertThat(json).contains("\"op\":\"c\"");
            assertThat(json).contains("\"id\":1");
        }

        @Test
        @DisplayName("null record — 序列化为 null")
        void nullRecord() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .topic("test-topic")
                    .build();

            var serializer = connector.createValueSerializer();
            assertThat(serializer.serialize(null)).isNull();
        }

        @Test
        @DisplayName("AVRO 格式 — 占位实现退化为 JSON")
        void avroPlaceholder() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .topic("test-topic")
                    .format(KafkaSinkConnector.SerializationFormat.AVRO)
                    .build();

            var serializer = connector.createValueSerializer();
            ChangeRecord record = new ChangeRecord(null, Map.of("id", 1), "c", null, 100L);

            byte[] bytes = serializer.serialize(record);
            assertThat(bytes).isNotNull();
            // 占位实现退化为 JSON
            String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            assertThat(json).startsWith("{");
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTest {

        @Test
        @DisplayName("包含关键配置信息")
        void containsKeyConfig() {
            KafkaSinkConnector connector = KafkaSinkConnector.builder()
                    .bootstrapServers("localhost:9092")
                    .topic("shop.orders")
                    .format(KafkaSinkConnector.SerializationFormat.AVRO)
                    .deliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
                    .build();

            String str = connector.toString();
            assertThat(str).contains("localhost:9092")
                    .contains("shop.orders")
                    .contains("AVRO")
                    .contains("exactly-once");
        }
    }
}