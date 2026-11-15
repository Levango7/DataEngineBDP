package com.levango7.dataenginebdp.flinkcdc;

import com.levango7.dataenginebdp.flinkcdc.config.CdcYamlConfig;
import com.levango7.dataenginebdp.flinkcdc.sink.SinkConfig;
import com.levango7.dataenginebdp.flinkcdc.source.SourceConfig;
import com.levango7.dataenginebdp.flinkcdc.source.SourceConnectorFactory;
import org.apache.flink.cdc.connectors.postgres.source.PostgresSourceBuilder;
import org.apache.flink.cdc.connectors.mysql.source.MySqlSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 端到端泳道接线测试（2026-11-15 泳道批）：
 * PostgreSQL Source 此前在 CdcFramework 主链不可达（addSourceToEnv 硬编码 MySQL 连接器），
 * 本批改为经 SourceConnectorFactory 按类型分派，并对 Checkpoint 间隔可配化加契约。
 *
 * <p>全真装配例（buildPipeline 走 PostgreSQL source + Iceberg sink 的 attachReal）在
 * Windows 本机跳过（Hadoop LocalFileSystem 权限调用需要 winutils），WSL/CI 实跑。</p>
 */
class LaneSourceDispatchTest {

    @Test
    @DisplayName("工厂按类型分派：POSTGRESQL 返回 PostgresIncrementalSource 而非 MySqlSource")
    void factoryDispatchesByType() {
        SourceConfig pg = new SourceConfig();
        pg.setName("lane-pg");
        pg.setType(SourceConfig.SourceType.POSTGRESQL);
        pg.setHost("127.0.0.1");
        pg.setPort(5433);
        pg.setUsername("u");
        pg.setPassword("p");
        pg.setDatabase("bdp_lane");
        pg.setTable("public.mv_sales_lane");
        var source = SourceConnectorFactory.createSource(pg);
        assertInstanceOf(PostgresSourceBuilder.PostgresIncrementalSource.class, source);
        assertFalse(source instanceof MySqlSource, "PG 配置不得落到 MySQL 连接器");
    }

    @Test
    @DisplayName("buildPipeline 全真装配：POSTGRESQL source + ICEBERG sink（attachReal 真表加载）+ 可配 checkpoint")
    void buildPipelineWithPostgresAndIceberg(@TempDir Path warehouseDir) {
        assumeTrue(localFsWritable(),
                "Windows 本机无 winutils ⇒ 全真装配例由 WSL/CI 执行");
        String warehouseUri = warehouseDir.toUri().toString();

        // 真实 Iceberg 表（attachReal 构建期 fail-fast 加载的前提）
        Map<String, String> catalogProps = new HashMap<>();
        catalogProps.put("warehouse", warehouseUri);
        org.apache.iceberg.catalog.Catalog catalog = org.apache.iceberg.flink.CatalogLoader.hadoop(
                "lane", new org.apache.hadoop.conf.Configuration(), catalogProps).loadCatalog();
        TableIdentifier identifier = TableIdentifier.of("dw", "mv_sales_lane");
        catalog.createTable(identifier, new org.apache.iceberg.Schema(
                org.apache.iceberg.types.Types.NestedField.required(1, "id", org.apache.iceberg.types.Types.LongType.get()),
                org.apache.iceberg.types.Types.NestedField.optional(2, "name", org.apache.iceberg.types.Types.StringType.get()),
                org.apache.iceberg.types.Types.NestedField.optional(3, "amount", org.apache.iceberg.types.Types.DoubleType.get()),
                org.apache.iceberg.types.Types.NestedField.optional(4, "src_ts", org.apache.iceberg.types.Types.TimestampType.withZone())));
        // UPSERT 前提：identifier 字段（attachReal 的 fail-fast 校验会点名）
        catalog.loadTable(identifier).updateSchema().setIdentifierFields("id").commit();

        SourceConfig pg = new SourceConfig();
        pg.setName("lane-pg");
        pg.setType(SourceConfig.SourceType.POSTGRESQL);
        pg.setHost("127.0.0.1");
        pg.setPort(5433);
        pg.setUsername("u");
        pg.setPassword("p");
        pg.setDatabase("bdp_lane");
        pg.setTable("public.mv_sales_lane");

        SinkConfig iceberg = new SinkConfig();
        iceberg.setName("lane-iceberg");
        iceberg.setType(SinkConfig.SinkType.ICEBERG);
        iceberg.property("catalog-type", "hadoop");
        iceberg.property("warehouse", warehouseUri);
        iceberg.property("database", "dw");
        iceberg.property("table", "mv_sales_lane");
        iceberg.property("primary-keys", "id");

        CdcFramework framework = CdcFramework.builder()
                .jobName("lane-pg-iceberg")
                .parallelism(1)
                .checkpointIntervalMs(5000L)
                .build();
        framework.addSource(pg);
        framework.addSink(iceberg);
        // 走真实 createEnvironment（checkpoint 可配生效处）再构建拓扑不执行：
        // Postgres source 构建期不连库；Iceberg sink 构建期真加载表（fail-fast 契约）
        StreamExecutionEnvironment env = framework.createEnvironment();
        framework.buildPipeline(env);
        assertTrue(env.getCheckpointConfig().isCheckpointingEnabled(), "checkpoint 必须开启");
        assertEquals(5000L, env.getCheckpointConfig().getCheckpointInterval(),
                "checkpoint 间隔须等于 builder 可配值");
    }

    @Test
    @DisplayName("yaml job.checkpointIntervalMs：默认 60000，可显式覆盖，非正值拒绝")
    void yamlCheckpointIntervalParsing() {
        String withInterval = """
                job:
                  name: lane
                  parallelism: 1
                  checkpointIntervalMs: 5000
                sources: []
                sinks: []
                """;
        CdcYamlConfig cfg = CdcYamlConfig.load(new ByteArrayInputStream(
                withInterval.getBytes(StandardCharsets.UTF_8)));
        assertEquals(5000L, cfg.getCheckpointIntervalMs());

        String noInterval = """
                job:
                  name: lane
                sources: []
                sinks: []
                """;
        CdcYamlConfig cfg2 = CdcYamlConfig.load(new ByteArrayInputStream(
                noInterval.getBytes(StandardCharsets.UTF_8)));
        assertEquals(60000L, cfg2.getCheckpointIntervalMs(), "缺省须保持既有 60s 行为");

        String bad = """
                job:
                  name: lane
                  checkpointIntervalMs: 0
                sources: []
                sinks: []
                """;
        assertThrows(IllegalArgumentException.class, () -> CdcYamlConfig.load(
                new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8))));
    }

    /** Linux/Mac（WSL 与 CI）真实执行；Windows 本机须有 winutils 才执行。 */
    private static boolean localFsWritable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("windows")) {
            return true;
        }
        String hadoopHome = System.getenv("HADOOP_HOME");
        return hadoopHome != null && new java.io.File(hadoopHome, "bin/winutils.exe").exists();
    }
}
