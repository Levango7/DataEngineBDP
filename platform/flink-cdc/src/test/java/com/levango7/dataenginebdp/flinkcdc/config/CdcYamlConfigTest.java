package com.levango7.dataenginebdp.flinkcdc.config;

import com.levango7.dataenginebdp.flinkcdc.sink.SinkConfig;
import com.levango7.dataenginebdp.flinkcdc.source.SourceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CdcYamlConfig} 单元测试。
 *
 * @author shuqing-bigdata
 */
class CdcYamlConfigTest {

    /**
     * 构造一个完整的 YAML 配置 Map（用于 parse 测试）。
     */
    private Map<String, Object> fullConfigMap() {
        Map<String, Object> root = new HashMap<>();

        Map<String, Object> job = new HashMap<>();
        job.put("name", "mysql-to-kafka");
        job.put("parallelism", 2);
        root.put("job", job);

        Map<String, Object> source = new HashMap<>();
        source.put("name", "mysql-orders");
        source.put("type", "MYSQL");
        source.put("host", "127.0.0.1");
        source.put("port", 3306);
        source.put("username", "cdc");
        source.put("password", "pass");
        source.put("database", "shop");
        source.put("table", "shop.orders");
        source.put("serverId", 5400);
        source.put("startupMode", "initial");
        source.put("parallelism", 2);
        root.put("sources", List.of(source));

        Map<String, Object> sink = new HashMap<>();
        sink.put("name", "kafka-orders");
        sink.put("type", "KAFKA");
        sink.put("host", "127.0.0.1");
        sink.put("port", 9092);
        sink.put("topic", "cdc.orders");
        sink.put("writeMode", "upsert");
        sink.put("format", "debezium-json");
        sink.put("primaryKey", "id");
        Map<String, Object> props = new HashMap<>();
        props.put("acks", "1");
        sink.put("properties", props);
        root.put("sinks", List.of(sink));

        return root;
    }

    @Nested
    @DisplayName("parse — 从 Map 解析")
    class ParseTest {

        @Test
        @DisplayName("完整配置 — 正确解析所有字段")
        void parse_fullConfig() {
            CdcYamlConfig config = CdcYamlConfig.parse(fullConfigMap());

            assertThat(config.getJobName()).isEqualTo("mysql-to-kafka");
            assertThat(config.getParallelism()).isEqualTo(2);

            assertThat(config.getSources()).hasSize(1);
            SourceConfig source = config.getSources().get(0);
            assertThat(source.getName()).isEqualTo("mysql-orders");
            assertThat(source.getType()).isEqualTo(SourceConfig.SourceType.MYSQL);
            assertThat(source.getHost()).isEqualTo("127.0.0.1");
            assertThat(source.getPort()).isEqualTo(3306);
            assertThat(source.getUsername()).isEqualTo("cdc");
            assertThat(source.getDatabase()).isEqualTo("shop");
            assertThat(source.getTable()).isEqualTo("shop.orders");
            assertThat(source.getServerId()).isEqualTo(5400L);
            assertThat(source.getStartupMode()).isEqualTo(SourceConfig.StartupMode.INITIAL);
            assertThat(source.getParallelism()).isEqualTo(2);

            assertThat(config.getSinks()).hasSize(1);
            SinkConfig sink = config.getSinks().get(0);
            assertThat(sink.getName()).isEqualTo("kafka-orders");
            assertThat(sink.getType()).isEqualTo(SinkConfig.SinkType.KAFKA);
            assertThat(sink.getHost()).isEqualTo("127.0.0.1");
            assertThat(sink.getPort()).isEqualTo(9092);
            assertThat(sink.getTopic()).isEqualTo("cdc.orders");
            assertThat(sink.getWriteMode()).isEqualTo(SinkConfig.WriteMode.UPSERT);
            assertThat(sink.getFormat()).isEqualTo("debezium-json");
            assertThat(sink.getPrimaryKey()).isEqualTo("id");
            assertThat(sink.property("acks")).isEqualTo("1");
        }

        @Test
        @DisplayName("空 job 段 — 使用默认值")
        void parse_emptyJob_usesDefaults() {
            CdcYamlConfig config = CdcYamlConfig.parse(new HashMap<>());
            assertThat(config.getJobName()).isEqualTo("flink-cdc-job");
            assertThat(config.getParallelism()).isEqualTo(1);
            assertThat(config.getSources()).isEmpty();
            assertThat(config.getSinks()).isEmpty();
        }

        @Test
        @DisplayName("多 Source 多 Sink — 全部解析")
        void parse_multipleSourcesAndSinks() {
            Map<String, Object> root = new HashMap<>();
            Map<String, Object> src1 = new HashMap<>(Map.of("name", "src1", "type", "MYSQL",
                    "host", "h1", "username", "u", "database", "d", "table", "d.t1"));
            Map<String, Object> src2 = new HashMap<>(Map.of("name", "src2", "type", "POSTGRESQL",
                    "host", "h2", "username", "u", "database", "d", "table", "d.t2",
                    "port", 5432));
            root.put("sources", List.of(src1, src2));

            CdcYamlConfig config = CdcYamlConfig.parse(root);
            assertThat(config.getSources()).hasSize(2);
            assertThat(config.getSources().get(0).getType()).isEqualTo(SourceConfig.SourceType.MYSQL);
            assertThat(config.getSources().get(1).getType()).isEqualTo(SourceConfig.SourceType.POSTGRESQL);
            assertThat(config.getSources().get(1).getPort()).isEqualTo(5432);
        }

