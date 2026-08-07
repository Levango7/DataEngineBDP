package com.shuqing.bigdata.flinkcdc;

import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChangeRecordJsonSerializer} 单元测试。
 *
 * @author shuqing-bigdata
 */
class ChangeRecordJsonSerializerTest {

    @Nested
    @DisplayName("toJsonString — JSON 序列化")
    class ToJsonStringTest {

        @Test
        @DisplayName("INSERT 记录 — before 为 null")
        void insertRecord() {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("id", 1);
            after.put("name", "alice");
            ChangeRecord record = new ChangeRecord(null, after, "c", null, 1000L);

            String json = ChangeRecordJsonSerializer.toJsonString(record);
            assertThat(json).contains("\"before\":null");
            assertThat(json).contains("\"after\":{\"id\":1,\"name\":\"alice\"}");
            assertThat(json).contains("\"op\":\"c\"");
            assertThat(json).contains("\"ts_ms\":1000");
        }

        @Test
        @DisplayName("UPDATE 记录 — before 和 after 均存在")
        void updateRecord() {
            Map<String, Object> before = Map.of("id", 1, "name", "old");
            Map<String, Object> after = Map.of("id", 1, "name", "new");
            ChangeRecord record = new ChangeRecord(before, after, "u", null, 2000L);

            String json = ChangeRecordJsonSerializer.toJsonString(record);
            assertThat(json).contains("\"op\":\"u\"");
            assertThat(json).contains("\"name\":\"old\"");
            assertThat(json).contains("\"name\":\"new\"");
        }

        @Test
        @DisplayName("DELETE 记录 — after 为 null")
        void deleteRecord() {
            Map<String, Object> before = Map.of("id", 5);
            ChangeRecord record = new ChangeRecord(before, null, "d", null, 3000L);

            String json = ChangeRecordJsonSerializer.toJsonString(record);
            assertThat(json).contains("\"after\":null");
            assertThat(json).contains("\"op\":\"d\"");
        }

        @Test
        @DisplayName("包含 source 元数据")
        void withSource() {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("db", "shop");
            source.put("table", "orders");
            source.put("pos", 1234);
            ChangeRecord record = new ChangeRecord(null, Map.of("id", 1), "c", source, 100L);

            String json = ChangeRecordJsonSerializer.toJsonString(record);
            assertThat(json).contains("\"source\":{\"db\":\"shop\",\"table\":\"orders\",\"pos\":1234}");
        }

        @Test
        @DisplayName("null ts_ms — 输出 null")
        void nullTsMs() {
            ChangeRecord record = new ChangeRecord(null, null, "c", null, null);
            String json = ChangeRecordJsonSerializer.toJsonString(record);
            assertThat(json).contains("\"ts_ms\":null");
        }

        @Test
        @DisplayName("null op — 输出 null")
        void nullOp() {
            ChangeRecord record = new ChangeRecord(null, null, (String) null, null, null);
            String json = ChangeRecordJsonSerializer.toJsonString(record);
            assertThat(json).contains("\"op\":null");
        }

        @Test
        @DisplayName("特殊字符转义 — 引号和反斜杠")
        void escapeSpecialChars() {
            Map<String, Object> after = Map.of("text", "hello \"world\" \\ test");
            ChangeRecord record = new ChangeRecord(null, after, "c", null, 1L);

            String json = ChangeRecordJsonSerializer.toJsonString(record);
            assertThat(json).contains("\\\"world\\\"");
            assertThat(json).contains("\\\\ test");
        }

        @Test
        @DisplayName("换行符转义")
        void escapeNewline() {
            Map<String, Object> after = Map.of("text", "line1\nline2");
            ChangeRecord record = new ChangeRecord(null, after, "c", null, 1L);

            String json = ChangeRecordJsonSerializer.toJsonString(record);
            assertThat(json).contains("line1\\nline2");
            assertThat(json).doesNotContain("line1\nline2");
        }

        @Test
        @DisplayName("Boolean 值 — 不加引号")
        void booleanValue() {
            Map<String, Object> after = Map.of("active", true, "deleted", false);
            ChangeRecord record = new ChangeRecord(null, after, "c", null, 1L);

            String json = ChangeRecordJsonSerializer.toJsonString(record);
            assertThat(json).contains("\"active\":true");
            assertThat(json).contains("\"deleted\":false");
        }

        @Test
        @DisplayName("Number 值 — 不加引号")
        void numberValue() {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("count", 42L);
            after.put("price", 99.9);
            ChangeRecord record = new ChangeRecord(null, after, "c", null, 1L);

            String json = ChangeRecordJsonSerializer.toJsonString(record);
            assertThat(json).contains("\"count\":42");
            assertThat(json).contains("\"price\":99.9");
        }

        @Test
        @DisplayName("null record — 抛出 NPE")
        void nullRecord_throwsNpe() {
            assertThatThrownBy(() -> ChangeRecordJsonSerializer.toJsonString(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("toJson — 字节数组")
    class ToJsonTest {

        @Test
        @DisplayName("返回 UTF-8 字节数组")
        void toJson_returnsUtf8Bytes() {
            ChangeRecord record = new ChangeRecord(null, Map.of("id", 1), "c", null, 1L);
            byte[] bytes = ChangeRecordJsonSerializer.toJson(record);

            String decoded = new String(bytes, StandardCharsets.UTF_8);
            assertThat(decoded).contains("\"op\":\"c\"");
            assertThat(decoded).contains("\"id\":1");
        }

        @Test
        @DisplayName("null record — 抛出 NPE")
        void toJson_nullRecord_throwsNpe() {
            assertThatThrownBy(() -> ChangeRecordJsonSerializer.toJson(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("中文字符正确编码")
        void toJson_chineseCharacters() {
            Map<String, Object> after = Map.of("name", "张三");
            ChangeRecord record = new ChangeRecord(null, after, "c", null, 1L);
            byte[] bytes = ChangeRecordJsonSerializer.toJson(record);

            String decoded = new String(bytes, StandardCharsets.UTF_8);
            assertThat(decoded).contains("张三");
        }
    }
}