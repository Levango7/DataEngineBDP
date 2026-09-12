package com.levango7.dataenginebdp.function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InvocationResponse} 单元测试。
 *
 * <p>验证响应 DTO 的构造、null body 兜底、工厂方法及 getter 行为。</p>
 */
@DisplayName("InvocationResponse 函数调用响应 DTO")
class InvocationResponseTest {

    @Nested
    @DisplayName("构造函数")
    class Constructor {

        @Test
        @DisplayName("应正确保存状态、状态码和响应体")
        void constructorStoresAllFields() {
            Map<String, Object> body = new HashMap<>();
            body.put("result", "ok");

            InvocationResponse response = new InvocationResponse("success", 200, body);

            assertThat(response.getStatus()).isEqualTo("success");
            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.getBody()).isSameAs(body);
        }

        @Test
        @DisplayName("null body 应兜底为空 HashMap")
        void nullBodyBecomesEmptyMap() {
            InvocationResponse response = new InvocationResponse("error", 500, null);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody()).isEmpty();
        }

        @Test
        @DisplayName("空 Map body 应保持空 Map（不替换）")
        void emptyBodyRemainsEmptyMap() {
            Map<String, Object> emptyBody = new HashMap<>();
            InvocationResponse response = new InvocationResponse("success", 200, emptyBody);

            assertThat(response.getBody()).isSameAs(emptyBody);
            assertThat(response.getBody()).isEmpty();
        }
    }

    @Nested
    @DisplayName("success 工厂方法")
    class SuccessFactory {

        @Test
        @DisplayName("应创建 status=success 的响应")
        void successCreatesSuccessStatus() {
            InvocationResponse response = InvocationResponse.success(new HashMap<>());

            assertThat(response.getStatus()).isEqualTo("success");
        }

        @Test
        @DisplayName("应创建 statusCode=200 的响应")
        void successCreates200StatusCode() {
            InvocationResponse response = InvocationResponse.success(new HashMap<>());

            assertThat(response.getStatusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("应携带传入的 body")
        void successCarriesBody() {
            Map<String, Object> body = new HashMap<>();
            body.put("data", "value");

            InvocationResponse response = InvocationResponse.success(body);

            assertThat(response.getBody()).isSameAs(body);
        }

        @Test
        @DisplayName("null body 应兜底为空 Map")
        void successWithNullBodyBecomesEmptyMap() {
            InvocationResponse response = InvocationResponse.success(null);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody()).isEmpty();
        }
    }

    @Nested
    @DisplayName("error 工厂方法")
    class ErrorFactory {

        @Test
        @DisplayName("应创建 status=error 的响应")
        void errorCreatesErrorStatus() {
            InvocationResponse response = InvocationResponse.error("boom", 500);

            assertThat(response.getStatus()).isEqualTo("error");
        }

        @Test
        @DisplayName("应使用传入的状态码")
        void errorUsesProvidedStatusCode() {
            InvocationResponse response = InvocationResponse.error("not found", 404);

            assertThat(response.getStatusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("body 应包含 error 字段并携带错误消息")
        void errorBodyContainsErrorMessage() {
            InvocationResponse response = InvocationResponse.error("something failed", 500);

            assertThat(response.getBody()).containsEntry("error", "something failed");
        }

        @Test
        @DisplayName("null 错误消息应存为 null error 字段")
        void errorWithNullMessageStoresNull() {
            InvocationResponse response = InvocationResponse.error(null, 500);

            assertThat(response.getBody()).containsKey("error");
            assertThat(response.getBody().get("error")).isNull();
        }
    }

    @Nested
    @DisplayName("getter 方法")
    class Getters {

        @Test
        @DisplayName("getStatus 应返回构造时传入的状态")
        void getStatusReturnsConstructorValue() {
            InvocationResponse response = new InvocationResponse("timeout", 408, null);

            assertThat(response.getStatus()).isEqualTo("timeout");
        }

        @Test
        @DisplayName("getStatusCode 应返回构造时传入的状态码")
        void getStatusCodeReturnsConstructorValue() {
            InvocationResponse response = new InvocationResponse("error", 503, null);

            assertThat(response.getStatusCode()).isEqualTo(503);
        }

        @Test
        @DisplayName("getBody 应返回构造时传入的响应体")
        void getBodyReturnsConstructorValue() {
            Map<String, Object> body = new HashMap<>();
            body.put("k", "v");
            InvocationResponse response = new InvocationResponse("success", 200, body);

            assertThat(response.getBody()).containsEntry("k", "v");
        }
    }
}