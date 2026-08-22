package com.levango7.dataenginebdp.governance.realtime.lineage;

import com.levango7.dataenginebdp.governance.realtime.model.FieldLineage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FlinkCdcSqlLineageParser} 单元测试。
 *
 * <p>覆盖 Flink CDC SQL 血缘解析的三类核心场景：
 * <ul>
 *   <li>简单 INSERT INTO ... SELECT ... FROM ...</li>
 *   <li>带显式目标列与转换表达式的 INSERT</li>
 *   <li>多表 JOIN 血缘提取</li>
 *   <li>CREATE TABLE AS SELECT 语法</li>
 *   <li>异常输入降级处理</li>
 * </ul>
 */
@DisplayName("FlinkCdcSqlLineageParser 血缘解析")
class FlinkCdcSqlLineageParserTest {

    private FlinkCdcSqlLineageParser parser;

    @BeforeEach
    void setUp() {
        parser = new FlinkCdcSqlLineageParser();
    }

    @Nested
    @DisplayName("简单 SELECT 语句")
    class SimpleSelect {

        @Test
        @DisplayName("解析 INSERT INTO target SELECT field_a, field_b FROM source")
        void parseSimpleInsertSelect() {
            String sql = "INSERT INTO target_table SELECT field_a, field_b FROM source_table";

            FieldLineage lineage = parser.parse(sql, "job-001");

            assertThat(lineage).isNotNull();
            assertThat(lineage.getSourceTable()).isEqualTo("source_table");
            assertThat(lineage.getTargetTable()).isEqualTo("target_table");
            assertThat(lineage.getJobId()).isEqualTo("job-001");
            assertThat(lineage.getSqlText()).isEqualTo(sql);
            assertThat(lineage.getLineageId()).isNotBlank();
            assertThat(lineage.getExtractedAt()).isNotNull();
            assertThat(lineage.getExtractDurationMs()).isGreaterThanOrEqualTo(0);
            assertThat(lineage.getFieldMappings()).hasSize(2);

            // 第一列：field_a → field_a，DIRECT
            FieldLineage.FieldMapping m1 = lineage.getFieldMappings().get(0);
            assertThat(m1.getSourceField()).isEqualTo("field_a");
            assertThat(m1.getTargetField()).isEqualTo("field_a");
            assertThat(m1.getTransformType()).isEqualTo("DIRECT");
            assertThat(m1.getExpression()).isNull();

            // 第二列：field_b → field_b，DIRECT
            FieldLineage.FieldMapping m2 = lineage.getFieldMappings().get(1);
            assertThat(m2.getSourceField()).isEqualTo("field_b");
            assertThat(m2.getTargetField()).isEqualTo("field_b");
            assertThat(m2.getTransformType()).isEqualTo("DIRECT");
        }

        @Test
        @DisplayName("带表别名的字段引用应剥离别名前缀")
        void parseWithTableAlias() {
            String sql = "INSERT INTO target_table SELECT src.field_a, src.field_b FROM source_table src";

            FieldLineage lineage = parser.parse(sql, "job-002");

            assertThat(lineage.getSourceTable()).isEqualTo("source_table");
            assertThat(lineage.getTargetTable()).isEqualTo("target_table");
            assertThat(lineage.getFieldMappings()).hasSize(2);

            FieldLineage.FieldMapping m1 = lineage.getFieldMappings().get(0);
            assertThat(m1.getSourceField()).isEqualTo("field_a");
            assertThat(m1.getTargetField()).isEqualTo("field_a");
            assertThat(m1.getTransformType()).isEqualTo("DIRECT");
        }
    }

    @Nested
    @DisplayName("INSERT INTO ... (显式列) SELECT ... 带转换表达式")
    class InsertWithExplicitColumns {

        @Test
        @DisplayName("显式目标列 + 转换表达式应识别为 TRANSFORM")
        void parseWithExplicitColumnsAndTransform() {
            String sql = "INSERT INTO target_table (f1, f2) "
                    + "SELECT src.field_a, src.field_b + 1 FROM source_table src";

            FieldLineage lineage = parser.parse(sql, "job-003");

            assertThat(lineage.getSourceTable()).isEqualTo("source_table");
            assertThat(lineage.getTargetTable()).isEqualTo("target_table");
            assertThat(lineage.getFieldMappings()).hasSize(2);

            // f1 ← field_a，DIRECT
            FieldLineage.FieldMapping m1 = lineage.getFieldMappings().get(0);
            assertThat(m1.getTargetField()).isEqualTo("f1");
            assertThat(m1.getSourceField()).isEqualTo("field_a");
            assertThat(m1.getTransformType()).isEqualTo("DIRECT");

            // f2 ← field_b + 1，TRANSFORM
            FieldLineage.FieldMapping m2 = lineage.getFieldMappings().get(1);
            assertThat(m2.getTargetField()).isEqualTo("f2");
            assertThat(m2.getSourceField()).isEqualTo("field_b");
            assertThat(m2.getTransformType()).isEqualTo("TRANSFORM");
            assertThat(m2.getExpression()).contains("field_b + 1");
        }

