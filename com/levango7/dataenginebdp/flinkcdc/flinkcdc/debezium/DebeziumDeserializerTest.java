package com.shuqing.bigdata.flinkcdc.debezium;

import org.apache.flink.util.Collector;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DebeziumDeserializer} 单元测试，覆盖 Debezium JSON 解析各场景。
 *
 * <p>测试覆盖：</p>
 * <ul>
 *   <li>INSERT 操作（op=c）</li>
 *   <li>UPDATE 操作（op=u，before+after）</li>
 *   <li>DELETE 操作（op=d）</li>
 *   <li>SNAPSHOT 操作（op=r）</li>
 *   <li>字段映射正确性</li>
 *   <li>异常处理（格式错误/字段缺失/op 非法）</li>
 *   <li>包装格式（schema+payload）</li>
 *   <li>事务元数据</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
class DebeziumDeserializerTest {

    private final DebeziumDeserializer deserializer = new DebeziumDeserializer();

    // ===== INSERT 操作 =====

    @Nested
    @DisplayName("INSERT 操作 (op=c)")
    class InsertTest {

        @Test
        @DisplayName("扁平格式 — before 为 null，after 存在")
        void flatFormat() throws Exception {
            String json = "{"
                    + "\"before\":null,"
                    + "\"after\":{\"id\":1,\"name\":\"alice\",\"age\":30},"
                    + "\"op\":\"c\","
                    + "\"source\":{\"db\":\"shop\",\"table\":\"orders\",\"file\":\"binlog.000001\",\"pos\":1234},"
                    + "\"ts_ms\":1700000000000"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.getOp()).isEqualTo("c");
            assertThat(record.getBefore()).isNull();
            assertThat(record.getAfter()).containsEntry("id", 1L)
                    .containsEntry("name", "alice")
                    .containsEntry("age", 30L);
            assertThat(record.getTsMs()).isEqualTo(1700000000000L);
            assertThat(record.isInsert()).isTrue();
        }

        @Test
        @DisplayName("包装格式 — schema + payload")
        void wrapperFormat() throws Exception {
            String json = "{"
                    + "\"schema\":{\"type\":\"struct\",\"name\":\"mysql.shop.orders.Envelope\"},"
                    + "\"payload\":{"
                    +   "\"before\":null,"
                    +   "\"after\":{\"id\":1},"
                    +   "\"op\":\"c\","
                    +   "\"source\":{\"db\":\"shop\",\"table\":\"orders\"},"
                    +   "\"ts_ms\":100"
                    + "}}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.getOp()).isEqualTo("c");
            assertThat(record.getAfter()).containsEntry("id", 1L);
            assertThat(record.getSchema()).isNotNull()
                    .containsEntry("type", "struct");
        }

