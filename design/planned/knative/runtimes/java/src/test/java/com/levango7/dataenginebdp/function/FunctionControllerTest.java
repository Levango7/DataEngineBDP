package com.levango7.dataenginebdp.function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FunctionController} 单元测试。
 *
 * <p>使用 MockMvc standaloneSetup（不加载完整 Spring 上下文）验证：
 * <ul>
 *   <li>GET /api/v1/health 返回 200 及正确健康信息</li>
 *   <li>POST /api/v1/invoke 成功调用 echo 函数并返回 200</li>
 *   <li>POST /api/v1/invoke 缺省请求头时使用默认函数名</li>
 *   <li>POST /api/v1/invoke 无请求体时使用空 Map</li>
 *   <li>invocation 计量被正确记录</li>
 * </ul>
 *
 * <p>{@code @Value} 注入字段通过 {@link ReflectionTestUtils} 设置，
 * {@link InvocationMetrics} 通过 Mockito mock 替换。</p>
 */
@DisplayName("FunctionController 函数调用控制器")
class FunctionControllerTest {

    private static final String DEFAULT_FUNCTION = "default";
    private static final String DEFAULT_TENANT = "default-tenant";

    private InvocationMetrics invocationMetrics;
    private FunctionController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        invocationMetrics = mock(InvocationMetrics.class);
        controller = new FunctionController(invocationMetrics);
        ReflectionTestUtils.setField(controller, "defaultFunctionName", DEFAULT_FUNCTION);
        ReflectionTestUtils.setField(controller, "defaultTenantId", DEFAULT_TENANT);
        // standaloneSetup 不加载 Security/PostConstruct，纯粹测试控制器 HTTP 语义
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Nested
    @DisplayName("GET /api/v1/health 端点")
    class HealthEndpoint {

        @Test
        @DisplayName("应返回 200 OK")
        void healthReturns200() throws Exception {
            mockMvc.perform(get("/api/v1/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("响应体 status 字段应为 UP")
        void healthStatusIsUp() throws Exception {
            mockMvc.perform(get("/api/v1/health"))
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        @DisplayName("响应体 runtime 字段应为 java")
        void healthRuntimeIsJava() throws Exception {
            mockMvc.perform(get("/api/v1/health"))
                    .andExpect(jsonPath("$.runtime").value("java"));
        }

        @Test
        @DisplayName("响应体 function 字段应为默认函数名")
        void healthFunctionIsDefault() throws Exception {
            mockMvc.perform(get("/api/v1/health"))
                    .andExpect(jsonPath("$.function").value(DEFAULT_FUNCTION));
        }

        @Test
        @DisplayName("health 端点不应触发 invocation 计量")
        void healthDoesNotRecordMetrics() throws Exception {
            mockMvc.perform(get("/api/v1/health"));
            verifyNoInteractions(invocationMetrics);
        }
    }

    @Nested
    @DisplayName("POST /api/v1/invoke 端点")
    class InvokeEndpoint {

        @Test
        @DisplayName("带请求头与请求体应返回 200 及 echo 结果")
        void invokeWithHeaderAndBodyReturnsEcho() throws Exception {
            mockMvc.perform(post("/api/v1/invoke")
                            .header("X-Tenant-Id", "tenant-1")
                            .header("X-Function-Name", "echo-fn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"value\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.runtime").value("java"))
                    .andExpect(jsonPath("$.function").value("echo-fn"))
                    .andExpect(jsonPath("$.echo.key").value("value"))
                    .andExpect(jsonPath("$.message")
                            .value("Hello from Java function runtime"));
        }

        @Test
        @DisplayName("成功调用应记录 success 计量")
        void invokeRecordsSuccessMetrics() throws Exception {
            mockMvc.perform(post("/api/v1/invoke")
                            .header("X-Tenant-Id", "tenant-1")
                            .header("X-Function-Name", "echo-fn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            verify(invocationMetrics).record(
                    eq("tenant-1"), eq("echo-fn"), eq("success"), anyLong());
        }

        @Test
        @DisplayName("缺省 X-Function-Name 头应使用默认函数名")
        void invokeWithoutFunctionHeaderUsesDefault() throws Exception {
            mockMvc.perform(post("/api/v1/invoke")
                            .header("X-Tenant-Id", "tenant-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.function").value(DEFAULT_FUNCTION));
        }

        @Test
        @DisplayName("缺省 X-Tenant-Id 头应使用默认租户")
        void invokeWithoutTenantHeaderUsesDefault() throws Exception {
            mockMvc.perform(post("/api/v1/invoke")
                            .header("X-Function-Name", "echo-fn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            verify(invocationMetrics).record(
                    eq(DEFAULT_TENANT), eq("echo-fn"), eq("success"), anyLong());
        }

        @Test
        @DisplayName("无请求体应返回 200 且 echo 为空对象")
        void invokeWithoutBodyReturnsEmptyEcho() throws Exception {
            mockMvc.perform(post("/api/v1/invoke")
                            .header("X-Tenant-Id", "tenant-1")
                            .header("X-Function-Name", "echo-fn"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.runtime").value("java"))
                    .andExpect(jsonPath("$.function").value("echo-fn"))
                    .andExpect(jsonPath("$.echo").exists());
        }

        @Test
        @DisplayName("带嵌套 JSON 请求体应原样回显")
        void invokeWithNestedBodyEchoesNested() throws Exception {
            mockMvc.perform(post("/api/v1/invoke")
                            .header("X-Tenant-Id", "tenant-1")
                            .header("X-Function-Name", "echo-fn")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nested\":{\"a\":1,\"b\":2},\"list\":[1,2,3]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.echo.nested.a").value(1))
                    .andExpect(jsonPath("$.echo.nested.b").value(2))
                    .andExpect(jsonPath("$.echo.list[0]").value(1))
                    .andExpect(jsonPath("$.echo.list[2]").value(3));
        }
    }

    @Nested
    @DisplayName("warmup 生命周期")
    class WarmupLifecycle {

        @Test
        @DisplayName("warmup 应以默认租户和函数名调用 metrics.warmup")
        void warmupCallsMetricsWarmup() {
            controller.warmup();

            verify(invocationMetrics).warmup(DEFAULT_TENANT, DEFAULT_FUNCTION);
        }
    }
}