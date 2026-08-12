package com.levango7.dataenginebdp.flinkcdc.source;

import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord;
import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord.Op;
import com.ververica.cdc.connectors.base.options.StartupOptions;
import org.apache.flink.util.Collector;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PostgresSourceConnector} 单元测试。
 *
 * <p>测试覆盖：配置校验、启动模式解析、变更记录转换、Logical Replication Slot 配置验证、
 * ChangeRecordDeserializer 反序列化、createSource 实际构建等。</p>
 *
 * @author shuqing-bigdata
 */
class PostgresSourceConnectorTest {

    /**
     * 构造一个合法的默认 PostgresSourceConfig。
     */
    private PostgresSourceConfig validConfig() {
        return PostgresSourceConfig.builder()
                .name("test-pg")
                .host("127.0.0.1")
                .port(5432)
                .username("cdc")
                .password("pass")
                .database("shop")
                .schemaList("public")
                .tableList("public.orders")
                .slotName("flink_slot")
                .decodingPlugin(PostgresSourceConfig.DecodingPlugin.PGOUTPUT)
                .startupMode(SourceConfig.StartupMode.INITIAL)
                .build();
    }

    @Nested
    @DisplayName("validate — 配置校验")
    class ValidateTest {

        @Test
        @DisplayName("合法配置 — 校验通过")
        void validate_validConfig_passes() {
            PostgresSourceConnector.validate(validConfig());
        }