        @Test
        @DisplayName("包含 source 元数据")
        void withSource() throws Exception {
            String json = "{"
                    + "\"before\":null,\"after\":{\"id\":1},\"op\":\"c\","
                    + "\"source\":{\"db\":\"shop\",\"schema\":\"dbo\",\"table\":\"orders\","
                    + "\"file\":\"binlog.000001\",\"pos\":1234,\"gtid\":\"gtid-1\"},"
                    + "\"ts_ms\":100"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.getSource())
                    .containsEntry("db", "shop")
                    .containsEntry("schema", "dbo")
                    .containsEntry("table", "orders")
                    .containsEntry("file", "binlog.000001")
                    .containsEntry("pos", 1234L)
                    .containsEntry("gtid", "gtid-1");
        }
    }

    // ===== UPDATE 操作 =====

    @Nested
    @DisplayName("UPDATE 操作 (op=u)")
    class UpdateTest {

        @Test
        @DisplayName("before 和 after 均存在")
        void beforeAndAfter() throws Exception {
            String json = "{"
                    + "\"before\":{\"id\":1,\"name\":\"old\",\"status\":0},"
                    + "\"after\":{\"id\":1,\"name\":\"new\",\"status\":1},"
                    + "\"op\":\"u\","
                    + "\"source\":{\"db\":\"shop\",\"table\":\"orders\"},"
                    + "\"ts_ms\":2000"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.getOp()).isEqualTo("u");
            assertThat(record.isUpdate()).isTrue();
            assertThat(record.getBefore())
                    .containsEntry("id", 1L)
                    .containsEntry("name", "old")
                    .containsEntry("status", 0L);
            assertThat(record.getAfter())
                    .containsEntry("id", 1L)
                    .containsEntry("name", "new")
                    .containsEntry("status", 1L);
        }

        @Test
        @DisplayName("字段映射正确性 — 主键不变，其他字段变更")
        void fieldMapping() throws Exception {
            String json = "{"
                    + "\"before\":{\"id\":42,\"name\":\"v1\",\"ts\":\"2024-01-01\"},"
                    + "\"after\":{\"id\":42,\"name\":\"v2\",\"ts\":\"2024-01-02\"},"
                    + "\"op\":\"u\","
                    + "\"source\":{\"db\":\"test\",\"table\":\"t1\"},"
                    + "\"ts_ms\":3000"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.getBefore().get("id")).isEqualTo(record.getAfter().get("id"));
            assertThat(record.getBefore().get("name")).isEqualTo("v1");
            assertThat(record.getAfter().get("name")).isEqualTo("v2");
        }
    }

    // ===== DELETE 操作 =====

    @Nested
    @DisplayName("DELETE 操作 (op=d)")
    class DeleteTest {

        @Test
        @DisplayName("after 为 null，before 存在")
        void afterIsNull() throws Exception {
            String json = "{"
                    + "\"before\":{\"id\":5,\"name\":\"to-delete\"},"
                    + "\"after\":null,"
                    + "\"op\":\"d\","
                    + "\"source\":{\"db\":\"shop\",\"table\":\"orders\"},"
                    + "\"ts_ms\":4000"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.getOp()).isEqualTo("d");
            assertThat(record.isDelete()).isTrue();
            assertThat(record.getBefore()).containsEntry("id", 5L);
            assertThat(record.getAfter()).isNull();
        }
    }

    // ===== SNAPSHOT 操作 =====

    @Nested
    @DisplayName("SNAPSHOT 操作 (op=r)")
    class SnapshotTest {

        @Test
        @DisplayName("快照读 — op=r，source.snapshot=true")
        void snapshotRead() throws Exception {
            String json = "{"
                    + "\"before\":null,"
                    + "\"after\":{\"id\":1,\"name\":\"initial\"},"
                    + "\"op\":\"r\","
                    + "\"source\":{\"db\":\"shop\",\"table\":\"orders\",\"snapshot\":\"true\"},"
                    + "\"ts_ms\":5000"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.getOp()).isEqualTo("r");
            assertThat(record.isSnapshot()).isTrue();
            assertThat(record.isSnapshotEvent()).isTrue();
            assertThat(record.getAfter()).containsEntry("id", 1L);
        }

        @Test
        @DisplayName("快照最后一条 — source.snapshot=last")
        void snapshotLast() throws Exception {
            String json = "{"
                    + "\"before\":null,\"after\":{\"id\":100},\"op\":\"r\","
                    + "\"source\":{\"db\":\"shop\",\"table\":\"orders\",\"snapshot\":\"last\"},"
                    + "\"ts_ms\":6000"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.isSnapshotEvent()).isTrue();
        }

        @Test
        @DisplayName("非快照事件 — source.snapshot=false")
        void nonSnapshotEvent() throws Exception {
            String json = "{"
                    + "\"before\":null,\"after\":{\"id\":1},\"op\":\"c\","
                    + "\"source\":{\"db\":\"shop\",\"table\":\"orders\",\"snapshot\":\"false\"},"
                    + "\"ts_ms\":7000"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.isSnapshotEvent()).isFalse();
        }
    }

    // ===== 事务元数据 =====

    @Nested
    @DisplayName("事务元数据")
    class TransactionTest {

        @Test
        @DisplayName("含 transaction 字段 — 提取事务 ID")
        void withTransaction() throws Exception {
            String json = "{"
                    + "\"before\":null,\"after\":{\"id\":1},\"op\":\"c\","
                    + "\"source\":{\"db\":\"shop\",\"table\":\"orders\"},"
                    + "\"ts_ms\":100,"
                    + "\"transaction\":{\"id\":\"tx-abc\",\"total_order\":3,\"data_collection_order\":1}"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.getTransaction()).isNotNull()
                    .containsEntry("id", "tx-abc")
                    .containsEntry("total_order", 3L)
                    .containsEntry("data_collection_order", 1L);
            assertThat(record.transactionId()).isEqualTo("tx-abc");
        }

        @Test
        @DisplayName("无 transaction 字段 — transactionId 返回 null")
        void withoutTransaction() throws Exception {
            String json = "{\"before\":null,\"after\":{\"id\":1},\"op\":\"c\","
                    + "\"source\":{\"db\":\"shop\",\"table\":\"orders\"},\"ts_ms\":100}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.getTransaction()).isNull();
            assertThat(record.transactionId()).isNull();
        }
    }

    // ===== 异常处理 =====

    @Nested
    @DisplayName("异常处理")
    class ErrorHandlingTest {

        @Test
        @DisplayName("缺少 op 字段 — 抛出 IllegalArgumentException")
        void missingOp() {
            String json = "{\"before\":null,\"after\":{\"id\":1},\"ts_ms\":100}";

            assertThatThrownBy(() -> deserializer.deserializeJson(json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("op");
        }

        @Test
        @DisplayName("未知 op 编码 — 抛出 IllegalArgumentException")
        void unknownOp() {
            String json = "{\"before\":null,\"after\":{\"id\":1},\"op\":\"x\",\"ts_ms\":100}";

            assertThatThrownBy(() -> deserializer.deserializeJson(json))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("x");
        }

        @Test
        @DisplayName("空 op — 抛出 IllegalArgumentException")
        void emptyOp() {
            String json = "{\"before\":null,\"after\":{\"id\":1},\"op\":\"\",\"ts_ms\":100}";

            assertThatThrownBy(() -> deserializer.deserializeJson(json))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("空 JSON 字节数组 — 抛出 IllegalArgumentException")
        void emptyBytes() {
            assertThatThrownBy(() -> deserializer.deserializeJson(new byte[0]))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null JSON — 抛出 NPE")
        void nullJson() {
            assertThatThrownBy(() -> deserializer.deserializeJson((String) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("非法 JSON 格式 — 抛出 IOException")
        void invalidJson() {
            String json = "{not valid json";

            assertThatThrownBy(() -> deserializer.deserializeJson(json))
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("非对象 JSON — 抛出 IOException")
        void nonObjectJson() {
            String json = "[1,2,3]";

            assertThatThrownBy(() -> deserializer.deserializeJson(json))
                    .isInstanceOf(Exception.class);
        }
    }

    // ===== 类型解析 =====

    @Nested
    @DisplayName("JSON 类型解析")
    class TypeParsingTest {

        @Test
        @DisplayName("数值类型 — int/long/double 正确解析")
        void numericTypes() throws Exception {
            String json = "{"
                    + "\"before\":null,"
                    + "\"after\":{\"id\":42,\"count\":10000000000,\"price\":99.9},"
                    + "\"op\":\"c\",\"source\":{},\"ts_ms\":1"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.getAfter())
                    .containsEntry("id", 42L)
                    .containsEntry("count", 10000000000L)
                    .containsEntry("price", 99.9);
        }

        @Test
        @DisplayName("布尔类型 — true/false 正确解析")
        void booleanTypes() throws Exception {
            String json = "{"
                    + "\"before\":null,"
                    + "\"after\":{\"active\":true,\"deleted\":false},"
                    + "\"op\":\"c\",\"source\":{},\"ts_ms\":1"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.getAfter())
                    .containsEntry("active", Boolean.TRUE)
                    .containsEntry("deleted", Boolean.FALSE);
        }

        @Test
        @DisplayName("null 值 — 正确解析为 null")
        void nullValues() throws Exception {
            String json = "{"
                    + "\"before\":null,"
                    + "\"after\":{\"id\":1,\"name\":null},"
                    + "\"op\":\"c\",\"source\":{},\"ts_ms\":1"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            assertThat(record.getAfter()).containsEntry("name", null);
        }

        @Test
        @DisplayName("嵌套对象 — 正确解析为 Map")
        void nestedObject() throws Exception {
            String json = "{"
                    + "\"before\":null,"
                    + "\"after\":{\"id\":1,\"meta\":{\"key\":\"value\"}},"
                    + "\"op\":\"c\",\"source\":{},\"ts_ms\":1"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            Object meta = record.getAfter().get("meta");
            assertThat(meta).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> metaMap = (Map<String, Object>) meta;
            assertThat(metaMap).containsEntry("key", "value");
        }

        @Test
        @DisplayName("数组 — 正确解析为 List")
        void arrayValue() throws Exception {
            String json = "{"
                    + "\"before\":null,"
                    + "\"after\":{\"id\":1,\"tags\":[\"a\",\"b\",\"c\"]},"
                    + "\"op\":\"c\",\"source\":{},\"ts_ms\":1"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);

            Object tags = record.getAfter().get("tags");
            assertThat(tags).isInstanceOf(java.util.List.class);
            @SuppressWarnings("unchecked")
            java.util.List<Object> tagList = (java.util.List<Object>) tags;
            assertThat(tagList).containsExactly("a", "b", "c");
        }
    }

    // ===== DebeziumChangeRecord 扩展功能 =====

    @Nested
    @DisplayName("DebeziumChangeRecord 扩展功能")
    class ExtensionTest {

        @Test
        @DisplayName("from(ChangeRecord) — 保留所有基础字段")
        void fromChangeRecord() {
            Map<String, Object> before = Map.of("id", 1);
            Map<String, Object> after = Map.of("id", 2);
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("db", "shop");
            source.put("table", "orders");

            com.shuqing.bigdata.flinkcdc.model.ChangeRecord base =
                    new com.shuqing.bigdata.flinkcdc.model.ChangeRecord(before, after, "u", source, 100L);

            DebeziumChangeRecord ext = DebeziumChangeRecord.from(base);

            assertThat(ext.getBefore()).isEqualTo(before);
            assertThat(ext.getAfter()).isEqualTo(after);
            assertThat(ext.getOp()).isEqualTo("u");
            assertThat(ext.getSource()).isEqualTo(source);
            assertThat(ext.getTsMs()).isEqualTo(100L);
            assertThat(ext.getSchema()).isNull();
            assertThat(ext.getTransaction()).isNull();
        }

        @Test
        @DisplayName("equals — 含扩展字段")
        void equals_withExtensionFields() {
            Map<String, Object> schema = Map.of("type", "struct");
            DebeziumChangeRecord r1 = new DebeziumChangeRecord(
                    null, Map.of("id", 1), "c", null, 1L, schema, null, null);
            DebeziumChangeRecord r2 = new DebeziumChangeRecord(
                    null, Map.of("id", 1), "c", null, 1L, schema, null, null);
            DebeziumChangeRecord r3 = new DebeziumChangeRecord(
                    null, Map.of("id", 1), "c", null, 1L, null, null, null);

            assertThat(r1).isEqualTo(r2);
            assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
            assertThat(r1).isNotEqualTo(r3);
        }

        @Test
        @DisplayName("toString — 包含 op 和 schema 标识")
        void toString_containsFields() {
            DebeziumChangeRecord record = new DebeziumChangeRecord(
                    null, Map.of("id", 1), "c", null, 1L,
                    Map.of("type", "struct"), null, null);
            String str = record.toString();
            assertThat(str).contains("op='c'").contains("schema=present");
        }
    }

    // ===== SourceRecord 解析测试 =====

    /**
     * 测试用 Collector，收集输出的 ChangeRecord。
     */
    static class TestCollector implements Collector<com.shuqing.bigdata.flinkcdc.model.ChangeRecord> {
        final List<com.shuqing.bigdata.flinkcdc.model.ChangeRecord> records = new ArrayList<>();

        @Override
        public void collect(com.shuqing.bigdata.flinkcdc.model.ChangeRecord record) {
            records.add(record);
        }

        @Override
        public void close() {
            // no-op
        }
    }

    /**
     * 构造 Debezium value Struct 的 Schema。
     *
     * @return Debezium Envelope Schema
     */
    private static Schema buildEnvelopeSchema() {
        Schema dataSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("name", Schema.STRING_SCHEMA)
                .optional()
                .build();

        Schema sourceSchema = SchemaBuilder.struct()
                .field("db", Schema.STRING_SCHEMA)
                .field("table", Schema.STRING_SCHEMA)
                .field("snapshot", Schema.STRING_SCHEMA)
                .build();

        return SchemaBuilder.struct()
                .field("before", dataSchema)
                .field("after", dataSchema)
                .field("op", Schema.STRING_SCHEMA)
                .field("source", sourceSchema)
                .field("ts_ms", Schema.INT64_SCHEMA)
                .build();
    }

    /**
     * 构造一个 INSERT 类型的 Debezium value Struct。
     *
     * @return Struct
     */
    private static Struct buildInsertStruct() {
        Schema envelope = buildEnvelopeSchema();
        Schema dataSchema = envelope.field("after").schema();
        Schema sourceSchema = envelope.field("source").schema();

        return new Struct(envelope)
                .put("before", null)
                .put("after", new Struct(dataSchema).put("id", 1).put("name", "alice"))
                .put("op", "c")
                .put("source", new Struct(sourceSchema)
                        .put("db", "shop")
                        .put("table", "orders")
                        .put("snapshot", "false"))
                .put("ts_ms", 100L);
    }

    @Nested
    @DisplayName("deserialize(SourceRecord) — Struct 解析")
    class SourceRecordTest {

        @Test
        @DisplayName("正常 Struct — 输出 DebeziumChangeRecord")
        void normalStruct() {
            Struct value = buildInsertStruct();
            SourceRecord record = new SourceRecord(
                    Collections.emptyMap(), Collections.emptyMap(),
                    "test-topic", 0, value.schema(), value);

            TestCollector collector = new TestCollector();
            deserializer.deserialize(record, collector);

            assertThat(collector.records).hasSize(1);
            DebeziumChangeRecord change = (DebeziumChangeRecord) collector.records.get(0);
            assertThat(change.getOp()).isEqualTo("c");
            assertThat(change.getAfter())
                    .containsEntry("id", 1)
                    .containsEntry("name", "alice");
            assertThat(change.getTsMs()).isEqualTo(100L);
        }

        @Test
        @DisplayName("tombstone 记录 (value=null) — 跳过不输出")
        void tombstoneRecord() {
            SourceRecord record = new SourceRecord(
                    Collections.emptyMap(), Collections.emptyMap(),
                    "test-topic", 0, null, null);

            TestCollector collector = new TestCollector();
            deserializer.deserialize(record, collector);

            assertThat(collector.records).isEmpty();
        }

        @Test
        @DisplayName("非 Struct 类型 — 跳过不输出")
        void nonStructValue() {
            SourceRecord record = new SourceRecord(
                    Collections.emptyMap(), Collections.emptyMap(),
                    "test-topic", 0, Schema.STRING_SCHEMA, "raw-string");

            TestCollector collector = new TestCollector();
            deserializer.deserialize(record, collector);

            assertThat(collector.records).isEmpty();
        }

        @Test
        @DisplayName("null record — 抛出 NPE")
        void nullRecord() {
            TestCollector collector = new TestCollector();
            assertThatThrownBy(() -> deserializer.deserialize(null, collector))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("DELETE 操作 — before 存在，after 为 null")
        void deleteStruct() {
            Schema envelope = buildEnvelopeSchema();
            Schema dataSchema = envelope.field("before").schema();
            Schema sourceSchema = envelope.field("source").schema();

            Struct value = new Struct(envelope)
                    .put("before", new Struct(dataSchema).put("id", 5).put("name", "to-delete"))
                    .put("after", null)
                    .put("op", "d")
                    .put("source", new Struct(sourceSchema)
                            .put("db", "shop")
                            .put("table", "orders")
                            .put("snapshot", "false"))
                    .put("ts_ms", 200L);

            SourceRecord record = new SourceRecord(
                    Collections.emptyMap(), Collections.emptyMap(),
                    "test-topic", 0, envelope, value);

            TestCollector collector = new TestCollector();
            deserializer.deserialize(record, collector);

            assertThat(collector.records).hasSize(1);
            DebeziumChangeRecord change = (DebeziumChangeRecord) collector.records.get(0);
            assertThat(change.getOp()).isEqualTo("d");
            assertThat(change.isDelete()).isTrue();
            assertThat(change.getBefore()).containsEntry("id", 5);
            assertThat(change.getAfter()).isNull();
        }

        @Test
        @DisplayName("缺少 op 字段 — 抛出 IllegalArgumentException")
        void missingOpField() {
            Schema dataSchema = SchemaBuilder.struct()
                    .field("id", Schema.INT32_SCHEMA)
                    .optional()
                    .build();
            Schema sourceSchema = SchemaBuilder.struct()
                    .field("db", Schema.STRING_SCHEMA)
                    .build();
            // 不含 op 字段的 Schema
            Schema envelope = SchemaBuilder.struct()
                    .field("before", dataSchema)
                    .field("after", dataSchema)
                    .field("source", sourceSchema)
                    .field("ts_ms", Schema.INT64_SCHEMA)
                    .build();

            Struct value = new Struct(envelope)
                    .put("before", null)
                    .put("after", new Struct(dataSchema).put("id", 1))
                    .put("source", new Struct(sourceSchema).put("db", "shop"))
                    .put("ts_ms", 100L);

            SourceRecord record = new SourceRecord(
                    Collections.emptyMap(), Collections.emptyMap(),
                    "test-topic", 0, envelope, value);

            TestCollector collector = new TestCollector();
            assertThatThrownBy(() -> deserializer.deserialize(record, collector))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("op");
        }

        @Test
        @DisplayName("未知 op 编码 — 抛出 IllegalArgumentException")
        void unknownOp() {
            Schema envelope = buildEnvelopeSchema();
            Schema dataSchema = envelope.field("after").schema();
            Schema sourceSchema = envelope.field("source").schema();

            Struct value = new Struct(envelope)
                    .put("before", null)
                    .put("after", new Struct(dataSchema).put("id", 1).put("name", "x"))
                    .put("op", "z")
                    .put("source", new Struct(sourceSchema)
                            .put("db", "shop")
                            .put("table", "orders")
                            .put("snapshot", "false"))
                    .put("ts_ms", 100L);

            SourceRecord record = new SourceRecord(
                    Collections.emptyMap(), Collections.emptyMap(),
                    "test-topic", 0, envelope, value);

            TestCollector collector = new TestCollector();
            assertThatThrownBy(() -> deserializer.deserialize(record, collector))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("z");
        }
    }

    @Nested
    @DisplayName("SchemaResolver 集成")
    class SchemaResolverTest {

        @Test
        @DisplayName("parseStruct with SchemaResolver — schema 信息被提取")
        void withSchemaResolver() {
            Schema envelope = buildEnvelopeSchema();
            Schema dataSchema = envelope.field("after").schema();
            Schema sourceSchema = envelope.field("source").schema();

            Struct value = new Struct(envelope)
                    .put("before", null)
                    .put("after", new Struct(dataSchema).put("id", 1).put("name", "alice"))
                    .put("op", "c")
                    .put("source", new Struct(sourceSchema)
                            .put("db", "shop")
                            .put("table", "orders")
                            .put("snapshot", "false"))
                    .put("ts_ms", 100L);

            // SchemaResolver 返回自定义 schema 信息
            DebeziumDeserializer.SchemaResolver resolver = struct ->
                    Map.of("type", "struct", "name", "custom");
            DebeziumDeserializer deserializerWithResolver = new DebeziumDeserializer(resolver);

            DebeziumChangeRecord record = deserializerWithResolver.parseStruct(value);
            assertThat(record.getSchema()).isNotNull()
                    .containsEntry("type", "struct")
                    .containsEntry("name", "custom");
        }

        @Test
        @DisplayName("parseStruct without SchemaResolver — schema 为 null")
        void withoutSchemaResolver() {
            Struct value = buildInsertStruct();
            DebeziumChangeRecord record = deserializer.parseStruct(value);
            assertThat(record.getSchema()).isNull();
        }
    }

    @Nested
    @DisplayName("getProducedType")
    class ProducedTypeTest {

        @Test
        @DisplayName("返回 ChangeRecord 类型信息")
        void producedType() {
            assertThat(deserializer.getProducedType()).isNotNull();
        }
    }

    @Nested
    @DisplayName("ts_ms 字符串类型解析")
    class TsMsStringTest {

        @Test
        @DisplayName("ts_ms 为字符串 — 正确解析为 Long")
        void tsMsAsString() throws Exception {
            String json = "{"
                    + "\"before\":null,\"after\":{\"id\":1},\"op\":\"c\","
                    + "\"source\":{},\"ts_ms\":\"1700000000000\""
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);
            assertThat(record.getTsMs()).isEqualTo(1700000000000L);
        }
    }

    @Nested
    @DisplayName("sourceMeta 扩展字段提取")
    class SourceMetaTest {

        @Test
        @DisplayName("含 snapshot 字段 — 提取到 sourceMeta")
        void snapshotField() throws Exception {
            String json = "{"
                    + "\"before\":null,\"after\":{\"id\":1},\"op\":\"c\","
                    + "\"source\":{\"db\":\"shop\",\"table\":\"orders\",\"snapshot\":\"true\"},"
                    + "\"ts_ms\":100"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);
            assertThat(record.getSourceMeta()).isNotNull()
                    .containsEntry("snapshot", "true");
        }

        @Test
        @DisplayName("含 lsn/txId/thread 字段 — 提取到 sourceMeta")
        void lsnTxIdThreadFields() throws Exception {
            String json = "{"
                    + "\"before\":null,\"after\":{\"id\":1},\"op\":\"c\","
                    + "\"source\":{\"db\":\"shop\",\"table\":\"orders\","
                    + "\"lsn\":12345,\"txId\":99,\"thread\":\"worker-1\"},"
                    + "\"ts_ms\":100"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);
            assertThat(record.getSourceMeta()).isNotNull()
                    .containsEntry("lsn", 12345L)
                    .containsEntry("txId", 99L)
                    .containsEntry("thread", "worker-1");
        }

        @Test
        @DisplayName("无扩展字段 — sourceMeta 为 null")
        void noMetaFields() throws Exception {
            String json = "{"
                    + "\"before\":null,\"after\":{\"id\":1},\"op\":\"c\","
                    + "\"source\":{\"db\":\"shop\",\"table\":\"orders\"},"
                    + "\"ts_ms\":100"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);
            assertThat(record.getSourceMeta()).isNull();
        }

        @Test
        @DisplayName("source 为 null — sourceMeta 为 null")
        void nullSource() throws Exception {
            String json = "{"
                    + "\"before\":null,\"after\":{\"id\":1},\"op\":\"c\","
                    + "\"source\":null,"
                    + "\"ts_ms\":100"
                    + "}";

            DebeziumChangeRecord record = deserializer.deserializeJson(json);
            assertThat(record.getSourceMeta()).isNull();
            assertThat(record.getSource()).isNull();
        }
    }

    @Nested
    @DisplayName("deserializeJson 字节数组")
    class DeserializeJsonBytesTest {

        @Test
        @DisplayName("null 字节数组 — 抛出 NPE")
        void nullBytes() {
            assertThatThrownBy(() -> deserializer.deserializeJson((byte[]) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("字节数组与字符串结果一致")
        void bytesEqualsString() throws Exception {
            String json = "{\"before\":null,\"after\":{\"id\":1},\"op\":\"c\","
                    + "\"source\":{},\"ts_ms\":1}";

            DebeziumChangeRecord fromString = deserializer.deserializeJson(json);
            DebeziumChangeRecord fromBytes = deserializer.deserializeJson(
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            assertThat(fromString).isEqualTo(fromBytes);
        }
    }

    @Nested
    @DisplayName("DebeziumChangeRecord 默认构造与 Setter")
    class ChangeRecordSetterTest {

        @Test
        @DisplayName("默认构造 + Setter — 正确设置字段")
        void defaultConstructorWithSetter() {
            DebeziumChangeRecord record = new DebeziumChangeRecord();
            record.setBefore(Map.of("id", 1));
            record.setAfter(Map.of("id", 2));
            record.setOp("u");
            record.setSource(Map.of("db", "shop"));
            record.setTsMs(100L);
            record.setSchema(Map.of("type", "struct"));
            record.setSourceMeta(Map.of("snapshot", "true"));
            record.setTransaction(Map.of("id", "tx-1"));

            assertThat(record.getBefore()).containsEntry("id", 1);
            assertThat(record.getAfter()).containsEntry("id", 2);
            assertThat(record.getOp()).isEqualTo("u");
            assertThat(record.getSource()).containsEntry("db", "shop");
            assertThat(record.getTsMs()).isEqualTo(100L);
            assertThat(record.getSchema()).containsEntry("type", "struct");
            assertThat(record.getSourceMeta()).containsEntry("snapshot", "true");
            assertThat(record.getTransaction()).containsEntry("id", "tx-1");
        }

        @Test
        @DisplayName("from(null) — 抛出 NPE")
        void fromNull() {
            assertThatThrownBy(() -> DebeziumChangeRecord.from(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("isSnapshotEvent — sourceMeta 中 snapshot=last")
        void snapshotEventFromMeta() {
            DebeziumChangeRecord record = new DebeziumChangeRecord(
                    null, Map.of("id", 1), "r", null, 1L,
                    null, Map.of("snapshot", "last"), null);
            assertThat(record.isSnapshotEvent()).isTrue();
        }

        @Test
        @DisplayName("transactionId — transaction 为 null 返回 null")
        void transactionIdNull() {
            DebeziumChangeRecord record = new DebeziumChangeRecord(
                    null, Map.of("id", 1), "c", null, 1L, null, null, null);
            assertThat(record.transactionId()).isNull();
        }

        @Test
        @DisplayName("transactionId — id 为 null 返回 null")
        void transactionIdNullId() {
            DebeziumChangeRecord record = new DebeziumChangeRecord(
                    null, Map.of("id", 1), "c", null, 1L,
                    null, null, Map.of("total_order", 1));
            assertThat(record.transactionId()).isNull();
        }

        @Test
        @DisplayName("equals — 与 null 比较")
        void equalsNull() {
            DebeziumChangeRecord record = new DebeziumChangeRecord(
                    null, Map.of("id", 1), "c", null, 1L, null, null, null);
            assertThat(record.equals(null)).isFalse();
        }

        @Test
        @DisplayName("equals — 与非 DebeziumChangeRecord 类型比较")
        void equalsDifferentType() {
            DebeziumChangeRecord record = new DebeziumChangeRecord(
                    null, Map.of("id", 1), "c", null, 1L, null, null, null);
            assertThat(record.equals("string")).isFalse();
        }

        @Test
        @DisplayName("equals — 自反性")
        void equalsReflexive() {
            DebeziumChangeRecord record = new DebeziumChangeRecord(
                    null, Map.of("id", 1), "c", null, 1L, null, null, null);
            assertThat(record.equals(record)).isTrue();
        }

        @Test
        @DisplayName("toString — schema 为 null")
        void toStringSchemaNull() {
            DebeziumChangeRecord record = new DebeziumChangeRecord(
                    null, Map.of("id", 1), "c", null, 1L, null, null, null);
            String str = record.toString();
            assertThat(str).contains("schema=null");
        }
    }
}