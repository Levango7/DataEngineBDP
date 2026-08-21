package com.shuqing.bigdata.flinkcdc.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChangeRecord} 模型单元测试。
 *
 * @author shuqing-bigdata
 */
class ChangeRecordTest {

    @Nested
    @DisplayName("Op 枚举")
    class OpEnumTest {

        @Test
        @DisplayName("fromCode — 正确解析各 Debezium 编码")
        void fromCode_shouldParseAllCodes() {
            assertThat(ChangeRecord.Op.fromCode("c")).isEqualTo(ChangeRecord.Op.INSERT);
            assertThat(ChangeRecord.Op.fromCode("u")).isEqualTo(ChangeRecord.Op.UPDATE);
            assertThat(ChangeRecord.Op.fromCode("d")).isEqualTo(ChangeRecord.Op.DELETE);
            assertThat(ChangeRecord.Op.fromCode("r")).isEqualTo(ChangeRecord.Op.SNAPSHOT);
        }

        @Test
        @DisplayName("fromCode — 大小写不敏感")
        void fromCode_shouldBeCaseInsensitive() {
            assertThat(ChangeRecord.Op.fromCode("C")).isEqualTo(ChangeRecord.Op.INSERT);
            assertThat(ChangeRecord.Op.fromCode("U")).isEqualTo(ChangeRecord.Op.UPDATE);
        }