        @Test
        @DisplayName("常量投影应识别为 CONSTANT")
        void parseConstantProjection() {
            String sql = "INSERT INTO target_table (flag) SELECT 123 FROM source_table";

            FieldLineage lineage = parser.parse(sql, "job-004");

            assertThat(lineage.getFieldMappings()).hasSize(1);
            FieldLineage.FieldMapping m = lineage.getFieldMappings().get(0);
            assertThat(m.getTargetField()).isEqualTo("flag");
            assertThat(m.getTransformType()).isEqualTo("CONSTANT");
            assertThat(m.getSourceField()).isNull();
            assertThat(m.getExpression()).isEqualTo("123");
        }

        @Test
        @DisplayName("聚合函数投影应识别为 AGGREGATE")
        void parseAggregateProjection() {
            String sql = "INSERT INTO target_table (total) SELECT SUM(amount) FROM source_table";

            FieldLineage lineage = parser.parse(sql, "job-005");

            assertThat(lineage.getFieldMappings()).hasSize(1);
            FieldLineage.FieldMapping m = lineage.getFieldMappings().get(0);
            assertThat(m.getTargetField()).isEqualTo("total");
            assertThat(m.getTransformType()).isEqualTo("AGGREGATE");
            assertThat(m.getSourceField()).isEqualTo("amount");
            assertThat(m.getExpression()).contains("SUM(amount)");
        }
    }

    @Nested
    @DisplayName("多表 JOIN 血缘")
    class JoinLineage {

        @Test
        @DisplayName("解析双表 JOIN 应提取主源表与所有投影字段")
        void parseTwoTableJoin() {
            String sql = "INSERT INTO target_table "
                    + "SELECT a.field1, b.field2 "
                    + "FROM source_table_a a JOIN source_table_b b ON a.id = b.id";

            FieldLineage lineage = parser.parse(sql, "job-006");

            assertThat(lineage.getTargetTable()).isEqualTo("target_table");
            // 主源表取 FROM 子句中第一个表
            assertThat(lineage.getSourceTable()).isEqualTo("source_table_a");
            assertThat(lineage.getFieldMappings()).hasSize(2);

            FieldLineage.FieldMapping m1 = lineage.getFieldMappings().get(0);
            assertThat(m1.getSourceField()).isEqualTo("field1");
            assertThat(m1.getTargetField()).isEqualTo("field1");
            assertThat(m1.getTransformType()).isEqualTo("DIRECT");

            FieldLineage.FieldMapping m2 = lineage.getFieldMappings().get(1);
            assertThat(m2.getSourceField()).isEqualTo("field2");
            assertThat(m2.getTargetField()).isEqualTo("field2");
            assertThat(m2.getTransformType()).isEqualTo("DIRECT");
        }
    }

    @Nested
    @DisplayName("CREATE TABLE AS SELECT 语法")
    class CreateAsSelect {

        @Test
        @DisplayName("解析 CTAS 应提取源表与目标表")
        void parseCtas() {
            String sql = "CREATE TABLE target_table AS SELECT field_a, field_b FROM source_table";

            FieldLineage lineage = parser.parse(sql, "job-007");

            assertThat(lineage.getSourceTable()).isEqualTo("source_table");
            assertThat(lineage.getTargetTable()).isEqualTo("target_table");
            assertThat(lineage.getFieldMappings()).hasSize(2);
            assertThat(lineage.getJobId()).isEqualTo("job-007");
        }
    }

    @Nested
    @DisplayName("异常与边界输入")
    class EdgeCases {

        @Test
        @DisplayName("null SQL 应返回空血缘（unknown/unknown）")
        void parseNullSql() {
            FieldLineage lineage = parser.parse(null, "job-null");

            assertThat(lineage).isNotNull();
            assertThat(lineage.getSourceTable()).isEqualTo("unknown");
            assertThat(lineage.getTargetTable()).isEqualTo("unknown");
            assertThat(lineage.getFieldMappings()).isEmpty();
            assertThat(lineage.getJobId()).isEqualTo("job-null");
        }

        @Test
        @DisplayName("空字符串 SQL 应返回空血缘")
        void parseEmptySql() {
            FieldLineage lineage = parser.parse("   ", "job-empty");

            assertThat(lineage.getSourceTable()).isEqualTo("unknown");
            assertThat(lineage.getTargetTable()).isEqualTo("unknown");
            assertThat(lineage.getFieldMappings()).isEmpty();
        }

        @Test
        @DisplayName("无法识别的 SQL 模式应返回空血缘")
        void parseUnrecognizedSql() {
            FieldLineage lineage = parser.parse("DROP TABLE some_table", "job-drop");

            assertThat(lineage.getSourceTable()).isEqualTo("unknown");
            assertThat(lineage.getTargetTable()).isEqualTo("unknown");
            assertThat(lineage.getFieldMappings()).isEmpty();
        }

        @Test
        @DisplayName("带尾部分号号的 SQL 应正常解析")
        void parseSqlWithSemicolon() {
            String sql = "INSERT INTO target_table SELECT field_a FROM source_table;";

            FieldLineage lineage = parser.parse(sql, "job-semicolon");

            assertThat(lineage.getSourceTable()).isEqualTo("source_table");
            assertThat(lineage.getTargetTable()).isEqualTo("target_table");
            assertThat(lineage.getFieldMappings()).hasSize(1);
        }
    }
}