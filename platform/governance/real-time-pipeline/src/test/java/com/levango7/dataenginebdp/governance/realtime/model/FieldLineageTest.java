package com.levango7.dataenginebdp.governance.realtime.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FieldLineage} 模型类单元测试。
 *
 * <p>验证 Lombok 生成的 builder、getter、equals/hashCode、toString 行为，
 * 以及内嵌 {@link FieldLineage.FieldMapping} 的同等功能。
 */
@DisplayName("FieldLineage 字段血缘模型")
class FieldLineageTest {

    @Nested
    @DisplayName("构造与 Getter")
    class ConstructionAndGetter {

        @Test
        @DisplayName("Builder 应正确设置所有字段")
        void builderSetsAllFields() {
            Instant now = Instant.now();
            FieldLineage.FieldMapping mapping = FieldLineage.FieldMapping.builder()
                    .sourceField("src_col")
                    .targetField("tgt_col")
                    .transformType("DIRECT")
                    .expression(null)
                    .build();

            FieldLineage lineage = FieldLineage.builder()
                    .lineageId("uuid-123")
                    .sourceTable("source_db.source_table")
                    .targetTable("target_db.target_table")
                    .fieldMappings(List.of(mapping))
                    .jobId("flink-job-001")
                    .sqlText("INSERT INTO target SELECT src_col FROM source")
                    .extractedAt(now)
                    .extractDurationMs(42L)
                    .build();

            assertThat(lineage.getLineageId()).isEqualTo("uuid-123");
            assertThat(lineage.getSourceTable()).isEqualTo("source_db.source_table");
            assertThat(lineage.getTargetTable()).isEqualTo("target_db.target_table");
            assertThat(lineage.getFieldMappings()).containsExactly(mapping);
            assertThat(lineage.getJobId()).isEqualTo("flink-job-001");
            assertThat(lineage.getSqlText()).contains("INSERT INTO target");
            assertThat(lineage.getExtractedAt()).isEqualTo(now);
            assertThat(lineage.getExtractDurationMs()).isEqualTo(42L);
        }

        @Test
        @DisplayName("无参构造应产生 null 默认值")
        void noArgsConstructorDefaultsNull() {
            FieldLineage lineage = new FieldLineage();

            assertThat(lineage.getLineageId()).isNull();
            assertThat(lineage.getSourceTable()).isNull();
            assertThat(lineage.getTargetTable()).isNull();
            assertThat(lineage.getFieldMappings()).isNull();
            assertThat(lineage.getJobId()).isNull();
            assertThat(lineage.getSqlText()).isNull();
            assertThat(lineage.getExtractedAt()).isNull();
            assertThat(lineage.getExtractDurationMs()).isZero();
        }

        @Test
        @DisplayName("全参构造应正确赋值")
        void allArgsConstructorSetsAll() {
            Instant now = Instant.now();
            List<FieldLineage.FieldMapping> mappings = List.of(
                    FieldLineage.FieldMapping.builder()
                            .sourceField("a").targetField("b")
                            .transformType("DIRECT").build());

            FieldLineage lineage = new FieldLineage(
                    "id-1", "src", "tgt", mappings, "job-1", "sql", now, 10L);

            assertThat(lineage.getLineageId()).isEqualTo("id-1");
            assertThat(lineage.getSourceTable()).isEqualTo("src");
            assertThat(lineage.getTargetTable()).isEqualTo("tgt");
            assertThat(lineage.getFieldMappings()).hasSize(1);
            assertThat(lineage.getJobId()).isEqualTo("job-1");
            assertThat(lineage.getSqlText()).isEqualTo("sql");
            assertThat(lineage.getExtractedAt()).isEqualTo(now);
            assertThat(lineage.getExtractDurationMs()).isEqualTo(10L);
        }
    }

