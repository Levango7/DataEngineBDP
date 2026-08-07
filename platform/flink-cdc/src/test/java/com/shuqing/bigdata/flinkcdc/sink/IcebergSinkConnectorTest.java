package com.shuqing.bigdata.flinkcdc.sink;

import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IcebergSinkConnector} 单元测试。
 *
 * <p>覆盖行级 UPSERT 动作解析、合并优化触发条件、Schema 演化分析、
 * 配置校验、Builder、属性生成等核心逻辑，目标覆盖率 ≥80%。</p>
 *
 * @author shuqing-bigdata
 */
class IcebergSinkConnectorTest {

    /** 构造一个最小可用的 UPSERT 连接器。 */
    private IcebergSinkConnector upsertConnector() {
        return IcebergSinkConnector.builder()
                .catalogName("rest")
                .catalogType(IcebergSinkConnector.CatalogType.REST)
                .catalogUri("http://iceberg-rest:8181")
                .warehouse("s3://shuqing-warehouse/")
                .database("ods")
                .table("orders")
                .primaryKeys("order_id")
                .writeMode(IcebergSinkConnector.WriteMode.UPSERT)
                .build();
    }

    /** 构造一个 APPEND_ONLY 连接器。 */
    private IcebergSinkConnector appendConnector() {
        return IcebergSinkConnector.builder()
                .catalogName("hive")
                .catalogType(IcebergSinkConnector.CatalogType.HIVE)
                .warehouse("hdfs:///warehouse")
                .database("ods")
                .table("events")
                .writeMode(IcebergSinkConnector.WriteMode.APPEND_ONLY)
                .distributionMode(IcebergSinkConnector.DistributionMode.NONE)
                .build();
    }

    private ChangeRecord record(String op, Map<String, Object> before, Map<String, Object> after) {
        return new ChangeRecord(before, after, op, null, 100L);
    }

    // ===== 枚举测试 =====

    @Nested
    @DisplayName("CatalogType 枚举")
    class CatalogTypeTest {

        @Test
        @DisplayName("fromCode — 正确解析所有类型")
        void fromCode_allTypes() {
            assertThat(IcebergSinkConnector.CatalogType.fromCode("hive"))
                    .isEqualTo(IcebergSinkConnector.CatalogType.HIVE);
            assertThat(IcebergSinkConnector.CatalogType.fromCode("rest"))
                    .isEqualTo(IcebergSinkConnector.CatalogType.REST);
            assertThat(IcebergSinkConnector.CatalogType.fromCode("hadoop"))
                    .isEqualTo(IcebergSinkConnector.CatalogType.HADOOP);
            assertThat(IcebergSinkConnector.CatalogType.fromCode("jdbc"))
                    .isEqualTo(IcebergSinkConnector.CatalogType.JDBC);
        }

        @Test
        @DisplayName("fromCode — 大小写不敏感 + 下划线转横线")
        void fromCode_caseInsensitive() {
            assertThat(IcebergSinkConnector.CatalogType.fromCode("REST"))
                    .isEqualTo(IcebergSinkConnector.CatalogType.REST);
            assertThat(IcebergSinkConnector.CatalogType.fromCode("Hive"))
                    .isEqualTo(IcebergSinkConnector.CatalogType.HIVE);
        }

