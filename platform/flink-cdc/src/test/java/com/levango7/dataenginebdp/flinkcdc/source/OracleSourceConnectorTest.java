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
 * {@link OracleSourceConnector} 单元测试。
 *
 * <p>测试覆盖：配置校验、启动模式解析、变更记录转换、LogMiner 配置验证、
 * ChangeRecordDeserializer 反序列化、createSource 实际构建等。</p>
 *
 * @author shuqing-bigdata
 */
class OracleSourceConnectorTest {

    /**
     * 构造一个合法的默认 OracleSourceConfig。
     */
    private OracleSourceConfig validConfig() {
        return OracleSourceConfig.builder()
                .name("test-ora")
                .host("127.0.0.1")
                .port(1521)
                .username("cdc")
                .password("pass")
                .serviceName("ORCLPDB1")
                .schemaList("SHOP")
                .tableList("SHOP.ORDERS")
                .logMinerOption(OracleSourceConfig.LogMinerOption.BOTH)
                .startupMode(SourceConfig.StartupMode.INITIAL)
                .build();
    }

    @Nested
    @DisplayName("validate — 配置校验")
    class ValidateTest {

        @Test
        @DisplayName("合法配置 — 校验通过")
        void validate_validConfig_passes() {
            OracleSourceConnector.validate(validConfig());
        }