    @Nested
    @DisplayName("equals 与 hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("相同字段值的两个对象应相等")
        void equalObjectsHaveSameHashCode() {
            Instant now = Instant.now();
            FieldLineage a = buildLineage("id", "src", "tgt", "job", now, 5L);
            FieldLineage b = buildLineage("id", "src", "tgt", "job", now, 5L);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("任一字段不同应不相等")
        void differingFieldMakesNotEqual() {
            Instant now = Instant.now();
            FieldLineage base = buildLineage("id", "src", "tgt", "job", now, 5L);

            assertThat(base).isNotEqualTo(buildLineage("id-x", "src", "tgt", "job", now, 5L));
            assertThat(base).isNotEqualTo(buildLineage("id", "src-x", "tgt", "job", now, 5L));
            assertThat(base).isNotEqualTo(buildLineage("id", "src", "tgt-x", "job", now, 5L));
            assertThat(base).isNotEqualTo(buildLineage("id", "src", "tgt", "job-x", now, 5L));
            assertThat(base).isNotEqualTo(buildLineage("id", "src", "tgt", "job", now, 99L));
        }

        @Test
        @DisplayName("与 null 及其他类型比较应不相等")
        void notEqualToNullAndOtherType() {
            FieldLineage lineage = buildLineage("id", "src", "tgt", "job", Instant.now(), 1L);

            assertThat(lineage).isNotEqualTo(null);
            assertThat(lineage).isNotEqualTo("some-string");
        }

        private FieldLineage buildLineage(String id, String src, String tgt,
                                          String job, Instant at, long duration) {
            return FieldLineage.builder()
                    .lineageId(id)
                    .sourceTable(src)
                    .targetTable(tgt)
                    .fieldMappings(List.of())
                    .jobId(job)
                    .sqlText("sql")
                    .extractedAt(at)
                    .extractDurationMs(duration)
                    .build();
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("toString 应包含所有字段名")
        void toStringContainsFieldNames() {
            FieldLineage lineage = FieldLineage.builder()
                    .lineageId("uuid-xyz")
                    .sourceTable("src_tbl")
                    .targetTable("tgt_tbl")
                    .fieldMappings(List.of())
                    .jobId("job-abc")
                    .sqlText("SELECT 1")
                    .extractedAt(Instant.now())
                    .extractDurationMs(7L)
                    .build();

            String str = lineage.toString();
            assertThat(str).contains("uuid-xyz");
            assertThat(str).contains("src_tbl");
            assertThat(str).contains("tgt_tbl");
            assertThat(str).contains("job-abc");
        }
    }

    @Nested
    @DisplayName("FieldMapping 内嵌类")
    class FieldMappingTest {

        @Test
        @DisplayName("Builder 应正确设置所有字段")
        void builderSetsAllFields() {
            FieldLineage.FieldMapping mapping = FieldLineage.FieldMapping.builder()
                    .sourceField("src_col")
                    .targetField("tgt_col")
                    .transformType("TRANSFORM")
                    .expression("src_col + 1")
                    .build();

            assertThat(mapping.getSourceField()).isEqualTo("src_col");
            assertThat(mapping.getTargetField()).isEqualTo("tgt_col");
            assertThat(mapping.getTransformType()).isEqualTo("TRANSFORM");
            assertThat(mapping.getExpression()).isEqualTo("src_col + 1");
        }

        @Test
        @DisplayName("相同值的两个 FieldMapping 应相等")
        void equalFieldMappings() {
            FieldLineage.FieldMapping a = FieldLineage.FieldMapping.builder()
                    .sourceField("c1").targetField("c2")
                    .transformType("DIRECT").expression(null).build();
            FieldLineage.FieldMapping b = FieldLineage.FieldMapping.builder()
                    .sourceField("c1").targetField("c2")
                    .transformType("DIRECT").expression(null).build();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("toString 应包含字段值")
        void toStringContainsValues() {
            FieldLineage.FieldMapping mapping = FieldLineage.FieldMapping.builder()
                    .sourceField("s").targetField("t")
                    .transformType("DIRECT").build();

            assertThat(mapping.toString()).contains("s").contains("t").contains("DIRECT");
        }
    }
}