        @Test
        @DisplayName("config 为 null — 抛出 NPE")
        void validate_nullConfig_throwsNpe() {
            assertThatThrownBy(() -> PostgresSourceConnector.validate(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("base 为 null — 抛出异常")
        void validate_nullBase_throws() {
            PostgresSourceConfig config = new PostgresSourceConfig();
            config.setBase(null);
            assertThatThrownBy(() -> PostgresSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("base");
        }

        @Test
        @DisplayName("host 为空 — 抛出异常")
        void validate_emptyHost_throws() {
            PostgresSourceConfig config = validConfig();
            config.getBase().setHost("");
            assertThatThrownBy(() -> PostgresSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("host");
        }

        @Test
        @DisplayName("username 为空 — 抛出异常")
        void validate_emptyUsername_throws() {
            PostgresSourceConfig config = validConfig();
            config.getBase().setUsername(null);
            assertThatThrownBy(() -> PostgresSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("username");
        }

        @Test
        @DisplayName("database 为空 — 抛出异常")
        void validate_emptyDatabase_throws() {
            PostgresSourceConfig config = validConfig();
            config.getBase().setDatabase("  ");
            assertThatThrownBy(() -> PostgresSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("database");
        }

        @Test
        @DisplayName("slotName 为空 — 抛出异常")
        void validate_emptySlotName_throws() {
            PostgresSourceConfig config = validConfig();
            config.setSlotName(null);
            assertThatThrownBy(() -> PostgresSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("slotName");
        }

        @Test
        @DisplayName("decodingPlugin 为 null — 抛出异常")
        void validate_nullDecodingPlugin_throws() {
            PostgresSourceConfig config = validConfig();
            config.setDecodingPlugin(null);
            assertThatThrownBy(() -> PostgresSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("decodingPlugin");
        }

        @Test
        @DisplayName("schemaList 为空 — 抛出异常")
        void validate_emptySchemaList_throws() {
            PostgresSourceConfig config = validConfig();
            config.setSchemaList(java.util.Set.of());
            assertThatThrownBy(() -> PostgresSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("schemaList");
        }

        @Test
        @DisplayName("tableList 为空 — 抛出异常")
        void validate_emptyTableList_throws() {
            PostgresSourceConfig config = validConfig();
            config.setTableList(java.util.Set.of());
            assertThatThrownBy(() -> PostgresSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tableList");
        }

        @Test
        @DisplayName("port 越界 — 抛出异常")
        void validate_invalidPort_throws() {
            PostgresSourceConfig config = validConfig();
            config.getBase().setPort(70000);
            assertThatThrownBy(() -> PostgresSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("port");
        }

        @Test
        @DisplayName("port 为 0 — 抛出异常")
        void validate_zeroPort_throws() {
            PostgresSourceConfig config = validConfig();
            config.getBase().setPort(0);
            assertThatThrownBy(() -> PostgresSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("port");
        }

        @Test
        @DisplayName("TIMESTAMP 模式未指定时间戳 — 抛出异常")
        void validate_timestampWithoutMillis_throws() {
            PostgresSourceConfig config = validConfig();
            config.getBase().setStartupMode(SourceConfig.StartupMode.TIMESTAMP);
            config.getBase().setStartupTimestampMillis(null);
            assertThatThrownBy(() -> PostgresSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("startupTimestampMillis");
        }

        @Test
        @DisplayName("TIMESTAMP 模式指定时间戳 — 校验通过")
        void validate_timestampWithMillis_passes() {
            PostgresSourceConfig config = validConfig();
            config.getBase().setStartupMode(SourceConfig.StartupMode.TIMESTAMP);
            config.getBase().setStartupTimestampMillis(1700000000000L);
            PostgresSourceConnector.validate(config);
        }
    }

    @Nested
    @DisplayName("resolveStartupOptions — 启动模式解析")
    class StartupOptionsTest {

        @Test
        @DisplayName("INITIAL 模式 — 返回 initial 选项")
        void resolve_initial() {
            SourceConfig base = validConfig().getBase();
            base.setStartupMode(SourceConfig.StartupMode.INITIAL);
            StartupOptions options = PostgresSourceConnector.resolveStartupOptions(base);
            assertThat(options).isNotNull();
        }

        @Test
        @DisplayName("LATEST_OFFSET 模式 — 返回 latest 选项")
        void resolve_latestOffset() {
            SourceConfig base = validConfig().getBase();
            base.setStartupMode(SourceConfig.StartupMode.LATEST_OFFSET);
            StartupOptions options = PostgresSourceConnector.resolveStartupOptions(base);
            assertThat(options).isNotNull();
        }

        @Test
        @DisplayName("TIMESTAMP 模式 — 返回 timestamp 选项")
        void resolve_timestamp() {
            SourceConfig base = validConfig().getBase();
            base.setStartupMode(SourceConfig.StartupMode.TIMESTAMP);
            base.setStartupTimestampMillis(1700000000000L);
            StartupOptions options = PostgresSourceConnector.resolveStartupOptions(base);
            assertThat(options).isNotNull();
        }

        @Test
        @DisplayName("SPECIFIC_OFFSET 模式 — 回退到 latest 选项")
        void resolve_specificOffset() {
            SourceConfig base = validConfig().getBase();
            base.setStartupMode(SourceConfig.StartupMode.SPECIFIC_OFFSET);
            StartupOptions options = PostgresSourceConnector.resolveStartupOptions(base);
            assertThat(options).isNotNull();
        }
    }

    @Nested
    @DisplayName("toChangeRecord — 变更记录转换")
    class ToChangeRecordTest {

        @Test
        @DisplayName("INSERT 记录 — before 为 null")
        void toChangeRecord_insert() {
            Map<String, Object> after = Map.of("id", 1, "name", "alice");
            ChangeRecord record = PostgresSourceConnector.toChangeRecord(null, after, "c", null, 100L);

            assertThat(record.getBefore()).isNull();
            assertThat(record.getAfter()).isEqualTo(after);
            assertThat(record.getOp()).isEqualTo("c");
            assertThat(record.isInsert()).isTrue();
        }

        @Test
        @DisplayName("UPDATE 记录 — before 和 after 均存在")
        void toChangeRecord_update() {
            Map<String, Object> before = Map.of("id", 1, "name", "old");
            Map<String, Object> after = Map.of("id", 1, "name", "new");
            ChangeRecord record = PostgresSourceConnector.toChangeRecord(before, after, "u", null, 200L);

            assertThat(record.getBefore()).isEqualTo(before);
            assertThat(record.getAfter()).isEqualTo(after);
            assertThat(record.isUpdate()).isTrue();
        }

        @Test
        @DisplayName("DELETE 记录 — after 为 null")
        void toChangeRecord_delete() {
            Map<String, Object> before = Map.of("id", 1, "name", "alice");
            ChangeRecord record = PostgresSourceConnector.toChangeRecord(before, null, "d", null, 300L);

            assertThat(record.getBefore()).isEqualTo(before);
            assertThat(record.getAfter()).isNull();
            assertThat(record.isDelete()).isTrue();
        }

        @Test
        @DisplayName("SNAPSHOT 记录 — op=r")
        void toChangeRecord_snapshot() {
            ChangeRecord record = PostgresSourceConnector.toChangeRecord(
                    null, Map.of("id", 1), "r", Map.of("db", "shop", "schema", "public"), 400L);
            assertThat(record.isSnapshot()).isTrue();
            assertThat(record.opEnum()).isEqualTo(Op.SNAPSHOT);
        }

        @Test
        @DisplayName("包含 source 元数据（含 LSN 位点）")
        void toChangeRecord_withSource() {
            Map<String, Object> source = Map.of(
                    "db", "shop", "schema", "public", "table", "orders",
                    "lsn", 123456, "txId", 100L, "slotName", "flink_slot");
            ChangeRecord record = PostgresSourceConnector.toChangeRecord(
                    null, Map.of("id", 1), "c", source, 500L);

            assertThat(record.getSource()).isEqualTo(source);
            assertThat(record.getSource()).containsKey("lsn");
            assertThat(record.getSource()).containsKey("slotName");
        }

        @Test
        @DisplayName("tsMs 为 null — 允许")
        void toChangeRecord_nullTsMs() {
            ChangeRecord record = PostgresSourceConnector.toChangeRecord(
                    null, Map.of("id", 1), "c", null, null);
            assertThat(record.getTsMs()).isNull();
        }
    }

    @Nested
    @DisplayName("常量字段名")
    class ConstantsTest {

        @Test
        @DisplayName("Debezium 字段名常量正确")
        void fieldConstants() {
            assertThat(PostgresSourceConnector.FIELD_BEFORE).isEqualTo("before");
            assertThat(PostgresSourceConnector.FIELD_AFTER).isEqualTo("after");
            assertThat(PostgresSourceConnector.FIELD_OP).isEqualTo("op");
            assertThat(PostgresSourceConnector.FIELD_SOURCE).isEqualTo("source");
            assertThat(PostgresSourceConnector.FIELD_TS_MS).isEqualTo("ts_ms");
        }
    }

    @Nested
    @DisplayName("createSource — Source 构建")
    class CreateSourceTest {

        @Test
        @DisplayName("config 为 null — 抛出 NPE")
        void createSource_nullConfig_throwsNpe() {
            assertThatThrownBy(() -> PostgresSourceConnector.createSource(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("合法配置 — 成功构建 PostgresIncrementalSource")
        void createSource_validConfig_builds() {
            PostgresSourceConfig config = validConfig();
            var source = PostgresSourceConnector.createSource(config);
            assertThat(source).isNotNull();
        }

        @Test
        @DisplayName("LATEST_OFFSET 模式 — 成功构建")
        void createSource_latestOffset_builds() {
            PostgresSourceConfig config = validConfig();
            config.getBase().setStartupMode(SourceConfig.StartupMode.LATEST_OFFSET);
            var source = PostgresSourceConnector.createSource(config);
            assertThat(source).isNotNull();
        }

        @Test
        @DisplayName("TIMESTAMP 模式 — Flink CDC 3.0 不支持，抛出 UnsupportedOperationException")
        void createSource_timestamp_throws() {
            PostgresSourceConfig config = validConfig();
            config.getBase().setStartupMode(SourceConfig.StartupMode.TIMESTAMP);
            config.getBase().setStartupTimestampMillis(1700000000000L);
            assertThatThrownBy(() -> PostgresSourceConnector.createSource(config))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("DECODERBUFS 插件 — 成功构建")
        void createSource_decoderbufs_builds() {
            PostgresSourceConfig config = validConfig();
            config.setDecodingPlugin(PostgresSourceConfig.DecodingPlugin.DECODERBUFS);
            var source = PostgresSourceConnector.createSource(config);
            assertThat(source).isNotNull();
        }

        @Test
        @DisplayName("WAL2JSON 插件 — 成功构建")
        void createSource_wal2json_builds() {
            PostgresSourceConfig config = validConfig();
            config.setDecodingPlugin(PostgresSourceConfig.DecodingPlugin.WAL2JSON);
            var source = PostgresSourceConnector.createSource(config);
            assertThat(source).isNotNull();
        }

        @Test
        @DisplayName("多 schema 多 table — 成功构建")
        void createSource_multiSchemaTable_builds() {
            PostgresSourceConfig config = PostgresSourceConfig.builder()
                    .name("test-pg-multi")
                    .host("127.0.0.1").port(5432)
                    .username("cdc").password("pass")
                    .database("shop")
                    .schemaList("public", "audit")
                    .tableList("public.orders", "audit.logs")
                    .slotName("flink_slot_2")
                    .decodingPlugin(PostgresSourceConfig.DecodingPlugin.PGOUTPUT)
                    .startupMode(SourceConfig.StartupMode.INITIAL)
                    .build();
            var source = PostgresSourceConnector.createSource(config);
            assertThat(source).isNotNull();
        }
    }

    @Nested
    @DisplayName("ChangeRecordDeserializer — 反序列化")
    class DeserializerTest {

        /**
         * 将 List 包装为 Flink Collector（Collector 非 FunctionalInterface，需匿名内部类）。
         */
        private Collector<ChangeRecord> toCollector(List<ChangeRecord> output) {
            return new Collector<>() {
                @Override
                public void collect(ChangeRecord record) {
                    output.add(record);
                }
                @Override
                public void close() {
                    // no-op
                }
            };
        }

        /**
         * 构造一个 Debezium 格式的 SourceRecord。
         */
        private SourceRecord buildRecord(Struct before, Struct after, String op, Long tsMs) {
            Schema sourceSchema = SchemaBuilder.struct()
                    .field("db", Schema.STRING_SCHEMA)
                    .field("schema", Schema.STRING_SCHEMA)
                    .field("table", Schema.STRING_SCHEMA)
                    .build();
            Struct source = new Struct(sourceSchema);
            source.put("db", "shop");
            source.put("schema", "public");
            source.put("table", "orders");

            Schema valueSchema = SchemaBuilder.struct()
                    .field("before", before != null ? before.schema() : SchemaBuilder.struct().optional().build())
                    .field("after", after != null ? after.schema() : SchemaBuilder.struct().optional().build())
                    .field("op", Schema.STRING_SCHEMA)
                    .field("source", sourceSchema)
                    .field("ts_ms", Schema.INT64_SCHEMA)
                    .build();

            Struct value = new Struct(valueSchema);
            value.put("before", before);
            value.put("after", after);
            value.put("op", op);
            value.put("source", source);
            value.put("ts_ms", tsMs);

            Map<String, String> sourcePartition = new HashMap<>();
            sourcePartition.put("server", "flink_slot");
            Map<String, Object> sourceOffset = new HashMap<>();
            sourceOffset.put("lsn", 123456);
            return new SourceRecord(sourcePartition, sourceOffset, "test-topic",
                    0, valueSchema, value);
        }

        private Struct buildRow(int id, String name) {
            Schema schema = SchemaBuilder.struct()
                    .field("id", Schema.INT32_SCHEMA)
                    .field("name", Schema.STRING_SCHEMA)
                    .build();
            Struct row = new Struct(schema);
            row.put("id", id);
            row.put("name", name);
            return row;
        }

        @Test
        @DisplayName("INSERT 记录反序列化 — before 为 null")
        void deserialize_insert() {
            Struct after = buildRow(1, "alice");
            SourceRecord record = buildRecord(null, after, "c", 100L);

            List<ChangeRecord> output = new ArrayList<>();
            PostgresSourceConnector.ChangeRecordDeserializer deserializer =
                    new PostgresSourceConnector.ChangeRecordDeserializer();
            deserializer.deserialize(record, toCollector(output));

            assertThat(output).hasSize(1);
            ChangeRecord cr = output.get(0);
            assertThat(cr.getBefore()).isNull();
            assertThat(cr.getAfter()).containsEntry("id", 1).containsEntry("name", "alice");
            assertThat(cr.getOp()).isEqualTo("c");
            assertThat(cr.isInsert()).isTrue();
            assertThat(cr.getTsMs()).isEqualTo(100L);
        }

        @Test
        @DisplayName("UPDATE 记录反序列化 — before 和 after 均存在")
        void deserialize_update() {
            Struct before = buildRow(1, "old");
            Struct after = buildRow(1, "new");
            SourceRecord record = buildRecord(before, after, "u", 200L);

            List<ChangeRecord> output = new ArrayList<>();
            PostgresSourceConnector.ChangeRecordDeserializer deserializer =
                    new PostgresSourceConnector.ChangeRecordDeserializer();
            deserializer.deserialize(record, toCollector(output));

            assertThat(output).hasSize(1);
            ChangeRecord cr = output.get(0);
            assertThat(cr.getBefore()).containsEntry("name", "old");
            assertThat(cr.getAfter()).containsEntry("name", "new");
            assertThat(cr.isUpdate()).isTrue();
        }

        @Test
        @DisplayName("DELETE 记录反序列化 — after 为 null")
        void deserialize_delete() {
            Struct before = buildRow(1, "alice");
            SourceRecord record = buildRecord(before, null, "d", 300L);

            List<ChangeRecord> output = new ArrayList<>();
            PostgresSourceConnector.ChangeRecordDeserializer deserializer =
                    new PostgresSourceConnector.ChangeRecordDeserializer();
            deserializer.deserialize(record, toCollector(output));

            assertThat(output).hasSize(1);
            ChangeRecord cr = output.get(0);
            assertThat(cr.getBefore()).containsEntry("id", 1);
            assertThat(cr.getAfter()).isNull();
            assertThat(cr.isDelete()).isTrue();
        }

        @Test
        @DisplayName("tombstone 记录（value=null）— 跳过不输出")
        void deserialize_tombstone_skipped() {
            Map<String, String> sourcePartition = new HashMap<>();
            Map<String, Object> sourceOffset = new HashMap<>();
            SourceRecord record = new SourceRecord(sourcePartition, sourceOffset, "test-topic", 0,
                    null, null);

            List<ChangeRecord> output = new ArrayList<>();
            PostgresSourceConnector.ChangeRecordDeserializer deserializer =
                    new PostgresSourceConnector.ChangeRecordDeserializer();
            deserializer.deserialize(record, toCollector(output));

            assertThat(output).isEmpty();
        }

        @Test
        @DisplayName("getProducedType — 返回 ChangeRecord 类型")
        void getProducedType() {
            PostgresSourceConnector.ChangeRecordDeserializer deserializer =
                    new PostgresSourceConnector.ChangeRecordDeserializer();
            assertThat(deserializer.getProducedType().getTypeClass()).isEqualTo(ChangeRecord.class);
        }
    }

    @Nested
    @DisplayName("PostgresSourceConfig — Logical Replication Slot 配置")
    class SlotConfigTest {

        @Test
        @DisplayName("默认 slotName 为 flink_slot")
        void defaultSlotName() {
            PostgresSourceConfig config = new PostgresSourceConfig();
            assertThat(config.getSlotName()).isEqualTo("flink_slot");
        }

        @Test
        @DisplayName("默认 decodingPlugin 为 PGOUTPUT")
        void defaultDecodingPlugin() {
            PostgresSourceConfig config = new PostgresSourceConfig();
            assertThat(config.getDecodingPlugin()).isEqualTo(PostgresSourceConfig.DecodingPlugin.PGOUTPUT);
        }

        @Test
        @DisplayName("默认 schemaList 包含 public")
        void defaultSchemaList() {
            PostgresSourceConfig config = new PostgresSourceConfig();
            assertThat(config.getSchemaList()).contains("public");
        }

        @Test
        @DisplayName("默认 slotDropOnFinish 为 false")
        void defaultSlotDropOnFinish() {
            PostgresSourceConfig config = new PostgresSourceConfig();
            assertThat(config.isSlotDropOnFinish()).isFalse();
        }

        @Test
        @DisplayName("Builder 设置 slotName 和 plugin")
        void builder_slotAndPlugin() {
            PostgresSourceConfig config = PostgresSourceConfig.builder()
                    .slotName("my_slot")
                    .decodingPlugin(PostgresSourceConfig.DecodingPlugin.DECODERBUFS)
                    .build();
            assertThat(config.getSlotName()).isEqualTo("my_slot");
            assertThat(config.getDecodingPlugin()).isEqualTo(PostgresSourceConfig.DecodingPlugin.DECODERBUFS);
        }

        @Test
        @DisplayName("Builder 设置 slotDropOnFinish")
        void builder_slotDropOnFinish() {
            PostgresSourceConfig config = PostgresSourceConfig.builder()
                    .slotDropOnFinish(true)
                    .build();
            assertThat(config.isSlotDropOnFinish()).isTrue();
        }

        @Test
        @DisplayName("DecodingPlugin.fromCode — 大小写不敏感")
        void decodingPlugin_fromCode_caseInsensitive() {
            assertThat(PostgresSourceConfig.DecodingPlugin.fromCode("PGOUTPUT"))
                    .isEqualTo(PostgresSourceConfig.DecodingPlugin.PGOUTPUT);
            assertThat(PostgresSourceConfig.DecodingPlugin.fromCode("decoderbufs"))
                    .isEqualTo(PostgresSourceConfig.DecodingPlugin.DECODERBUFS);
            assertThat(PostgresSourceConfig.DecodingPlugin.fromCode("WAL2JSON"))
                    .isEqualTo(PostgresSourceConfig.DecodingPlugin.WAL2JSON);
        }

        @Test
        @DisplayName("DecodingPlugin.fromCode — WAL2JSON_R2")
        void decodingPlugin_fromCode_wal2json_r2() {
            assertThat(PostgresSourceConfig.DecodingPlugin.fromCode("wal2json_r2"))
                    .isEqualTo(PostgresSourceConfig.DecodingPlugin.WAL2JSON_R2);
        }

        @Test
        @DisplayName("DecodingPlugin.fromCode — 未知插件抛出异常")
        void decodingPlugin_fromCode_unknown_throws() {
            assertThatThrownBy(() -> PostgresSourceConfig.DecodingPlugin.fromCode("unknown"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("DecodingPlugin.fromCode — null 抛出 NPE")
        void decodingPlugin_fromCode_null_throwsNpe() {
            assertThatThrownBy(() -> PostgresSourceConfig.DecodingPlugin.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("DecodingPlugin.code — 返回正确编码")
        void decodingPlugin_code() {
            assertThat(PostgresSourceConfig.DecodingPlugin.PGOUTPUT.code()).isEqualTo("pgoutput");
            assertThat(PostgresSourceConfig.DecodingPlugin.DECODERBUFS.code()).isEqualTo("decoderbufs");
            assertThat(PostgresSourceConfig.DecodingPlugin.WAL2JSON.code()).isEqualTo("wal2json");
            assertThat(PostgresSourceConfig.DecodingPlugin.WAL2JSON_R2.code()).isEqualTo("wal2json_r2");
        }

        @Test
        @DisplayName("schemaListAsList / tableListAsList — 返回排序后的 List")
        void listAsList_sorted() {
            PostgresSourceConfig config = PostgresSourceConfig.builder()
                    .schemaList("public", "audit", "report")
                    .tableList("public.orders", "public.users")
                    .build();
            assertThat(config.schemaListAsList()).containsExactly("audit", "public", "report");
            assertThat(config.tableListAsList()).containsExactly("public.orders", "public.users");
        }

        @Test
        @DisplayName("equals / hashCode / toString — 正确实现")
        void objectMethods() {
            PostgresSourceConfig c1 = validConfig();
            PostgresSourceConfig c2 = validConfig();
            assertThat(c1).isEqualTo(c2);
            assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
            assertThat(c1.toString()).contains("PostgresSourceConfig");
        }

        @Test
        @DisplayName("equals — 不同 slotName 不相等")
        void equals_differentSlotName() {
            PostgresSourceConfig c1 = validConfig();
            PostgresSourceConfig c2 = validConfig();
            c2.setSlotName("other_slot");
            assertThat(c1).isNotEqualTo(c2);
        }

        @Test
        @DisplayName("equals — null 返回 false")
        void equals_null() {
            assertThat(validConfig()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("全参构造器 — 正确设置所有字段")
        void fullConstructor() {
            SourceConfig base = new SourceConfig();
            base.setName("test");
            PostgresSourceConfig config = new PostgresSourceConfig(
                    base, "my_slot", PostgresSourceConfig.DecodingPlugin.WAL2JSON,
                    java.util.Set.of("public"), java.util.Set.of("public.orders"), true);
            assertThat(config.getSlotName()).isEqualTo("my_slot");
            assertThat(config.getDecodingPlugin()).isEqualTo(PostgresSourceConfig.DecodingPlugin.WAL2JSON);
            assertThat(config.isSlotDropOnFinish()).isTrue();
        }
    }
}