        @Test
        @DisplayName("config 为 null — 抛出 NPE")
        void validate_nullConfig_throwsNpe() {
            assertThatThrownBy(() -> OracleSourceConnector.validate(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("base 为 null — 抛出异常")
        void validate_nullBase_throws() {
            OracleSourceConfig config = new OracleSourceConfig();
            config.setBase(null);
            assertThatThrownBy(() -> OracleSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("base");
        }

        @Test
        @DisplayName("host 为空 — 抛出异常")
        void validate_emptyHost_throws() {
            OracleSourceConfig config = validConfig();
            config.getBase().setHost("");
            assertThatThrownBy(() -> OracleSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("host");
        }

        @Test
        @DisplayName("username 为空 — 抛出异常")
        void validate_emptyUsername_throws() {
            OracleSourceConfig config = validConfig();
            config.getBase().setUsername(null);
            assertThatThrownBy(() -> OracleSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("username");
        }

        @Test
        @DisplayName("serviceName 为空 — 抛出异常")
        void validate_emptyServiceName_throws() {
            OracleSourceConfig config = validConfig();
            config.setServiceName("  ");
            assertThatThrownBy(() -> OracleSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("serviceName");
        }

        @Test
        @DisplayName("schemaList 为空 — 抛出异常")
        void validate_emptySchemaList_throws() {
            OracleSourceConfig config = validConfig();
            config.setSchemaList(java.util.Set.of());
            assertThatThrownBy(() -> OracleSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("schemaList");
        }

        @Test
        @DisplayName("tableList 为空 — 抛出异常")
        void validate_emptyTableList_throws() {
            OracleSourceConfig config = validConfig();
            config.setTableList(java.util.Set.of());
            assertThatThrownBy(() -> OracleSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tableList");
        }

        @Test
        @DisplayName("port 越界 — 抛出异常")
        void validate_invalidPort_throws() {
            OracleSourceConfig config = validConfig();
            config.getBase().setPort(0);
            assertThatThrownBy(() -> OracleSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("port");
        }

        @Test
        @DisplayName("logMinerOption 为 null — 抛出异常")
        void validate_nullLogMinerOption_throws() {
            OracleSourceConfig config = validConfig();
            config.setLogMinerOption(null);
            assertThatThrownBy(() -> OracleSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("logMinerOption");
        }

        @Test
        @DisplayName("TIMESTAMP 模式未指定时间戳 — 抛出异常")
        void validate_timestampWithoutMillis_throws() {
            OracleSourceConfig config = validConfig();
            config.getBase().setStartupMode(SourceConfig.StartupMode.TIMESTAMP);
            config.getBase().setStartupTimestampMillis(null);
            assertThatThrownBy(() -> OracleSourceConnector.validate(config))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("startupTimestampMillis");
        }

        @Test
        @DisplayName("TIMESTAMP 模式指定时间戳 — 校验通过")
        void validate_timestampWithMillis_passes() {
            OracleSourceConfig config = validConfig();
            config.getBase().setStartupMode(SourceConfig.StartupMode.TIMESTAMP);
            config.getBase().setStartupTimestampMillis(1700000000000L);
            OracleSourceConnector.validate(config);
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
            StartupOptions options = OracleSourceConnector.resolveStartupOptions(base);
            assertThat(options).isNotNull();
        }

        @Test
        @DisplayName("LATEST_OFFSET 模式 — 返回 latest 选项")
        void resolve_latestOffset() {
            SourceConfig base = validConfig().getBase();
            base.setStartupMode(SourceConfig.StartupMode.LATEST_OFFSET);
            StartupOptions options = OracleSourceConnector.resolveStartupOptions(base);
            assertThat(options).isNotNull();
        }

        @Test
        @DisplayName("TIMESTAMP 模式 — 返回 timestamp 选项")
        void resolve_timestamp() {
            SourceConfig base = validConfig().getBase();
            base.setStartupMode(SourceConfig.StartupMode.TIMESTAMP);
            base.setStartupTimestampMillis(1700000000000L);
            StartupOptions options = OracleSourceConnector.resolveStartupOptions(base);
            assertThat(options).isNotNull();
        }

        @Test
        @DisplayName("SPECIFIC_OFFSET 模式 — 回退到 latest 选项")
        void resolve_specificOffset() {
            SourceConfig base = validConfig().getBase();
            base.setStartupMode(SourceConfig.StartupMode.SPECIFIC_OFFSET);
            StartupOptions options = OracleSourceConnector.resolveStartupOptions(base);
            assertThat(options).isNotNull();
        }
    }

    @Nested
    @DisplayName("toChangeRecord — 变更记录转换")
    class ToChangeRecordTest {

        @Test
        @DisplayName("INSERT 记录 — before 为 null")
        void toChangeRecord_insert() {
            Map<String, Object> after = Map.of("ID", 1, "NAME", "alice");
            ChangeRecord record = OracleSourceConnector.toChangeRecord(null, after, "c", null, 100L);

            assertThat(record.getBefore()).isNull();
            assertThat(record.getAfter()).isEqualTo(after);
            assertThat(record.getOp()).isEqualTo("c");
            assertThat(record.isInsert()).isTrue();
        }

        @Test
        @DisplayName("UPDATE 记录 — before 和 after 均存在")
        void toChangeRecord_update() {
            Map<String, Object> before = Map.of("ID", 1, "NAME", "old");
            Map<String, Object> after = Map.of("ID", 1, "NAME", "new");
            ChangeRecord record = OracleSourceConnector.toChangeRecord(before, after, "u", null, 200L);

            assertThat(record.getBefore()).isEqualTo(before);
            assertThat(record.getAfter()).isEqualTo(after);
            assertThat(record.isUpdate()).isTrue();
        }

        @Test
        @DisplayName("DELETE 记录 — after 为 null")
        void toChangeRecord_delete() {
            Map<String, Object> before = Map.of("ID", 1, "NAME", "alice");
            ChangeRecord record = OracleSourceConnector.toChangeRecord(before, null, "d", null, 300L);

            assertThat(record.getBefore()).isEqualTo(before);
            assertThat(record.getAfter()).isNull();
            assertThat(record.isDelete()).isTrue();
        }

        @Test
        @DisplayName("SNAPSHOT 记录 — op=r")
        void toChangeRecord_snapshot() {
            ChangeRecord record = OracleSourceConnector.toChangeRecord(
                    null, Map.of("ID", 1), "r", Map.of("db", "ORCLPDB1", "schema", "SHOP"), 400L);
            assertThat(record.isSnapshot()).isTrue();
            assertThat(record.opEnum()).isEqualTo(Op.SNAPSHOT);
        }

        @Test
        @DisplayName("包含 source 元数据（含 SCN 位点）")
        void toChangeRecord_withSource() {
            Map<String, Object> source = Map.of(
                    "db", "ORCLPDB1", "schema", "SHOP", "table", "ORDERS",
                    "scn", 12345678L, "commit_scn", 12345680L, "redo_sql", "INSERT INTO ...");
            ChangeRecord record = OracleSourceConnector.toChangeRecord(
                    null, Map.of("ID", 1), "c", source, 500L);

            assertThat(record.getSource()).isEqualTo(source);
            assertThat(record.getSource()).containsKey("scn");
            assertThat(record.getSource()).containsKey("redo_sql");
        }

        @Test
        @DisplayName("tsMs 为 null — 允许")
        void toChangeRecord_nullTsMs() {
            ChangeRecord record = OracleSourceConnector.toChangeRecord(
                    null, Map.of("ID", 1), "c", null, null);
            assertThat(record.getTsMs()).isNull();
        }
    }

    @Nested
    @DisplayName("常量字段名")
    class ConstantsTest {

        @Test
        @DisplayName("Debezium 字段名常量正确")
        void fieldConstants() {
            assertThat(OracleSourceConnector.FIELD_BEFORE).isEqualTo("before");
            assertThat(OracleSourceConnector.FIELD_AFTER).isEqualTo("after");
            assertThat(OracleSourceConnector.FIELD_OP).isEqualTo("op");
            assertThat(OracleSourceConnector.FIELD_SOURCE).isEqualTo("source");
            assertThat(OracleSourceConnector.FIELD_TS_MS).isEqualTo("ts_ms");
        }
    }

    @Nested
    @DisplayName("createSource — Source 构建")
    class CreateSourceTest {

        @Test
        @DisplayName("config 为 null — 抛出 NPE")
        void createSource_nullConfig_throwsNpe() {
            assertThatThrownBy(() -> OracleSourceConnector.createSource(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("合法配置 — 成功构建 OracleIncrementalSource")
        void createSource_validConfig_builds() {
            OracleSourceConfig config = validConfig();
            var source = OracleSourceConnector.createSource(config);
            assertThat(source).isNotNull();
        }

        @Test
        @DisplayName("LATEST_OFFSET 模式 — 成功构建")
        void createSource_latestOffset_builds() {
            OracleSourceConfig config = validConfig();
            config.getBase().setStartupMode(SourceConfig.StartupMode.LATEST_OFFSET);
            var source = OracleSourceConnector.createSource(config);
            assertThat(source).isNotNull();
        }

        @Test
        @DisplayName("TIMESTAMP 模式 — Flink CDC 3.0 不支持，抛出 UnsupportedOperationException")
        void createSource_timestamp_throws() {
            OracleSourceConfig config = validConfig();
            config.getBase().setStartupMode(SourceConfig.StartupMode.TIMESTAMP);
            config.getBase().setStartupTimestampMillis(1700000000000L);
            assertThatThrownBy(() -> OracleSourceConnector.createSource(config))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("ARCHIVED_LOG 选项 — 成功构建")
        void createSource_archivedLog_builds() {
            OracleSourceConfig config = validConfig();
            config.setLogMinerOption(OracleSourceConfig.LogMinerOption.ARCHIVED_LOG);
            var source = OracleSourceConnector.createSource(config);
            assertThat(source).isNotNull();
        }

        @Test
        @DisplayName("ONLINE_LOG 选项 — 成功构建")
        void createSource_onlineLog_builds() {
            OracleSourceConfig config = validConfig();
            config.setLogMinerOption(OracleSourceConfig.LogMinerOption.ONLINE_LOG);
            var source = OracleSourceConnector.createSource(config);
            assertThat(source).isNotNull();
        }

        @Test
        @DisplayName("多 schema 多 table — 成功构建")
        void createSource_multiSchemaTable_builds() {
            OracleSourceConfig config = OracleSourceConfig.builder()
                    .name("test-ora-multi")
                    .host("127.0.0.1").port(1521)
                    .username("cdc").password("pass")
                    .serviceName("ORCLPDB1")
                    .schemaList("SHOP", "AUDIT")
                    .tableList("SHOP.ORDERS", "AUDIT.LOGS")
                    .logMinerOption(OracleSourceConfig.LogMinerOption.BOTH)
                    .startupMode(SourceConfig.StartupMode.INITIAL)
                    .build();
            var source = OracleSourceConnector.createSource(config);
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

        private SourceRecord buildRecord(Struct before, Struct after, String op, Long tsMs) {
            Schema sourceSchema = SchemaBuilder.struct()
                    .field("db", Schema.STRING_SCHEMA)
                    .field("schema", Schema.STRING_SCHEMA)
                    .field("table", Schema.STRING_SCHEMA)
                    .build();
            Struct source = new Struct(sourceSchema);
            source.put("db", "ORCLPDB1");
            source.put("schema", "SHOP");
            source.put("table", "ORDERS");

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
            sourcePartition.put("server", "oracle");
            Map<String, Object> sourceOffset = new HashMap<>();
            sourceOffset.put("scn", 12345678L);
            return new SourceRecord(sourcePartition, sourceOffset, "test-topic",
                    0, valueSchema, value);
        }

        private Struct buildRow(int id, String name) {
            Schema schema = SchemaBuilder.struct()
                    .field("ID", Schema.INT32_SCHEMA)
                    .field("NAME", Schema.STRING_SCHEMA)
                    .build();
            Struct row = new Struct(schema);
            row.put("ID", id);
            row.put("NAME", name);
            return row;
        }

        @Test
        @DisplayName("INSERT 记录反序列化 — before 为 null")
        void deserialize_insert() {
            Struct after = buildRow(1, "alice");
            SourceRecord record = buildRecord(null, after, "c", 100L);

            List<ChangeRecord> output = new ArrayList<>();
            OracleSourceConnector.ChangeRecordDeserializer deserializer =
                    new OracleSourceConnector.ChangeRecordDeserializer();
            deserializer.deserialize(record, toCollector(output));

            assertThat(output).hasSize(1);
            ChangeRecord cr = output.get(0);
            assertThat(cr.getBefore()).isNull();
            assertThat(cr.getAfter()).containsEntry("ID", 1).containsEntry("NAME", "alice");
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
            OracleSourceConnector.ChangeRecordDeserializer deserializer =
                    new OracleSourceConnector.ChangeRecordDeserializer();
            deserializer.deserialize(record, toCollector(output));

            assertThat(output).hasSize(1);
            ChangeRecord cr = output.get(0);
            assertThat(cr.getBefore()).containsEntry("NAME", "old");
            assertThat(cr.getAfter()).containsEntry("NAME", "new");
            assertThat(cr.isUpdate()).isTrue();
        }

        @Test
        @DisplayName("DELETE 记录反序列化 — after 为 null")
        void deserialize_delete() {
            Struct before = buildRow(1, "alice");
            SourceRecord record = buildRecord(before, null, "d", 300L);

            List<ChangeRecord> output = new ArrayList<>();
            OracleSourceConnector.ChangeRecordDeserializer deserializer =
                    new OracleSourceConnector.ChangeRecordDeserializer();
            deserializer.deserialize(record, toCollector(output));

            assertThat(output).hasSize(1);
            ChangeRecord cr = output.get(0);
            assertThat(cr.getBefore()).containsEntry("ID", 1);
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
            OracleSourceConnector.ChangeRecordDeserializer deserializer =
                    new OracleSourceConnector.ChangeRecordDeserializer();
            deserializer.deserialize(record, toCollector(output));

            assertThat(output).isEmpty();
        }

        @Test
        @DisplayName("getProducedType — 返回 ChangeRecord 类型")
        void getProducedType() {
            OracleSourceConnector.ChangeRecordDeserializer deserializer =
                    new OracleSourceConnector.ChangeRecordDeserializer();
            assertThat(deserializer.getProducedType().getTypeClass()).isEqualTo(ChangeRecord.class);
        }
    }

    @Nested
    @DisplayName("OracleSourceConfig — LogMiner 配置")
    class LogMinerConfigTest {

        @Test
        @DisplayName("默认 logMinerOption 为 BOTH")
        void defaultLogMinerOption() {
            OracleSourceConfig config = new OracleSourceConfig();
            assertThat(config.getLogMinerOption()).isEqualTo(OracleSourceConfig.LogMinerOption.BOTH);
        }

        @Test
        @DisplayName("默认 useXStream 为 false")
        void defaultUseXStream() {
            OracleSourceConfig config = new OracleSourceConfig();
            assertThat(config.isUseXStream()).isFalse();
        }

        @Test
        @DisplayName("Builder 设置 logMinerOption 和 useXStream")
        void builder_logMinerAndXStream() {
            OracleSourceConfig config = OracleSourceConfig.builder()
                    .logMinerOption(OracleSourceConfig.LogMinerOption.ARCHIVED_LOG)
                    .useXStream(true)
                    .build();
            assertThat(config.getLogMinerOption()).isEqualTo(OracleSourceConfig.LogMinerOption.ARCHIVED_LOG);
            assertThat(config.isUseXStream()).isTrue();
        }

        @Test
        @DisplayName("LogMinerOption.fromCode — 大小写不敏感")
        void logMinerOption_fromCode_caseInsensitive() {
            assertThat(OracleSourceConfig.LogMinerOption.fromCode("ONLINE-LOG"))
                    .isEqualTo(OracleSourceConfig.LogMinerOption.ONLINE_LOG);
            assertThat(OracleSourceConfig.LogMinerOption.fromCode("archived-log"))
                    .isEqualTo(OracleSourceConfig.LogMinerOption.ARCHIVED_LOG);
            assertThat(OracleSourceConfig.LogMinerOption.fromCode("BOTH"))
                    .isEqualTo(OracleSourceConfig.LogMinerOption.BOTH);
        }

        @Test
        @DisplayName("LogMinerOption.fromCode — 未知选项抛出异常")
        void logMinerOption_fromCode_unknown_throws() {
            assertThatThrownBy(() -> OracleSourceConfig.LogMinerOption.fromCode("unknown"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("LogMinerOption.fromCode — null 抛出 NPE")
        void logMinerOption_fromCode_null_throwsNpe() {
            assertThatThrownBy(() -> OracleSourceConfig.LogMinerOption.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("LogMinerOption.code — 返回正确编码")
        void logMinerOption_code() {
            assertThat(OracleSourceConfig.LogMinerOption.ONLINE_LOG.code()).isEqualTo("online-log");
            assertThat(OracleSourceConfig.LogMinerOption.ARCHIVED_LOG.code()).isEqualTo("archived-log");
            assertThat(OracleSourceConfig.LogMinerOption.BOTH.code()).isEqualTo("both");
        }

        @Test
        @DisplayName("schemaListAsList / tableListAsList — 返回排序后的 List")
        void listAsList_sorted() {
            OracleSourceConfig config = OracleSourceConfig.builder()
                    .schemaList("SHOP", "AUDIT", "REPORT")
                    .tableList("SHOP.ORDERS", "SHOP.USERS")
                    .build();
            assertThat(config.schemaListAsList()).containsExactly("AUDIT", "REPORT", "SHOP");
            assertThat(config.tableListAsList()).containsExactly("SHOP.ORDERS", "SHOP.USERS");
        }

        @Test
        @DisplayName("Builder.database 同时设置 serviceName")
        void builder_databaseSetsServiceName() {
            OracleSourceConfig config = OracleSourceConfig.builder()
                    .database("MYDB")
                    .build();
            assertThat(config.getServiceName()).isEqualTo("MYDB");
            assertThat(config.getBase().getDatabase()).isEqualTo("MYDB");
        }

        @Test
        @DisplayName("Builder.serviceName 同时设置 base.database")
        void builder_serviceNameSetsDatabase() {
            OracleSourceConfig config = OracleSourceConfig.builder()
                    .serviceName("MYDB2")
                    .build();
            assertThat(config.getServiceName()).isEqualTo("MYDB2");
            assertThat(config.getBase().getDatabase()).isEqualTo("MYDB2");
        }

        @Test
        @DisplayName("Builder.startScn 设置起始 SCN")
        void builder_startScn() {
            OracleSourceConfig config = OracleSourceConfig.builder()
                    .startScn(12345678L)
                    .build();
            assertThat(config.getStartScn()).isEqualTo(12345678L);
        }

        @Test
        @DisplayName("equals / hashCode / toString — 正确实现")
        void objectMethods() {
            OracleSourceConfig c1 = validConfig();
            OracleSourceConfig c2 = validConfig();
            assertThat(c1).isEqualTo(c2);
            assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
            assertThat(c1.toString()).contains("OracleSourceConfig");
        }

        @Test
        @DisplayName("equals — 不同 serviceName 不相等")
        void equals_differentServiceName() {
            OracleSourceConfig c1 = validConfig();
            OracleSourceConfig c2 = validConfig();
            c2.setServiceName("OTHER");
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
            OracleSourceConfig config = new OracleSourceConfig(
                    base, "MYDB", java.util.Set.of("SHOP"), java.util.Set.of("SHOP.ORDERS"),
                    OracleSourceConfig.LogMinerOption.ONLINE_LOG, true, 12345L);
            assertThat(config.getServiceName()).isEqualTo("MYDB");
            assertThat(config.getLogMinerOption()).isEqualTo(OracleSourceConfig.LogMinerOption.ONLINE_LOG);
            assertThat(config.isUseXStream()).isTrue();
            assertThat(config.getStartScn()).isEqualTo(12345L);
        }
    }
}