        @Test
        @DisplayName("fromCode — null 抛出 NPE")
        void fromCode_null_shouldThrowNpe() {
            assertThatThrownBy(() -> ChangeRecord.Op.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("fromCode — 未知编码抛出 IllegalArgumentException")
        void fromCode_unknown_shouldThrow() {
            assertThatThrownBy(() -> ChangeRecord.Op.fromCode("x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("x");
        }

        @Test
        @DisplayName("code — 返回正确的 Debezium 编码")
        void code_shouldReturnCorrectCodes() {
            assertThat(ChangeRecord.Op.INSERT.code()).isEqualTo("c");
            assertThat(ChangeRecord.Op.UPDATE.code()).isEqualTo("u");
            assertThat(ChangeRecord.Op.DELETE.code()).isEqualTo("d");
            assertThat(ChangeRecord.Op.SNAPSHOT.code()).isEqualTo("r");
        }
    }

    @Nested
    @DisplayName("构造与字段存取")
    class ConstructionTest {

        @Test
        @DisplayName("全参构造器(Op) — 正确设置所有字段")
        void allArgsConstructor_withOpEnum() {
            Map<String, Object> before = Map.of("id", 1, "name", "old");
            Map<String, Object> after = Map.of("id", 1, "name", "new");
            Map<String, Object> source = Map.of("db", "shop", "table", "orders");

            ChangeRecord record = new ChangeRecord(before, after, ChangeRecord.Op.UPDATE, source, 1700000000000L);

            assertThat(record.getBefore()).isEqualTo(before);
            assertThat(record.getAfter()).isEqualTo(after);
            assertThat(record.getOp()).isEqualTo("u");
            assertThat(record.getSource()).isEqualTo(source);
            assertThat(record.getTsMs()).isEqualTo(1700000000000L);
        }

        @Test
        @DisplayName("全参构造器(String) — 正确设置 op 字符串")
        void allArgsConstructor_withOpCode() {
            ChangeRecord record = new ChangeRecord(null, Map.of("id", 1), "c", null, 100L);
            assertThat(record.getOp()).isEqualTo("c");
            assertThat(record.getBefore()).isNull();
        }

        @Test
        @DisplayName("默认构造器 + setter — 所有字段可存取")
        void defaultConstructor_withSetters() {
            ChangeRecord record = new ChangeRecord();
            record.setBefore(Map.of("id", 5));
            record.setAfter(null);
            record.setOp("d");
            record.setSource(Map.of("file", "binlog.000001"));
            record.setTsMs(999L);

            assertThat(record.getBefore()).containsEntry("id", 5);
            assertThat(record.getAfter()).isNull();
            assertThat(record.getOp()).isEqualTo("d");
            assertThat(record.getSource()).containsEntry("file", "binlog.000001");
            assertThat(record.getTsMs()).isEqualTo(999L);
        }

        @Test
        @DisplayName("op 为 null 时 opEnum 返回 null")
        void opEnum_nullOp_returnsNull() {
            ChangeRecord record = new ChangeRecord();
            record.setOp(null);
            assertThat(record.opEnum()).isNull();
        }

        @Test
        @DisplayName("op 为未知编码时 opEnum 返回 null（不抛异常）")
        void opEnum_unknownCode_returnsNull() {
            ChangeRecord record = new ChangeRecord();
            record.setOp("z");
            assertThat(record.opEnum()).isNull();
        }
    }

    @Nested
    @DisplayName("操作类型判断")
    class OperationCheckTest {

        @Test
        @DisplayName("isInsert — op=c 返回 true")
        void isInsert() {
            assertThat(new ChangeRecord(null, Map.of(), "c", null, 1L).isInsert()).isTrue();
            assertThat(new ChangeRecord(null, Map.of(), "u", null, 1L).isInsert()).isFalse();
        }

        @Test
        @DisplayName("isUpdate — op=u 返回 true")
        void isUpdate() {
            assertThat(new ChangeRecord(Map.of(), Map.of(), "u", null, 1L).isUpdate()).isTrue();
            assertThat(new ChangeRecord(Map.of(), Map.of(), "c", null, 1L).isUpdate()).isFalse();
        }

        @Test
        @DisplayName("isDelete — op=d 返回 true")
        void isDelete() {
            assertThat(new ChangeRecord(Map.of(), null, "d", null, 1L).isDelete()).isTrue();
            assertThat(new ChangeRecord(Map.of(), null, "c", null, 1L).isDelete()).isFalse();
        }

        @Test
        @DisplayName("isSnapshot — op=r 返回 true")
        void isSnapshot() {
            assertThat(new ChangeRecord(null, Map.of(), "r", null, 1L).isSnapshot()).isTrue();
            assertThat(new ChangeRecord(null, Map.of(), "c", null, 1L).isSnapshot()).isFalse();
        }
    }

    @Nested
    @DisplayName("equals / hashCode / toString")
    class ObjectMethodsTest {

        @Test
        @DisplayName("equals — 相同字段返回 true")
        void equals_sameFields() {
            ChangeRecord r1 = new ChangeRecord(Map.of("id", 1), Map.of("id", 2), "u", Map.of("db", "x"), 100L);
            ChangeRecord r2 = new ChangeRecord(Map.of("id", 1), Map.of("id", 2), "u", Map.of("db", "x"), 100L);
            assertThat(r1).isEqualTo(r2);
            assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
        }

        @Test
        @DisplayName("equals — 不同字段返回 false")
        void equals_differentFields() {
            ChangeRecord r1 = new ChangeRecord(Map.of("id", 1), Map.of("id", 2), "u", null, 100L);
            ChangeRecord r2 = new ChangeRecord(Map.of("id", 1), Map.of("id", 3), "u", null, 100L);
            assertThat(r1).isNotEqualTo(r2);
        }

        @Test
        @DisplayName("equals — 与 null 及其他类型比较")
        void equals_nullAndOtherType() {
            ChangeRecord record = new ChangeRecord(null, null, "c", null, null);
            assertThat(record).isNotEqualTo(null);
            assertThat(record).isNotEqualTo("not a record");
            assertThat(record).isEqualTo(record);
        }

        @Test
        @DisplayName("toString — 包含 op 和 tsMs")
        void toString_containsOpAndTsMs() {
            ChangeRecord record = new ChangeRecord(null, Map.of("id", 1), "c", null, 42L);
            String str = record.toString();
            assertThat(str).contains("op='c'").contains("tsMs=42");
        }
    }
}