        @Test
        @DisplayName("tableFilters — 正确解析为 Set")
        void parse_tableFilters() {
            Map<String, Object> root = new HashMap<>();
            Map<String, Object> src = new HashMap<>();
            src.put("name", "src");
            src.put("type", "MYSQL");
            src.put("host", "h");
            src.put("username", "u");
            src.put("database", "d");
            src.put("table", "d.t");
            src.put("tableFilters", List.of("d.t1", "d.t2", "d.t3"));
            root.put("sources", List.of(src));

            CdcYamlConfig config = CdcYamlConfig.parse(root);
            assertThat(config.getSources().get(0).getTableFilters())
                    .containsExactlyInAnyOrder("d.t1", "d.t2", "d.t3");
        }

        @Test
        @DisplayName("startupMode latest-offset — 正确解析")
        void parse_latestOffsetMode() {
            Map<String, Object> root = new HashMap<>();
            Map<String, Object> src = new HashMap<>();
            src.put("name", "src");
            src.put("type", "MYSQL");
            src.put("host", "h");
            src.put("username", "u");
            src.put("database", "d");
            src.put("table", "d.t");
            src.put("startupMode", "latest-offset");
            root.put("sources", List.of(src));

            CdcYamlConfig config = CdcYamlConfig.parse(root);
            assertThat(config.getSources().get(0).getStartupMode())
                    .isEqualTo(SourceConfig.StartupMode.LATEST_OFFSET);
        }

        @Test
        @DisplayName("null root — 抛出 NPE")
        void parse_nullRoot_throwsNpe() {
            assertThatThrownBy(() -> CdcYamlConfig.parse(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("load — 从输入流/文件加载")
    class LoadTest {

        @Test
        @DisplayName("从 InputStream 加载 — 正确解析")
        void load_fromInputStream() {
            String yaml = """
                    job:
                      name: test-job
                      parallelism: 4
                    sources:
                      - name: src1
                        type: MYSQL
                        host: localhost
                        port: 3306
                        username: root
                        password: secret
                        database: testdb
                        table: testdb.users
                        serverId: 100
                        startupMode: initial
                    sinks:
                      - name: sink1
                        type: KAFKA
                        host: kafka
                        port: 9092
                        topic: users
                        writeMode: append-only
                        format: json
                    """;
            ByteArrayInputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));

            CdcYamlConfig config = CdcYamlConfig.load(in);
            assertThat(config.getJobName()).isEqualTo("test-job");
            assertThat(config.getParallelism()).isEqualTo(4);
            assertThat(config.getSources()).hasSize(1);
            assertThat(config.getSinks()).hasSize(1);
            assertThat(config.getSources().get(0).getServerId()).isEqualTo(100L);
            assertThat(config.getSinks().get(0).getWriteMode()).isEqualTo(SinkConfig.WriteMode.APPEND_ONLY);
        }

        @Test
        @DisplayName("从文件加载 — 正确解析")
        void load_fromFile() throws IOException {
            String yaml = """
                    job:
                      name: file-job
                    sources:
                      - name: src
                        type: MYSQL
                        host: h
                        username: u
                        database: d
                        table: d.t
                    sinks:
                      - name: sink
                        type: KAFKA
                        host: h
                        topic: t
                    """;
            Path tempFile = Files.createTempFile("cdc-test", ".yaml");
            Files.writeString(tempFile, yaml, StandardCharsets.UTF_8);

            try {
                CdcYamlConfig config = CdcYamlConfig.load(tempFile);
                assertThat(config.getJobName()).isEqualTo("file-job");
                assertThat(config.getSources()).hasSize(1);
                assertThat(config.getSinks()).hasSize(1);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("空 YAML — 抛出 IllegalArgumentException")
        void load_emptyYaml_throws() {
            ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
            assertThatThrownBy(() -> CdcYamlConfig.load(in))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null InputStream — 抛出 NPE")
        void load_nullStream_throwsNpe() {
            assertThatThrownBy(() -> CdcYamlConfig.load((java.io.InputStream) null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("getter/setter")
    class AccessorTest {

        @Test
        @DisplayName("setSources/setSinks — 可更新列表")
        void setSourcesAndSinks() {
            CdcYamlConfig config = new CdcYamlConfig();
            config.setJobName("new-job");
            config.setParallelism(8);

            SourceConfig src = new SourceConfig();
            src.setName("s");
            config.setSources(List.of(src));

            SinkConfig sink = new SinkConfig();
            sink.setName("k");
            config.setSinks(List.of(sink));

            assertThat(config.getJobName()).isEqualTo("new-job");
            assertThat(config.getParallelism()).isEqualTo(8);
            assertThat(config.getSources()).hasSize(1);
            assertThat(config.getSinks()).hasSize(1);
        }

        @Test
        @DisplayName("setSources(null) — 初始化为空列表")
        void setSourcesNull_returnsEmptyList() {
            CdcYamlConfig config = new CdcYamlConfig();
            config.setSources(null);
            assertThat(config.getSources()).isEmpty();
        }

        @Test
        @DisplayName("toString — 包含作业名")
        void toString_containsJobName() {
            CdcYamlConfig config = new CdcYamlConfig();
            config.setJobName("my-job");
            assertThat(config.toString()).contains("my-job");
        }
    }
}