package com.levango7.dataenginebdp.flinkcdc.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SchemaRegistryClient} 单元测试，使用 Mockito mock HttpClient。
 *
 * @author shuqing-bigdata
 */
class SchemaRegistryClientTest {

    /**
     * 构造 mock HttpClient，对任意请求返回指定状态码与响应体。
     *
     * @param statusCode HTTP 状态码
     * @param body       响应体
     * @return mock HttpClient
     * @throws Exception mock 设置异常
     */
    @SuppressWarnings("unchecked")
    private static HttpClient mockClient(int statusCode, String body) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        return client;
    }

    @Nested
    @DisplayName("SchemaType 枚举")
    class SchemaTypeTest {

        @Test
        @DisplayName("fromCode — 正确解析各类型")
        void fromCode_shouldParseAllTypes() {
            assertThat(SchemaRegistryClient.SchemaType.fromCode("AVRO"))
                    .isEqualTo(SchemaRegistryClient.SchemaType.AVRO);
            assertThat(SchemaRegistryClient.SchemaType.fromCode("JSON"))
                    .isEqualTo(SchemaRegistryClient.SchemaType.JSON);
            assertThat(SchemaRegistryClient.SchemaType.fromCode("PROTOBUF"))
                    .isEqualTo(SchemaRegistryClient.SchemaType.PROTOBUF);
        }

        @Test
        @DisplayName("fromCode — 大小写不敏感")
        void fromCode_shouldBeCaseInsensitive() {
            assertThat(SchemaRegistryClient.SchemaType.fromCode("avro"))
                    .isEqualTo(SchemaRegistryClient.SchemaType.AVRO);
            assertThat(SchemaRegistryClient.SchemaType.fromCode("Json"))
                    .isEqualTo(SchemaRegistryClient.SchemaType.JSON);
        }

        @Test
        @DisplayName("fromCode — 未知类型抛出异常")
        void fromCode_unknown_shouldThrow() {
            assertThatThrownBy(() -> SchemaRegistryClient.SchemaType.fromCode("xml"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("fromCode — null 抛出 NPE")
        void fromCode_null_shouldThrowNpe() {
            assertThatThrownBy(() -> SchemaRegistryClient.SchemaType.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("code — 返回正确编码")
        void code_shouldReturnCorrectCodes() {
            assertThat(SchemaRegistryClient.SchemaType.AVRO.code()).isEqualTo("AVRO");
            assertThat(SchemaRegistryClient.SchemaType.JSON.code()).isEqualTo("JSON");
            assertThat(SchemaRegistryClient.SchemaType.PROTOBUF.code()).isEqualTo("PROTOBUF");
        }
    }

    @Nested
    @DisplayName("CompatibilityLevel 枚举")
    class CompatibilityLevelTest {

        @Test
        @DisplayName("code — 返回正确编码")
        void code_shouldReturnCorrectCodes() {
            assertThat(SchemaRegistryClient.CompatibilityLevel.BACKWARD.code()).isEqualTo("BACKWARD");
            assertThat(SchemaRegistryClient.CompatibilityLevel.FULL.code()).isEqualTo("FULL");
            assertThat(SchemaRegistryClient.CompatibilityLevel.NONE.code()).isEqualTo("NONE");
        }
    }

    @Nested
    @DisplayName("Schema 注册")
    class RegisterTest {

        @Test
        @DisplayName("注册成功 — 返回版本号")
        void registerSuccess() throws Exception {
            HttpClient client = mockClient(200, "{\"id\":1,\"version\":3,\"schema\":\"...\"}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            int version = src.register("shop.orders-value",
                    "{\"type\":\"record\",\"name\":\"orders\"}",
                    SchemaRegistryClient.SchemaType.AVRO);

            assertThat(version).isEqualTo(3);
        }

        @Test
        @DisplayName("注册失败 — HTTP 409 抛出 IOException")
        void registerFailure() throws Exception {
            HttpClient client = mockClient(409, "{\"error_code\":409,\"message\":\"Incompatible\"}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.register("shop.orders-value", "{}",
                    SchemaRegistryClient.SchemaType.AVRO))
                    .isInstanceOf(java.io.IOException.class);
        }

        @Test
        @DisplayName("null subject — 抛出 NPE")
        void nullSubject() throws Exception {
            HttpClient client = mockClient(200, "{}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.register(null, "{}",
                    SchemaRegistryClient.SchemaType.AVRO))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Schema 查询")
    class GetSchemaTest {

        @Test
        @DisplayName("getLatestSchema — 返回 schema 字符串")
        void getLatest() throws Exception {
            String schemaJson = "{\"type\":\"record\",\"name\":\"orders\",\"fields\":[]}";
            HttpClient client = mockClient(200,
                    "{\"subject\":\"shop.orders-value\",\"version\":2,\"id\":5,\"schema\":\""
                            + escapeForJson(schemaJson) + "\"}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            String schema = src.getLatestSchema("shop.orders-value");
            assertThat(schema).isEqualTo(schemaJson);
        }

        @Test
        @DisplayName("getSchema(version) — 返回指定版本 schema")
        void getVersion() throws Exception {
            String schemaJson = "{\"type\":\"record\",\"name\":\"v1\"}";
            HttpClient client = mockClient(200,
                    "{\"subject\":\"shop.orders-value\",\"version\":1,\"id\":1,\"schema\":\""
                            + escapeForJson(schemaJson) + "\"}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            String schema = src.getSchema("shop.orders-value", 1);
            assertThat(schema).isEqualTo(schemaJson);
        }

        @Test
        @DisplayName("getSchema(0) — 抛出 IllegalArgumentException")
        void getInvalidVersion() throws Exception {
            HttpClient client = mockClient(200, "{}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.getSchema("test", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("兼容性检查")
    class CompatibilityTest {

        @Test
        @DisplayName("兼容 — 返回 true")
        void compatible() throws Exception {
            HttpClient client = mockClient(200, "{\"compatible\":true}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            boolean result = src.checkCompatibility("shop.orders-value", "{}",
                    SchemaRegistryClient.SchemaType.AVRO);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("不兼容 — 返回 false")
        void incompatible() throws Exception {
            HttpClient client = mockClient(200, "{\"compatible\":false}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            boolean result = src.checkCompatibility("shop.orders-value", "{}",
                    SchemaRegistryClient.SchemaType.AVRO);
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("列出 Subject 与版本")
    class ListTest {

        @Test
        @DisplayName("listSubjects — 返回 Subject 列表")
        void listSubjects() throws Exception {
            HttpClient client = mockClient(200, "[\"subject-1\",\"subject-2\",\"subject-3\"]");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            List<String> subjects = src.listSubjects();
            assertThat(subjects).containsExactly("subject-1", "subject-2", "subject-3");
        }

        @Test
        @DisplayName("listVersions — 返回版本号列表")
        void listVersions() throws Exception {
            HttpClient client = mockClient(200, "[1,2,3]");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            List<Integer> versions = src.listVersions("shop.orders-value");
            assertThat(versions).containsExactly(1, 2, 3);
        }
    }

    @Nested
    @DisplayName("Builder 配置")
    class BuilderTest {

        @Test
        @DisplayName("默认配置 — 正确")
        void defaults() throws Exception {
            HttpClient client = mock(HttpClient.class);
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThat(src).isNotNull();
        }

        @Test
        @DisplayName("null URL — 抛出 NPE")
        void nullUrl() {
            assertThatThrownBy(() -> SchemaRegistryClient.builder().url(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("buildRegisterBody — 请求体构造")
    class BuildBodyTest {

        @Test
        @DisplayName("AVRO — 包含 schemaType=AVRO")
        void avroBody() {
            String body = SchemaRegistryClient.buildRegisterBody(
                    "{\"type\":\"record\"}", SchemaRegistryClient.SchemaType.AVRO);
            assertThat(body).contains("\"schemaType\":\"AVRO\"");
            assertThat(body).contains("\"schema\":\"{\\\"type\\\":\\\"record\\\"}\"");
        }

        @Test
        @DisplayName("JSON — 包含 schemaType=JSON")
        void jsonBody() {
            String body = SchemaRegistryClient.buildRegisterBody(
                    "{}", SchemaRegistryClient.SchemaType.JSON);
            assertThat(body).contains("\"schemaType\":\"JSON\"");
        }

        @Test
        @DisplayName("特殊字符转义 — 换行符")
        void escapeNewline() {
            String body = SchemaRegistryClient.buildRegisterBody(
                    "line1\nline2", SchemaRegistryClient.SchemaType.AVRO);
            assertThat(body).contains("line1\\nline2");
            assertThat(body).doesNotContain("line1\nline2");
        }
    }

    /**
     * 转义 JSON 字符串中的特殊字符（用于构造 mock 响应体）。
     *
     * @param s 原始字符串
     * @return 转义后字符串
     */
    private static String escapeForJson(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    // ===== 补充测试：异常路径与未覆盖方法 =====

    @Nested
    @DisplayName("register 异常处理")
    class RegisterErrorTest {

        @Test
        @DisplayName("version 非 Number — 抛出 IOException")
        void versionNotNumber() throws Exception {
            HttpClient client = mockClient(200, "{\"id\":1,\"version\":\"abc\"}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.register("s", "{}",
                    SchemaRegistryClient.SchemaType.AVRO))
                    .isInstanceOf(java.io.IOException.class);
        }

        @Test
        @DisplayName("null schema — 抛出 NPE")
        void nullSchema() throws Exception {
            HttpClient client = mockClient(200, "{}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.register("s", null,
                    SchemaRegistryClient.SchemaType.AVRO))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null type — 抛出 NPE")
        void nullType() throws Exception {
            HttpClient client = mockClient(200, "{}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.register("s", "{}", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("getLatestSchema 异常处理")
    class GetLatestSchemaErrorTest {

        @Test
        @DisplayName("HTTP 错误 — 抛出 IOException")
        void httpError() throws Exception {
            HttpClient client = mockClient(404, "{\"error_code\":40402,\"message\":\"Not Found\"}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.getLatestSchema("unknown-subject"))
                    .isInstanceOf(java.io.IOException.class);
        }

        @Test
        @DisplayName("null subject — 抛出 NPE")
        void nullSubject() throws Exception {
            HttpClient client = mockClient(200, "{}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.getLatestSchema(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("schema 字段为 null — 返回 null")
        void schemaFieldNull() throws Exception {
            HttpClient client = mockClient(200, "{\"subject\":\"s\",\"version\":1,\"id\":1}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            String schema = src.getLatestSchema("s");
            assertThat(schema).isNull();
        }
    }

    @Nested
    @DisplayName("getSchema 异常处理")
    class GetSchemaErrorTest {

        @Test
        @DisplayName("HTTP 错误 — 抛出 IOException")
        void httpError() throws Exception {
            HttpClient client = mockClient(404, "{\"error_code\":40402}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.getSchema("s", 1))
                    .isInstanceOf(java.io.IOException.class);
        }

        @Test
        @DisplayName("null subject — 抛出 NPE")
        void nullSubject() throws Exception {
            HttpClient client = mockClient(200, "{}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.getSchema(null, 1))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("schema 字段为 null — 返回 null")
        void schemaFieldNull() throws Exception {
            HttpClient client = mockClient(200, "{\"subject\":\"s\",\"version\":1,\"id\":1}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            String schema = src.getSchema("s", 1);
            assertThat(schema).isNull();
        }
    }

    @Nested
    @DisplayName("checkCompatibility 异常处理")
    class CheckCompatibilityErrorTest {

        @Test
        @DisplayName("HTTP 错误 — 抛出 IOException")
        void httpError() throws Exception {
            HttpClient client = mockClient(409, "{\"error_code\":409}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.checkCompatibility("s", "{}",
                    SchemaRegistryClient.SchemaType.AVRO))
                    .isInstanceOf(java.io.IOException.class);
        }

        @Test
        @DisplayName("null subject — 抛出 NPE")
        void nullSubject() throws Exception {
            HttpClient client = mockClient(200, "{}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.checkCompatibility(null, "{}",
                    SchemaRegistryClient.SchemaType.AVRO))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null schema — 抛出 NPE")
        void nullSchema() throws Exception {
            HttpClient client = mockClient(200, "{}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.checkCompatibility("s", null,
                    SchemaRegistryClient.SchemaType.AVRO))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null type — 抛出 NPE")
        void nullType() throws Exception {
            HttpClient client = mockClient(200, "{}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.checkCompatibility("s", "{}", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("compatible 为字符串 'true' — 正确解析")
        void compatibleAsString() throws Exception {
            HttpClient client = mockClient(200, "{\"compatible\":\"true\"}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            boolean result = src.checkCompatibility("s", "{}",
                    SchemaRegistryClient.SchemaType.AVRO);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("compatible 为字符串 'false' — 正确解析")
        void incompatibleAsString() throws Exception {
            HttpClient client = mockClient(200, "{\"compatible\":\"false\"}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            boolean result = src.checkCompatibility("s", "{}",
                    SchemaRegistryClient.SchemaType.AVRO);
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("setCompatibility")
    class SetCompatibilityTest {

        @Test
        @DisplayName("全局配置 — subject=null")
        void globalConfig() throws Exception {
            HttpClient client = mockClient(200, "{\"compatibility\":\"FULL\"}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            boolean result = src.setCompatibility(null,
                    SchemaRegistryClient.CompatibilityLevel.FULL);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Subject 级配置 — subject 非 null")
        void subjectConfig() throws Exception {
            HttpClient client = mockClient(200, "{\"compatibility\":\"BACKWARD\"}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            boolean result = src.setCompatibility("shop.orders-value",
                    SchemaRegistryClient.CompatibilityLevel.BACKWARD);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("HTTP 错误 — 抛出 IOException")
        void httpError() throws Exception {
            HttpClient client = mockClient(500, "{\"error_code\":500}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.setCompatibility("s",
                    SchemaRegistryClient.CompatibilityLevel.NONE))
                    .isInstanceOf(java.io.IOException.class);
        }

        @Test
        @DisplayName("null level — 抛出 NPE")
        void nullLevel() throws Exception {
            HttpClient client = mockClient(200, "{}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.setCompatibility("s", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("deleteSubject")
    class DeleteSubjectTest {

        @Test
        @DisplayName("删除成功 — 返回 true")
        void deleteSuccess() throws Exception {
            HttpClient client = mockClient(200, "[1,2,3]");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            boolean result = src.deleteSubject("shop.orders-value");
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("HTTP 错误 — 抛出 IOException")
        void httpError() throws Exception {
            HttpClient client = mockClient(404, "{\"error_code\":40402}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.deleteSubject("unknown"))
                    .isInstanceOf(java.io.IOException.class);
        }

        @Test
        @DisplayName("null subject — 抛出 NPE")
        void nullSubject() throws Exception {
            HttpClient client = mockClient(200, "{}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.deleteSubject(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("listSubjects/listVersions 异常处理")
    class ListErrorTest {

        @Test
        @DisplayName("listSubjects HTTP 错误 — 抛出 IOException")
        void listSubjectsError() throws Exception {
            HttpClient client = mockClient(500, "{\"error_code\":500}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(src::listSubjects)
                    .isInstanceOf(java.io.IOException.class);
        }

        @Test
        @DisplayName("listVersions HTTP 错误 — 抛出 IOException")
        void listVersionsError() throws Exception {
            HttpClient client = mockClient(404, "{\"error_code\":40402}");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.listVersions("unknown"))
                    .isInstanceOf(java.io.IOException.class);
        }

        @Test
        @DisplayName("listVersions null subject — 抛出 NPE")
        void listVersionsNullSubject() throws Exception {
            HttpClient client = mockClient(200, "[]");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            assertThatThrownBy(() -> src.listVersions(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("listVersions 字符串版本号 — 正确解析")
        void listVersionsStringValues() throws Exception {
            HttpClient client = mockClient(200, "[\"1\",\"2\",\"3\"]");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            List<Integer> versions = src.listVersions("s");
            assertThat(versions).containsExactly(1, 2, 3);
        }
    }

    @Nested
    @DisplayName("Builder 异常处理")
    class BuilderErrorTest {

        @Test
        @DisplayName("null requestTimeout — 抛出 NPE")
        void nullRequestTimeout() {
            assertThatThrownBy(() -> SchemaRegistryClient.builder().requestTimeout(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("URL 末尾斜杠 — 自动去除")
        void urlTrailingSlash() throws Exception {
            HttpClient client = mockClient(200, "[\"s1\"]");
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081/")
                    .httpClient(client)
                    .build();

            List<String> subjects = src.listSubjects();
            assertThat(subjects).containsExactly("s1");
        }
    }

    @Nested
    @DisplayName("close")
    class CloseTest {

        @Test
        @DisplayName("close — 不抛出异常")
        void closeNoException() throws Exception {
            HttpClient client = mock(HttpClient.class);
            SchemaRegistryClient src = SchemaRegistryClient.builder()
                    .url("http://localhost:8081")
                    .httpClient(client)
                    .build();

            src.close();
            // HttpClient 复用，无需验证关闭
        }
    }

    @Nested
    @DisplayName("buildRegisterBody 特殊字符转义")
    class EscapeTest {

        @Test
        @DisplayName("回车符 — 转义为 \\r")
        void escapeCarriageReturn() {
            String body = SchemaRegistryClient.buildRegisterBody(
                    "a\rb", SchemaRegistryClient.SchemaType.AVRO);
            assertThat(body).contains("a\\rb");
            assertThat(body).doesNotContain("a\rb");
        }

        @Test
        @DisplayName("制表符 — 转义为 \\t")
        void escapeTab() {
            String body = SchemaRegistryClient.buildRegisterBody(
                    "a\tb", SchemaRegistryClient.SchemaType.AVRO);
            assertThat(body).contains("a\\tb");
            assertThat(body).doesNotContain("a\tb");
        }

        @Test
        @DisplayName("反斜杠 — 转义为 \\\\")
        void escapeBackslash() {
            String body = SchemaRegistryClient.buildRegisterBody(
                    "a\\b", SchemaRegistryClient.SchemaType.AVRO);
            assertThat(body).contains("a\\\\b");
        }

        @Test
        @DisplayName("PROTOBUF — 包含 schemaType=PROTOBUF")
        void protobufBody() {
            String body = SchemaRegistryClient.buildRegisterBody(
                    "syntax = \"proto3\";", SchemaRegistryClient.SchemaType.PROTOBUF);
            assertThat(body).contains("\"schemaType\":\"PROTOBUF\"");
        }
    }

    @Nested
    @DisplayName("CompatibilityLevel 完整枚举")
    class CompatibilityLevelFullTest {

        @Test
        @DisplayName("所有级别 — code 正确")
        void allLevels() {
            assertThat(SchemaRegistryClient.CompatibilityLevel.NONE.code()).isEqualTo("NONE");
            assertThat(SchemaRegistryClient.CompatibilityLevel.BACKWARD.code()).isEqualTo("BACKWARD");
            assertThat(SchemaRegistryClient.CompatibilityLevel.BACKWARD_TRANSITIVE.code())
                    .isEqualTo("BACKWARD_TRANSITIVE");
            assertThat(SchemaRegistryClient.CompatibilityLevel.FORWARD.code()).isEqualTo("FORWARD");
            assertThat(SchemaRegistryClient.CompatibilityLevel.FORWARD_TRANSITIVE.code())
                    .isEqualTo("FORWARD_TRANSITIVE");
            assertThat(SchemaRegistryClient.CompatibilityLevel.FULL.code()).isEqualTo("FULL");
            assertThat(SchemaRegistryClient.CompatibilityLevel.FULL_TRANSITIVE.code())
                    .isEqualTo("FULL_TRANSITIVE");
        }
    }
}