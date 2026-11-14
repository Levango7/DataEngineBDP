package com.levango7.dataenginebdp.flinkcdc;

import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord;
import com.levango7.dataenginebdp.flinkcdc.sink.IcebergSinkConnector;
import com.levango7.dataenginebdp.flinkcdc.sink.SinkConfig;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.flink.CatalogLoader;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A1 终段本地真写验证：HadoopCatalog（本地 file:// 仓库）+ 本地 mini Flink 作业，
 * 走 connector.attachReal 的真实写入路径（ChangeRecord → RowData → Iceberg sink →
 * checkpoint 提交），再用 iceberg Generics 读回断言行数与内容。
 *
 * <p>这是「真实 Iceberg SDK 写入」的最强验证。**Windows 本机跳过**：Hadoop
 * LocalFileSystem 的权限调用需要 winutils.exe（本机无）⇒ assumeTrue 诚实跳过；
 * Linux/CI（ubuntu runner，hadoop-client-runtime 在 test classpath）真实执行。</p>
 */
class RealIcebergWriteLocalTest {

    private static final Schema MV_SCHEMA = new Schema(
            Types.NestedField.required(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "name", Types.StringType.get()),
            Types.NestedField.optional(3, "amount", Types.DoubleType.get()));

    @TempDir
    Path warehouseDir;

    @Test
    @DisplayName("本地 HadoopCatalog + mini Flink：CDC 记录经 attachReal 真写 Iceberg 并可读回")
    void realWriteViaAttachReal() throws Exception {
        assumeTrue(localFsWritable(),
                "Windows 本机无 winutils（Hadoop LocalFileSystem 权限调用不可用）⇒ 真写验证由 CI Ubuntu 执行");
        String warehouseUri = warehouseDir.toUri().toString();

        // 1. SinkConfig（HADOOP catalog + 本地 warehouse）
        SinkConfig config = new SinkConfig();
        config.setName("a1-final-local");
        config.setType(SinkConfig.SinkType.ICEBERG);
        config.property("catalog-type", "hadoop");
        config.property("warehouse", warehouseUri);
        config.property("database", "dw");
        config.property("table", "mv_sales");
        config.property("primary-keys", "id");
        IcebergSinkConnector connector = CdcFramework.buildIcebergConnector(config);

        // 2. 真实 catalog 建表（带 identifier 字段，UPSERT 语义前提）
        org.apache.hadoop.conf.Configuration hadoopConf = new org.apache.hadoop.conf.Configuration();
        Map<String, String> catalogProps = new HashMap<>();
        catalogProps.put("warehouse", warehouseUri);
        CatalogLoader catalogLoader = CatalogLoader.hadoop("a1-final-local", hadoopConf, catalogProps);
        org.apache.iceberg.catalog.Catalog catalog = catalogLoader.loadCatalog();
        TableIdentifier identifier = TableIdentifier.of("dw", "mv_sales");
        catalog.createTable(identifier, MV_SCHEMA);
        // identifier 字段（主键）提交，UPSERT 前提
        catalog.loadTable(identifier).updateSchema().setIdentifierFields("id").commit();

        // 3. 本地 mini Flink：三条 CDC 记录经 attachReal 真写（先插 id=2 再等值删除，快照应只剩 id=1）
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.enableCheckpointing(300);
        DataStream<ChangeRecord> source = env.fromElements(
                cdcRecord("c", Map.<String, Object>of("id", 1L, "name", "alpha", "amount", 9.5), null),
                cdcRecord("c", Map.<String, Object>of("id", 2L, "name", "beta"), null),
                cdcRecord("d", null, Map.<String, Object>of("id", 2L, "name", "beta")));
        connector.attachReal(source);
        env.execute("a1-final-real-write");

        // 4. 读回断言（refresh 轮询，容忍 checkpoint 提交延迟）
        org.apache.iceberg.Table table = catalog.loadTable(identifier);
        long seen = -1;
        for (int attempt = 0; attempt < 20; attempt++) {
            table.refresh();
            seen = countRows(table);
            if (seen == 1) {
                break;
            }
            Thread.sleep(1000);
        }
        assertEquals(1, seen, "UPSERT 后应只剩 id=1 一行（先插 2 行、等值删除 1 行）");
        for (Record row : IcebergGenerics.read(table).build()) {
            assertEquals(1L, row.getField("id"));
            assertEquals("alpha", row.getField("name"));
        }
    }

    private static ChangeRecord cdcRecord(String op, Map<String, Object> after, Map<String, Object> before) {
        ChangeRecord record = new ChangeRecord();
        record.setOp(op);
        if (after != null) {
            record.setAfter(after);
        }
        if (before != null) {
            record.setBefore(before);
        }
        return record;
    }

    /** Linux/Mac（CI 与真机）真实执行；Windows 本机须有 winutils 才执行。 */
    private static boolean localFsWritable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("windows")) {
            return true;
        }
        String hadoopHome = System.getenv("HADOOP_HOME");
        return hadoopHome != null && new File(hadoopHome, "bin/winutils.exe").exists();
    }

    private static long countRows(org.apache.iceberg.Table table) {
        long count = 0;
        try (org.apache.iceberg.io.CloseableIterable<Record> rows =
                     IcebergGenerics.read(table).build()) {
            for (Record ignored : rows) {
                count++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Iceberg 读回失败: " + e.getMessage(), e);
        }
        return count;
    }
}