        @Test
        @DisplayName("fromCode — null 抛出 NPE")
        void fromCode_null() {
            assertThatThrownBy(() -> IcebergSinkConnector.CatalogType.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("fromCode — 未知类型抛出异常")
        void fromCode_unknown() {
            assertThatThrownBy(() -> IcebergSinkConnector.CatalogType.fromCode("glue"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("code — 返回正确编码")
        void code() {
            assertThat(IcebergSinkConnector.CatalogType.REST.code()).isEqualTo("rest");
            assertThat(IcebergSinkConnector.CatalogType.HIVE.code()).isEqualTo("hive");
        }
    }

    @Nested
    @DisplayName("DistributionMode 枚举")
    class DistributionModeTest {

        @Test
        @DisplayName("fromCode — 正确解析所有模式")
        void fromCode_all() {
            assertThat(IcebergSinkConnector.DistributionMode.fromCode("none"))
                    .isEqualTo(IcebergSinkConnector.DistributionMode.NONE);
            assertThat(IcebergSinkConnector.DistributionMode.fromCode("hash"))
                    .isEqualTo(IcebergSinkConnector.DistributionMode.HASH);
            assertThat(IcebergSinkConnector.DistributionMode.fromCode("range"))
                    .isEqualTo(IcebergSinkConnector.DistributionMode.RANGE);
        }

        @Test
        @DisplayName("fromCode — 大小写不敏感")
        void fromCode_caseInsensitive() {
            assertThat(IcebergSinkConnector.DistributionMode.fromCode("HASH"))
                    .isEqualTo(IcebergSinkConnector.DistributionMode.HASH);
        }

        @Test
        @DisplayName("fromCode — null 抛出 NPE")
        void fromCode_null() {
            assertThatThrownBy(() -> IcebergSinkConnector.DistributionMode.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("fromCode — 未知抛出异常")
        void fromCode_unknown() {
            assertThatThrownBy(() -> IcebergSinkConnector.DistributionMode.fromCode("random"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("code — 返回正确编码")
        void code() {
            assertThat(IcebergSinkConnector.DistributionMode.HASH.code()).isEqualTo("hash");
        }
    }

    @Nested
    @DisplayName("WriteMode 枚举")
    class WriteModeTest {

        @Test
        @DisplayName("fromCode — 正确解析所有模式")
        void fromCode_all() {
            assertThat(IcebergSinkConnector.WriteMode.fromCode("append-only"))
                    .isEqualTo(IcebergSinkConnector.WriteMode.APPEND_ONLY);
            assertThat(IcebergSinkConnector.WriteMode.fromCode("upsert"))
                    .isEqualTo(IcebergSinkConnector.WriteMode.UPSERT);
        }

        @Test
        @DisplayName("fromCode — 大小写不敏感")
        void fromCode_caseInsensitive() {
            assertThat(IcebergSinkConnector.WriteMode.fromCode("UPSERT"))
                    .isEqualTo(IcebergSinkConnector.WriteMode.UPSERT);
        }

        @Test
        @DisplayName("fromCode — null 抛出 NPE")
        void fromCode_null() {
            assertThatThrownBy(() -> IcebergSinkConnector.WriteMode.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("fromCode — 未知抛出异常")
        void fromCode_unknown() {
            assertThatThrownBy(() -> IcebergSinkConnector.WriteMode.fromCode("overwrite"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("code — 返回正确编码")
        void code() {
            assertThat(IcebergSinkConnector.WriteMode.UPSERT.code()).isEqualTo("upsert");
            assertThat(IcebergSinkConnector.WriteMode.APPEND_ONLY.code()).isEqualTo("append-only");
        }
    }

    @Nested
    @DisplayName("CompactionTrigger 枚举")
    class CompactionTriggerTest {

        @Test
        @DisplayName("fromCode — 正确解析所有策略")
        void fromCode_all() {
            assertThat(IcebergSinkConnector.CompactionTrigger.fromCode("none"))
                    .isEqualTo(IcebergSinkConnector.CompactionTrigger.NONE);
            assertThat(IcebergSinkConnector.CompactionTrigger.fromCode("after-checkpoint"))
                    .isEqualTo(IcebergSinkConnector.CompactionTrigger.AFTER_CHECKPOINT);
            assertThat(IcebergSinkConnector.CompactionTrigger.fromCode("by-file-count"))
                    .isEqualTo(IcebergSinkConnector.CompactionTrigger.BY_FILE_COUNT);
            assertThat(IcebergSinkConnector.CompactionTrigger.fromCode("by-file-size"))
                    .isEqualTo(IcebergSinkConnector.CompactionTrigger.BY_FILE_SIZE);
            assertThat(IcebergSinkConnector.CompactionTrigger.fromCode("hybrid"))
                    .isEqualTo(IcebergSinkConnector.CompactionTrigger.HYBRID);
        }

        @Test
        @DisplayName("fromCode — 大小写不敏感")
        void fromCode_caseInsensitive() {
            assertThat(IcebergSinkConnector.CompactionTrigger.fromCode("HYBRID"))
                    .isEqualTo(IcebergSinkConnector.CompactionTrigger.HYBRID);
        }

        @Test
        @DisplayName("fromCode — null 抛出 NPE")
        void fromCode_null() {
            assertThatThrownBy(() -> IcebergSinkConnector.CompactionTrigger.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("fromCode — 未知抛出异常")
        void fromCode_unknown() {
            assertThatThrownBy(() -> IcebergSinkConnector.CompactionTrigger.fromCode("cron"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("code — 返回正确编码")
        void code() {
            assertThat(IcebergSinkConnector.CompactionTrigger.HYBRID.code()).isEqualTo("hybrid");
        }
    }

    @Nested
    @DisplayName("SchemaEvolutionMode 枚举")
    class SchemaEvolutionModeTest {

        @Test
        @DisplayName("fromCode — 正确解析所有模式")
        void fromCode_all() {
            assertThat(IcebergSinkConnector.SchemaEvolutionMode.fromCode("off"))
                    .isEqualTo(IcebergSinkConnector.SchemaEvolutionMode.OFF);
            assertThat(IcebergSinkConnector.SchemaEvolutionMode.fromCode("auto"))
                    .isEqualTo(IcebergSinkConnector.SchemaEvolutionMode.AUTO);
            assertThat(IcebergSinkConnector.SchemaEvolutionMode.fromCode("pause-on-incompatible"))
                    .isEqualTo(IcebergSinkConnector.SchemaEvolutionMode.PAUSE_ON_INCOMPATIBLE);
        }

        @Test
        @DisplayName("fromCode — 大小写不敏感")
        void fromCode_caseInsensitive() {
            assertThat(IcebergSinkConnector.SchemaEvolutionMode.fromCode("AUTO"))
                    .isEqualTo(IcebergSinkConnector.SchemaEvolutionMode.AUTO);
        }

        @Test
        @DisplayName("fromCode — null 抛出 NPE")
        void fromCode_null() {
            assertThatThrownBy(() -> IcebergSinkConnector.SchemaEvolutionMode.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("fromCode — 未知抛出异常")
        void fromCode_unknown() {
            assertThatThrownBy(() -> IcebergSinkConnector.SchemaEvolutionMode.fromCode("strict"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("code — 返回正确编码")
        void code() {
            assertThat(IcebergSinkConnector.SchemaEvolutionMode.AUTO.code()).isEqualTo("auto");
        }
    }

    // ===== Builder 测试 =====

    @Nested
    @DisplayName("Builder 配置")
    class BuilderTest {

        @Test
        @DisplayName("默认配置 — V2 + UPSERT + HASH")
        void defaults() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("orders")
                    .primaryKeys("id")
                    .catalogUri("http://rest:8181")
                    .build();

            assertThat(c.getCatalogName()).isEqualTo("rest");
            assertThat(c.getCatalogType()).isEqualTo(IcebergSinkConnector.CatalogType.REST);
            assertThat(c.getFormatVersion()).isEqualTo(2);
            assertThat(c.getWriteMode()).isEqualTo(IcebergSinkConnector.WriteMode.UPSERT);
            assertThat(c.getDistributionMode()).isEqualTo(IcebergSinkConnector.DistributionMode.HASH);
            assertThat(c.getFileFormat()).isEqualTo("parquet");
            assertThat(c.getMicroBatchSize()).isEqualTo(1000);
            assertThat(c.getCompactionTrigger())
                    .isEqualTo(IcebergSinkConnector.CompactionTrigger.BY_FILE_COUNT);
            assertThat(c.getSchemaEvolutionMode())
                    .isEqualTo(IcebergSinkConnector.SchemaEvolutionMode.AUTO);
            assertThat(c.isIncrementalCommit()).isFalse();
        }

        @Test
        @DisplayName("自定义配置 — 全参数生效")
        void customConfig() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .catalogName("my-hive")
                    .catalogType(IcebergSinkConnector.CatalogType.HIVE)
                    .warehouse("hdfs:///wh")
                    .database("dwd")
                    .table("orders")
                    .primaryKeys("order_id", "shop_id")
                    .partitionKeys("dt", "region")
                    .formatVersion(2)
                    .writeMode(IcebergSinkConnector.WriteMode.UPSERT)
                    .distributionMode(IcebergSinkConnector.DistributionMode.HASH)
                    .fileFormat("orc")
                    .microBatchSize(500)
                    .incrementalCommit()
                    .compaction(IcebergSinkConnector.CompactionTrigger.HYBRID, 100, 256L * 1024 * 1024)
                    .schemaEvolution(IcebergSinkConnector.SchemaEvolutionMode.PAUSE_ON_INCOMPATIBLE)
                    .catalogProperty("s3.endpoint", "http://minio:9000")
                    .catalogProperty("s3.access-key", "admin")
                    .build();

            assertThat(c.getCatalogName()).isEqualTo("my-hive");
            assertThat(c.getCatalogType()).isEqualTo(IcebergSinkConnector.CatalogType.HIVE);
            assertThat(c.getPrimaryKeys()).containsExactly("order_id", "shop_id");
            assertThat(c.getPartitionKeys()).containsExactly("dt", "region");
            assertThat(c.getFileFormat()).isEqualTo("orc");
            assertThat(c.getMicroBatchSize()).isEqualTo(500);
            assertThat(c.isIncrementalCommit()).isTrue();
            assertThat(c.getCompactionTrigger()).isEqualTo(IcebergSinkConnector.CompactionTrigger.HYBRID);
            assertThat(c.getCompactionFileCountThreshold()).isEqualTo(100);
            assertThat(c.getCompactionFileSizeThreshold()).isEqualTo(256L * 1024 * 1024);
            assertThat(c.getSchemaEvolutionMode())
                    .isEqualTo(IcebergSinkConnector.SchemaEvolutionMode.PAUSE_ON_INCOMPATIBLE);
            assertThat(c.getCatalogProperties().getProperty("s3.endpoint")).isEqualTo("http://minio:9000");
            assertThat(c.getCatalogProperties().getProperty("s3.access-key")).isEqualTo("admin");
        }

        @Test
        @DisplayName("primaryKeys — 逗号分隔字符串解析")
        void primaryKeys_string() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .primaryKeys("a, b ,c")
                    .catalogUri("http://rest:8181")
                    .build();
            assertThat(c.getPrimaryKeys()).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("partitionKeys — 逗号分隔字符串解析")
        void partitionKeys_string() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .primaryKeys("id")
                    .partitionKeys("dt, region")
                    .catalogUri("http://rest:8181")
                    .build();
            assertThat(c.getPartitionKeys()).containsExactly("dt", "region");
        }

        @Test
        @DisplayName("lowLatency — 启用低延迟模式")
        void lowLatency() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .primaryKeys("id")
                    .catalogUri("http://rest:8181")
                    .lowLatency()
                    .build();

            assertThat(c.getMicroBatchSize()).isEqualTo(100);
            assertThat(c.isIncrementalCommit()).isTrue();
            assertThat(c.getCompactionTrigger())
                    .isEqualTo(IcebergSinkConnector.CompactionTrigger.AFTER_CHECKPOINT);
        }

        @Test
        @DisplayName("standardMode — 启用标准模式")
        void standardMode() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .primaryKeys("id")
                    .catalogUri("http://rest:8181")
                    .lowLatency()
                    .standardMode()
                    .build();

            assertThat(c.getMicroBatchSize()).isEqualTo(1000);
            assertThat(c.getCompactionTrigger())
                    .isEqualTo(IcebergSinkConnector.CompactionTrigger.BY_FILE_COUNT);
            assertThat(c.getCompactionFileCountThreshold()).isEqualTo(50);
        }

        @Test
        @DisplayName("catalogProperties — 批量设置")
        void catalogProperties_batch() {
            Map<String, String> props = new LinkedHashMap<>();
            props.put("k1", "v1");
            props.put("k2", "v2");
            props.put(null, "v3");
            props.put("k4", null);

            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .primaryKeys("id")
                    .catalogUri("http://rest:8181")
                    .catalogProperties(props)
                    .build();

            assertThat(c.getCatalogProperties().getProperty("k1")).isEqualTo("v1");
            assertThat(c.getCatalogProperties().getProperty("k2")).isEqualTo("v2");
            assertThat(c.getCatalogProperties().stringPropertyNames()).containsExactlyInAnyOrder("k1", "k2");
        }

        @Test
        @DisplayName("null warehouse — 抛出 NPE")
        void nullWarehouse() {
            assertThatThrownBy(() -> IcebergSinkConnector.builder()
                    .warehouse(null).database("ods").table("t").primaryKeys("id").catalogUri("u").build())
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ===== 配置校验测试 =====

    @Nested
    @DisplayName("配置校验 validate")
    class ValidationTest {

        @Test
        @DisplayName("完整 UPSERT 配置 — 校验通过")
        void validUpsert() {
            upsertConnector().validate();
        }

        @Test
        @DisplayName("完整 APPEND_ONLY 配置 — 校验通过")
        void validAppend() {
            appendConnector().validate();
        }

        @Test
        @DisplayName("catalogName 为空 — 抛出异常")
        void emptyCatalogName() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .catalogName("")
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .primaryKeys("id")
                    .catalogUri("u")
                    .build();
            assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("warehouse 为空 — 抛出异常")
        void emptyWarehouse() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("")
                    .database("ods")
                    .table("t")
                    .primaryKeys("id")
                    .catalogUri("u")
                    .build();
            assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("database 为空 — 抛出异常")
        void emptyDatabase() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("")
                    .table("t")
                    .primaryKeys("id")
                    .catalogUri("u")
                    .build();
            assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("table 为空 — 抛出异常")
        void emptyTable() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("")
                    .primaryKeys("id")
                    .catalogUri("u")
                    .build();
            assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("formatVersion 非法 — 抛出异常")
        void invalidFormatVersion() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .primaryKeys("id")
                    .catalogUri("u")
                    .formatVersion(3)
                    .build();
            assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("formatVersion");
        }

        @Test
        @DisplayName("UPSERT + V1 — 抛出异常")
        void upsertWithV1() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .primaryKeys("id")
                    .catalogUri("u")
                    .formatVersion(1)
                    .writeMode(IcebergSinkConnector.WriteMode.UPSERT)
                    .build();
            assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("UPSERT");
        }

        @Test
        @DisplayName("UPSERT 无主键 — 抛出异常")
        void upsertWithoutPrimaryKey() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .catalogUri("u")
                    .writeMode(IcebergSinkConnector.WriteMode.UPSERT)
                    .distributionMode(IcebergSinkConnector.DistributionMode.NONE)
                    .build();
            assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("primaryKeys");
        }

        @Test
        @DisplayName("HASH 分布无主键 — 抛出异常")
        void hashWithoutPrimaryKey() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .catalogUri("u")
                    .writeMode(IcebergSinkConnector.WriteMode.APPEND_ONLY)
                    .distributionMode(IcebergSinkConnector.DistributionMode.HASH)
                    .build();
            assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HASH");
        }

        @Test
        @DisplayName("REST catalog 无 URI — 抛出异常")
        void restWithoutUri() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .primaryKeys("id")
                    .catalogUri(null)
                    .build();
            assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("catalogUri");
        }

        @Test
        @DisplayName("JDBC catalog 无 URI — 抛出异常")
        void jdbcWithoutUri() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .catalogType(IcebergSinkConnector.CatalogType.JDBC)
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .primaryKeys("id")
                    .build();
            assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("catalogUri");
        }

        @Test
        @DisplayName("microBatchSize 负数 — 抛出异常")
        void negativeMicroBatch() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .primaryKeys("id")
                    .catalogUri("u")
                    .microBatchSize(-1)
                    .build();
            assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("microBatchSize");
        }

        @Test
        @DisplayName("compactionFileCountThreshold 负数 — 抛出异常")
        void negativeFileCountThreshold() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .primaryKeys("id")
                    .catalogUri("u")
                    .compactionFileCountThreshold(-1)
                    .build();
            assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("compactionFileSizeThreshold 负数 — 抛出异常")
        void negativeFileSizeThreshold() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/")
                    .database("ods")
                    .table("t")
                    .primaryKeys("id")
                    .catalogUri("u")
                    .compactionFileSizeThreshold(-1)
                    .build();
            assertThatThrownBy(c::validate).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("HADOOP catalog 无 URI — 校验通过（不需要 URI）")
        void hadoopWithoutUri() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .catalogType(IcebergSinkConnector.CatalogType.HADOOP)
                    .warehouse("hdfs:///wh")
                    .database("ods")
                    .table("t")
                    .writeMode(IcebergSinkConnector.WriteMode.APPEND_ONLY)
                    .distributionMode(IcebergSinkConnector.DistributionMode.NONE)
                    .build();
            c.validate();
        }
    }

    // ===== 行级 UPSERT 动作解析测试 =====

    @Nested
    @DisplayName("行级 UPSERT 动作解析")
    class UpsertActionTest {

        @Test
        @DisplayName("INSERT → INSERT_DATA")
        void insert() {
            IcebergSinkConnector c = upsertConnector();
            ChangeRecord r = record("c", null, Map.of("order_id", 1, "amount", 100));
            assertThat(c.resolveUpsertAction(r)).isEqualTo(IcebergSinkConnector.UpsertAction.INSERT_DATA);
        }

        @Test
        @DisplayName("UPDATE → UPDATE_WITH_DELETE")
        void update() {
            IcebergSinkConnector c = upsertConnector();
            ChangeRecord r = record("u", Map.of("order_id", 1, "amount", 100),
                    Map.of("order_id", 1, "amount", 200));
            assertThat(c.resolveUpsertAction(r)).isEqualTo(IcebergSinkConnector.UpsertAction.UPDATE_WITH_DELETE);
        }

        @Test
        @DisplayName("DELETE → DELETE_ONLY")
        void delete() {
            IcebergSinkConnector c = upsertConnector();
            ChangeRecord r = record("d", Map.of("order_id", 1), null);
            assertThat(c.resolveUpsertAction(r)).isEqualTo(IcebergSinkConnector.UpsertAction.DELETE_ONLY);
        }

        @Test
        @DisplayName("SNAPSHOT → INSERT_DATA")
        void snapshot() {
            IcebergSinkConnector c = upsertConnector();
            ChangeRecord r = record("r", null, Map.of("order_id", 1));
            assertThat(c.resolveUpsertAction(r)).isEqualTo(IcebergSinkConnector.UpsertAction.INSERT_DATA);
        }

        @Test
        @DisplayName("op 为 null → SKIP")
        void nullOp() {
            IcebergSinkConnector c = upsertConnector();
            ChangeRecord r = new ChangeRecord(null, null, (String) null, null, 100L);
            assertThat(c.resolveUpsertAction(r)).isEqualTo(IcebergSinkConnector.UpsertAction.SKIP);
        }

        @Test
        @DisplayName("未知 op → SKIP")
        void unknownOp() {
            IcebergSinkConnector c = upsertConnector();
            ChangeRecord r = record("x", null, Map.of("id", 1));
            assertThat(c.resolveUpsertAction(r)).isEqualTo(IcebergSinkConnector.UpsertAction.SKIP);
        }

        @Test
        @DisplayName("null record → 抛出 NPE")
        void nullRecord() {
            IcebergSinkConnector c = upsertConnector();
            assertThatThrownBy(() -> c.resolveUpsertAction(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("APPEND_ONLY 模式 — UPDATE 退化为 SKIP")
        void appendMode_update() {
            IcebergSinkConnector c = appendConnector();
            ChangeRecord r = record("u", Map.of("id", 1), Map.of("id", 1));
            assertThat(c.resolveUpsertAction(r)).isEqualTo(IcebergSinkConnector.UpsertAction.SKIP);
        }

        @Test
        @DisplayName("APPEND_ONLY 模式 — DELETE 退化为 SKIP")
        void appendMode_delete() {
            IcebergSinkConnector c = appendConnector();
            ChangeRecord r = record("d", Map.of("id", 1), null);
            assertThat(c.resolveUpsertAction(r)).isEqualTo(IcebergSinkConnector.UpsertAction.SKIP);
        }

        @Test
        @DisplayName("APPEND_ONLY 模式 — INSERT 仍为 INSERT_DATA")
        void appendMode_insert() {
            IcebergSinkConnector c = appendConnector();
            ChangeRecord r = record("c", null, Map.of("id", 1));
            assertThat(c.resolveUpsertAction(r)).isEqualTo(IcebergSinkConnector.UpsertAction.INSERT_DATA);
        }
    }

    // ===== extractRow / extractPrimaryKey / shouldWrite 测试 =====

    @Nested
    @DisplayName("行数据提取")
    class ExtractRowTest {

        @Test
        @DisplayName("extractRow — INSERT 返回 after")
        void insert() {
            IcebergSinkConnector c = upsertConnector();
            Map<String, Object> after = Map.of("order_id", 1, "amount", 100);
            ChangeRecord r = record("c", null, after);
            assertThat(c.extractRow(r)).isEqualTo(after);
        }

        @Test
        @DisplayName("extractRow — UPDATE 返回 after")
        void update() {
            IcebergSinkConnector c = upsertConnector();
            Map<String, Object> after = Map.of("order_id", 1, "amount", 200);
            ChangeRecord r = record("u", Map.of("order_id", 1, "amount", 100), after);
            assertThat(c.extractRow(r)).isEqualTo(after);
        }

        @Test
        @DisplayName("extractRow — DELETE 返回 before")
        void delete() {
            IcebergSinkConnector c = upsertConnector();
            Map<String, Object> before = Map.of("order_id", 1);
            ChangeRecord r = record("d", before, null);
            assertThat(c.extractRow(r)).isEqualTo(before);
        }

        @Test
        @DisplayName("extractRow — SKIP 返回 null")
        void skip() {
            IcebergSinkConnector c = upsertConnector();
            ChangeRecord r = new ChangeRecord(null, null, (String) null, null, 100L);
            assertThat(c.extractRow(r)).isNull();
        }

        @Test
        @DisplayName("extractRow — null record 抛出 NPE")
        void nullRecord() {
            assertThatThrownBy(() -> upsertConnector().extractRow(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("extractPrimaryKey — 单列主键")
        void singlePk() {
            IcebergSinkConnector c = upsertConnector();
            ChangeRecord r = record("c", null, Map.of("order_id", 42, "amount", 100));
            assertThat(c.extractPrimaryKey(r)).isEqualTo("42");
        }

        @Test
        @DisplayName("extractPrimaryKey — 多列主键用 | 分隔")
        void multiPk() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t")
                    .primaryKeys("a", "b")
                    .catalogUri("u")
                    .build();
            ChangeRecord r = record("c", null, Map.of("a", 1, "b", "x", "c", 2));
            assertThat(c.extractPrimaryKey(r)).isEqualTo("1|x");
        }

        @Test
        @DisplayName("extractPrimaryKey — 无主键返回 null")
        void noPk() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t")
                    .writeMode(IcebergSinkConnector.WriteMode.APPEND_ONLY)
                    .distributionMode(IcebergSinkConnector.DistributionMode.NONE)
                    .catalogUri("u")
                    .build();
            ChangeRecord r = record("c", null, Map.of("id", 1));
            assertThat(c.extractPrimaryKey(r)).isNull();
        }

        @Test
        @DisplayName("extractPrimaryKey — 主键列值为 null 返回 null")
        void nullPkValue() {
            IcebergSinkConnector c = upsertConnector();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("order_id", null);
            after.put("amount", 100);
            ChangeRecord r = record("c", null, after);
            assertThat(c.extractPrimaryKey(r)).isNull();
        }

        @Test
        @DisplayName("shouldWrite — INSERT/UPDATE/DELETE 返回 true")
        void shouldWrite_true() {
            IcebergSinkConnector c = upsertConnector();
            assertThat(c.shouldWrite(record("c", null, Map.of("order_id", 1)))).isTrue();
            assertThat(c.shouldWrite(record("u", Map.of("order_id", 1), Map.of("order_id", 1)))).isTrue();
            assertThat(c.shouldWrite(record("d", Map.of("order_id", 1), null))).isTrue();
        }

        @Test
        @DisplayName("shouldWrite — SKIP 返回 false")
        void shouldWrite_false() {
            IcebergSinkConnector c = upsertConnector();
            assertThat(c.shouldWrite(new ChangeRecord(null, null, (String) null, null, 100L))).isFalse();
            assertThat(c.shouldWrite(record("x", null, Map.of("id", 1)))).isFalse();
        }
    }

    // ===== 合并优化测试 =====

    @Nested
    @DisplayName("合并优化")
    class CompactionTest {

        @Test
        @DisplayName("shouldTriggerCompaction — NONE 永不触发")
        void noneTrigger() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .compactionTrigger(IcebergSinkConnector.CompactionTrigger.NONE)
                    .build();
            assertThat(c.shouldTriggerCompaction(1000, 1000000L)).isFalse();
        }

        @Test
        @DisplayName("shouldTriggerCompaction — AFTER_CHECKPOINT 总是触发")
        void afterCheckpointTrigger() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .compactionTrigger(IcebergSinkConnector.CompactionTrigger.AFTER_CHECKPOINT)
                    .build();
            assertThat(c.shouldTriggerCompaction(0, 0L)).isTrue();
            assertThat(c.shouldTriggerCompaction(10, 1000L)).isTrue();
        }

        @Test
        @DisplayName("shouldTriggerCompaction — BY_FILE_COUNT 超阈值触发")
        void byFileCountTrigger() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .compaction(IcebergSinkConnector.CompactionTrigger.BY_FILE_COUNT, 50, 0)
                    .build();
            assertThat(c.shouldTriggerCompaction(50, 0L)).isFalse();
            assertThat(c.shouldTriggerCompaction(51, 0L)).isTrue();
        }

        @Test
        @DisplayName("shouldTriggerCompaction — BY_FILE_SIZE 超阈值触发")
        void byFileSizeTrigger() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .compaction(IcebergSinkConnector.CompactionTrigger.BY_FILE_SIZE, 0, 1024)
                    .build();
            assertThat(c.shouldTriggerCompaction(0, 1024L)).isFalse();
            assertThat(c.shouldTriggerCompaction(0, 1025L)).isTrue();
        }

        @Test
        @DisplayName("shouldTriggerCompaction — HYBRID 任一超阈值触发")
        void hybridTrigger() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .compaction(IcebergSinkConnector.CompactionTrigger.HYBRID, 10, 1024)
                    .build();
            assertThat(c.shouldTriggerCompaction(5, 500L)).isFalse();
            assertThat(c.shouldTriggerCompaction(11, 500L)).isTrue();
            assertThat(c.shouldTriggerCompaction(5, 1025L)).isTrue();
            assertThat(c.shouldTriggerCompaction(11, 1025L)).isTrue();
        }

        @Test
        @DisplayName("planCompaction — 不触发返回 null")
        void planCompaction_noTrigger() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .compaction(IcebergSinkConnector.CompactionTrigger.BY_FILE_COUNT, 50, 0)
                    .build();
            assertThat(c.planCompaction(10, 1000L)).isNull();
        }

        @Test
        @DisplayName("planCompaction — 触发返回合并计划")
        void planCompaction_trigger() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .compaction(IcebergSinkConnector.CompactionTrigger.BY_FILE_SIZE, 0, 128L * 1024 * 1024)
                    .build();
            IcebergSinkConnector.CompactionPlan plan = c.planCompaction(100, 256L * 1024 * 1024);
            assertThat(plan).isNotNull();
            assertThat(plan.getSourceFileCount()).isEqualTo(100);
            assertThat(plan.getTargetFileCount()).isEqualTo(2);
            assertThat(plan.getTotalSizeBytes()).isEqualTo(256L * 1024 * 1024);
        }

        @Test
        @DisplayName("planCompaction — 零大小文件至少合并为 1 个文件")
        void planCompaction_zeroSize() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .compaction(IcebergSinkConnector.CompactionTrigger.AFTER_CHECKPOINT, 0, 128L * 1024 * 1024)
                    .build();
            IcebergSinkConnector.CompactionPlan plan = c.planCompaction(50, 0L);
            assertThat(plan).isNotNull();
            assertThat(plan.getTargetFileCount()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("isMicroBatchFull — 攒批满触发")
        void microBatchFull() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .microBatchSize(100)
                    .build();
            assertThat(c.isMicroBatchFull(99)).isFalse();
            assertThat(c.isMicroBatchFull(100)).isTrue();
            assertThat(c.isMicroBatchFull(101)).isTrue();
        }

        @Test
        @DisplayName("isMicroBatchFull — 0 表示禁用，永不触发")
        void microBatchDisabled() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .microBatchSize(0)
                    .build();
            assertThat(c.isMicroBatchFull(10000)).isFalse();
        }
    }

    @Nested
    @DisplayName("CompactionPlan")
    class CompactionPlanTest {

        @Test
        @DisplayName("reductionRatio — 正确计算缩减比例")
        void reductionRatio() {
            IcebergSinkConnector.CompactionPlan plan =
                    new IcebergSinkConnector.CompactionPlan(100, 10, 1024L, 128L);
            assertThat(plan.reductionRatio()).isCloseTo(0.9, within(0.001));
        }

        @Test
        @DisplayName("reductionRatio — 源文件数为 0 返回 0")
        void reductionRatio_zeroSource() {
            IcebergSinkConnector.CompactionPlan plan =
                    new IcebergSinkConnector.CompactionPlan(0, 0, 0L, 128L);
            assertThat(plan.reductionRatio()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("toString — 包含关键信息")
        void toString_containsKeyInfo() {
            IcebergSinkConnector.CompactionPlan plan =
                    new IcebergSinkConnector.CompactionPlan(100, 10, 1024L, 128L);
            String s = plan.toString();
            assertThat(s).contains("source=100").contains("target=10").contains("reduction");
        }

        @Test
        @DisplayName("getter — 返回正确值")
        void getters() {
            IcebergSinkConnector.CompactionPlan plan =
                    new IcebergSinkConnector.CompactionPlan(50, 5, 1000L, 200L);
            assertThat(plan.getSourceFileCount()).isEqualTo(50);
            assertThat(plan.getTargetFileCount()).isEqualTo(5);
            assertThat(plan.getTotalSizeBytes()).isEqualTo(1000L);
            assertThat(plan.getTargetFileSizeBytes()).isEqualTo(200L);
        }
    }

    // ===== Schema 演化测试 =====

    @Nested
    @DisplayName("Schema 演化")
    class SchemaEvolutionTest {

        @Test
        @DisplayName("analyzeSchemaChange — 无变更")
        void noChange() {
            IcebergSinkConnector c = upsertConnector();
            Map<String, Object> before = Map.of("id", 1, "name", "a");
            Map<String, Object> after = Map.of("id", 1, "name", "b");
            assertThat(c.analyzeSchemaChange(before, after))
                    .isEqualTo(IcebergSinkConnector.SchemaChangeType.NONE);
        }

        @Test
        @DisplayName("analyzeSchemaChange — 加列")
        void addColumn() {
            IcebergSinkConnector c = upsertConnector();
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("id", 1);
            before.put("name", "a");
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("id", 1);
            after.put("name", "a");
            after.put("age", 20);
            assertThat(c.analyzeSchemaChange(before, after))
                    .isEqualTo(IcebergSinkConnector.SchemaChangeType.ADD_COLUMN);
        }

        @Test
        @DisplayName("analyzeSchemaChange — 删列")
        void dropColumn() {
            IcebergSinkConnector c = upsertConnector();
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("id", 1);
            before.put("name", "a");
            before.put("age", 20);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("id", 1);
            after.put("name", "a");
            assertThat(c.analyzeSchemaChange(before, after))
                    .isEqualTo(IcebergSinkConnector.SchemaChangeType.DROP_COLUMN);
        }

        @Test
        @DisplayName("analyzeSchemaChange — 同时加列删列 → INCOMPATIBLE")
        void addAndDrop() {
            IcebergSinkConnector c = upsertConnector();
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("id", 1);
            before.put("name", "a");
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("id", 1);
            after.put("age", 20);
            assertThat(c.analyzeSchemaChange(before, after))
                    .isEqualTo(IcebergSinkConnector.SchemaChangeType.INCOMPATIBLE);
        }

        @Test
        @DisplayName("analyzeSchemaChange — before null → ADD_COLUMN")
        void beforeNull() {
            IcebergSinkConnector c = upsertConnector();
            assertThat(c.analyzeSchemaChange(null, Map.of("id", 1)))
                    .isEqualTo(IcebergSinkConnector.SchemaChangeType.ADD_COLUMN);
        }

        @Test
        @DisplayName("analyzeSchemaChange — after null → DROP_COLUMN")
        void afterNull() {
            IcebergSinkConnector c = upsertConnector();
            assertThat(c.analyzeSchemaChange(Map.of("id", 1), null))
                    .isEqualTo(IcebergSinkConnector.SchemaChangeType.DROP_COLUMN);
        }

        @Test
        @DisplayName("analyzeSchemaChange — 双 null → NONE")
        void bothNull() {
            IcebergSinkConnector c = upsertConnector();
            assertThat(c.analyzeSchemaChange(null, null))
                    .isEqualTo(IcebergSinkConnector.SchemaChangeType.NONE);
        }

        @Test
        @DisplayName("isCompatibleChange — 兼容变更")
        void compatibleChanges() {
            IcebergSinkConnector c = upsertConnector();
            assertThat(c.isCompatibleChange(IcebergSinkConnector.SchemaChangeType.NONE)).isTrue();
            assertThat(c.isCompatibleChange(IcebergSinkConnector.SchemaChangeType.ADD_COLUMN)).isTrue();
            assertThat(c.isCompatibleChange(IcebergSinkConnector.SchemaChangeType.DROP_COLUMN)).isTrue();
            assertThat(c.isCompatibleChange(IcebergSinkConnector.SchemaChangeType.RENAME_COLUMN)).isTrue();
            assertThat(c.isCompatibleChange(IcebergSinkConnector.SchemaChangeType.TYPE_WIDENING)).isTrue();
        }

        @Test
        @DisplayName("isCompatibleChange — 不兼容变更")
        void incompatibleChanges() {
            IcebergSinkConnector c = upsertConnector();
            assertThat(c.isCompatibleChange(IcebergSinkConnector.SchemaChangeType.TYPE_NARROWING)).isFalse();
            assertThat(c.isCompatibleChange(IcebergSinkConnector.SchemaChangeType.INCOMPATIBLE)).isFalse();
        }

        @Test
        @DisplayName("shouldPauseOnSchemaChange — OFF 模式任何变更暂停")
        void offMode_pause() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .schemaEvolution(IcebergSinkConnector.SchemaEvolutionMode.OFF)
                    .build();
            assertThat(c.shouldPauseOnSchemaChange(IcebergSinkConnector.SchemaChangeType.NONE)).isFalse();
            assertThat(c.shouldPauseOnSchemaChange(IcebergSinkConnector.SchemaChangeType.ADD_COLUMN)).isTrue();
            assertThat(c.shouldPauseOnSchemaChange(IcebergSinkConnector.SchemaChangeType.DROP_COLUMN)).isTrue();
        }

        @Test
        @DisplayName("shouldPauseOnSchemaChange — AUTO 模式仅不兼容暂停")
        void autoMode_pause() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .schemaEvolution(IcebergSinkConnector.SchemaEvolutionMode.AUTO)
                    .build();
            assertThat(c.shouldPauseOnSchemaChange(IcebergSinkConnector.SchemaChangeType.NONE)).isFalse();
            assertThat(c.shouldPauseOnSchemaChange(IcebergSinkConnector.SchemaChangeType.ADD_COLUMN)).isFalse();
            assertThat(c.shouldPauseOnSchemaChange(IcebergSinkConnector.SchemaChangeType.INCOMPATIBLE)).isTrue();
            assertThat(c.shouldPauseOnSchemaChange(IcebergSinkConnector.SchemaChangeType.TYPE_NARROWING)).isTrue();
        }

        @Test
        @DisplayName("shouldPauseOnSchemaChange — PAUSE_ON_INCOMPATIBLE 模式")
        void pauseOnIncompatibleMode() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .schemaEvolution(IcebergSinkConnector.SchemaEvolutionMode.PAUSE_ON_INCOMPATIBLE)
                    .build();
            assertThat(c.shouldPauseOnSchemaChange(IcebergSinkConnector.SchemaChangeType.ADD_COLUMN)).isFalse();
            assertThat(c.shouldPauseOnSchemaChange(IcebergSinkConnector.SchemaChangeType.INCOMPATIBLE)).isTrue();
        }
    }

    // ===== createIcebergProperties 测试 =====

    @Nested
    @DisplayName("createIcebergProperties")
    class IcebergPropertiesTest {

        @Test
        @DisplayName("UPSERT 模式 — 包含 V2 + upsert + hash 属性")
        void upsertProps() {
            IcebergSinkConnector c = upsertConnector();
            Map<String, String> props = c.createIcebergProperties();

            assertThat(props.get("connector")).isEqualTo("iceberg");
            assertThat(props.get("catalog-name")).isEqualTo("rest");
            assertThat(props.get("catalog-impl"))
                    .isEqualTo("org.apache.iceberg.rest.RESTCatalog");
            assertThat(props.get("uri")).isEqualTo("http://iceberg-rest:8181");
            assertThat(props.get("warehouse")).isEqualTo("s3://shuqing-warehouse/");
            assertThat(props.get("format-version")).isEqualTo("2");
            assertThat(props.get("write.upsert.enabled")).isEqualTo("true");
            assertThat(props.get("write.distribution-mode")).isEqualTo("hash");
            assertThat(props.get("write.format.default")).isEqualTo("parquet");
        }

        @Test
        @DisplayName("APPEND_ONLY 模式 — 不含 upsert 属性")
        void appendProps() {
            IcebergSinkConnector c = appendConnector();
            Map<String, String> props = c.createIcebergProperties();

            assertThat(props.get("format-version")).isEqualTo("2");
            assertThat(props).doesNotContainKey("write.upsert.enabled");
            assertThat(props.get("write.distribution-mode")).isEqualTo("none");
        }

        @Test
        @DisplayName("catalog 属性前缀 catalog.")
        void catalogPropsPrefix() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .catalogProperty("s3.endpoint", "http://minio:9000")
                    .build();
            Map<String, String> props = c.createIcebergProperties();
            assertThat(props.get("catalog.s3.endpoint")).isEqualTo("http://minio:9000");
        }

        @Test
        @DisplayName("incrementalCommit — 设置 manifest target size")
        void incrementalCommitProps() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .incrementalCommit()
                    .build();
            Map<String, String> props = c.createIcebergProperties();
            assertThat(props.get("commit.manifest.target-size-bytes"))
                    .isEqualTo(String.valueOf(8L * 1024 * 1024));
        }

        @Test
        @DisplayName("resolveCatalogImpl — 各类型返回正确实现类")
        void catalogImpl() {
            assertThat(IcebergSinkConnector.builder()
                    .catalogType(IcebergSinkConnector.CatalogType.REST)
                    .warehouse("w").database("d").table("t").primaryKeys("id").catalogUri("u")
                    .build().resolveCatalogImpl())
                    .isEqualTo("org.apache.iceberg.rest.RESTCatalog");
            assertThat(IcebergSinkConnector.builder()
                    .catalogType(IcebergSinkConnector.CatalogType.HIVE)
                    .warehouse("w").database("d").table("t").primaryKeys("id")
                    .build().resolveCatalogImpl())
                    .isEqualTo("org.apache.iceberg.hive.HiveCatalog");
            assertThat(IcebergSinkConnector.builder()
                    .catalogType(IcebergSinkConnector.CatalogType.HADOOP)
                    .warehouse("w").database("d").table("t").primaryKeys("id")
                    .build().resolveCatalogImpl())
                    .isEqualTo("org.apache.iceberg.hadoop.HadoopCatalog");
            assertThat(IcebergSinkConnector.builder()
                    .catalogType(IcebergSinkConnector.CatalogType.JDBC)
                    .warehouse("w").database("d").table("t").primaryKeys("id").catalogUri("u")
                    .build().resolveCatalogImpl())
                    .isEqualTo("org.apache.iceberg.jdbc.JdbcCatalog");
        }

        @Test
        @DisplayName("HIVE catalog 无 uri — 属性不含 uri 键")
        void hiveWithoutUri() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .catalogType(IcebergSinkConnector.CatalogType.HIVE)
                    .warehouse("hdfs:///wh").database("ods").table("t").primaryKeys("id")
                    .build();
            Map<String, String> props = c.createIcebergProperties();
            assertThat(props).doesNotContainKey("uri");
        }
    }

    // ===== createSink / 反射测试 =====

    @Nested
    @DisplayName("createSink 反射加载")
    class CreateSinkTest {

        @Test
        @DisplayName("createIcebergSinkViaReflection — Iceberg 不可用时抛出 IllegalStateException")
        void icebergUnavailable() {
            IcebergSinkConnector c = upsertConnector();
            Map<String, String> props = c.createIcebergProperties();
            assertThatThrownBy(() -> c.createIcebergSinkViaReflection(props))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Iceberg Flink Sink 依赖不可用");
        }

        @Test
        @DisplayName("createSink — Iceberg 不可用时抛出异常（先 validate 再反射）")
        void createSink_icebergUnavailable() {
            IcebergSinkConnector c = upsertConnector();
            assertThatThrownBy(c::createSink)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("createSink — 配置不合法时抛出异常（validate 先于反射）")
        void createSink_invalidConfig() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("").database("ods").table("t").primaryKeys("id").catalogUri("u")
                    .build();
            assertThatThrownBy(c::createSink)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("warehouse");
        }
    }

    // ===== 运行时统计测试 =====

    @Nested
    @DisplayName("运行时统计")
    class StatsTest {

        @Test
        @DisplayName("recordStats — 正确计数 INSERT/UPDATE/DELETE")
        void recordStats() {
            IcebergSinkConnector c = upsertConnector();
            c.recordStats(record("c", null, Map.of("order_id", 1)));
            c.recordStats(record("c", null, Map.of("order_id", 2)));
            c.recordStats(record("u", Map.of("order_id", 1), Map.of("order_id", 1)));
            c.recordStats(record("d", Map.of("order_id", 3), null));
            c.recordStats(new ChangeRecord(null, null, (String) null, null, 100L)); // SKIP

            assertThat(c.getTotalRecords()).isEqualTo(5);
            assertThat(c.getTotalInserts()).isEqualTo(2);
            assertThat(c.getTotalUpdates()).isEqualTo(1);
            assertThat(c.getTotalDeletes()).isEqualTo(1);
        }

        @Test
        @DisplayName("recordCompaction — 计数合并次数")
        void recordCompaction() {
            IcebergSinkConnector c = upsertConnector();
            assertThat(c.getTotalCompactions()).isEqualTo(0);
            c.recordCompaction();
            c.recordCompaction();
            assertThat(c.getTotalCompactions()).isEqualTo(2);
        }

        @Test
        @DisplayName("初始统计为 0")
        void initialStats() {
            IcebergSinkConnector c = upsertConnector();
            assertThat(c.getTotalRecords()).isEqualTo(0);
            assertThat(c.getTotalInserts()).isEqualTo(0);
            assertThat(c.getTotalUpdates()).isEqualTo(0);
            assertThat(c.getTotalDeletes()).isEqualTo(0);
            assertThat(c.getTotalCompactions()).isEqualTo(0);
        }
    }

    // ===== toString / fullTableName / Getter 测试 =====

    @Nested
    @DisplayName("toString / fullTableName")
    class ToStringTest {

        @Test
        @DisplayName("toString — 包含关键配置信息")
        void toString_containsKeyInfo() {
            IcebergSinkConnector c = upsertConnector();
            String s = c.toString();
            assertThat(s).contains("rest").contains("ods.orders")
                    .contains("format-version=2").contains("upsert").contains("hash");
        }

        @Test
        @DisplayName("fullTableName — database.table")
        void fullTableName() {
            assertThat(upsertConnector().fullTableName()).isEqualTo("ods.orders");
        }

        @Test
        @DisplayName("getCatalogProperties — 返回防御性副本")
        void catalogPropertiesDefensiveCopy() {
            IcebergSinkConnector c = IcebergSinkConnector.builder()
                    .warehouse("s3://wh/").database("ods").table("t").primaryKeys("id")
                    .catalogUri("u")
                    .catalogProperty("k", "v")
                    .build();
            Properties props = c.getCatalogProperties();
            props.setProperty("k", "modified");
            // 副本修改不影响原配置
            assertThat(c.getCatalogProperties().getProperty("k")).isEqualTo("v");
        }

        @Test
        @DisplayName("getPrimaryKeys — 返回不可变列表")
        void primaryKeysUnmodifiable() {
            IcebergSinkConnector c = upsertConnector();
            assertThatThrownBy(() -> c.getPrimaryKeys().add("new"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("getPartitionKeys — 返回不可变列表")
        void partitionKeysUnmodifiable() {
            IcebergSinkConnector c = upsertConnector();
            assertThatThrownBy(() -> c.getPartitionKeys().add("new"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}