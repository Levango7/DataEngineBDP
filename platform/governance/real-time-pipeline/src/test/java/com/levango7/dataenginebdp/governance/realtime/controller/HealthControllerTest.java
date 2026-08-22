package com.levango7.dataenginebdp.governance.realtime.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link HealthController} 单元测试。
 *
 * <p>使用 MockMvc standaloneSetup（不加载 Spring Security 上下文）验证：
 * <ul>
 *   <li>GET /api/v1/health 返回 200</li>
 *   <li>响应体包含 status=UP、component、version、timestamp 字段</li>
 *   <li>直接调用方法返回的 Map 结构正确</li>
 * </ul>
 */
@DisplayName("HealthController 健康检查端点")
class HealthControllerTest {

    private HealthController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new HealthController();
        // standaloneSetup 不加载 Security 配置，纯粹测试控制器 HTTP 语义
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Nested
    @DisplayName("HTTP 端点")
    class HttpEndpoint {

        @Test
        @DisplayName("GET /api/v1/health 应返回 200 OK")
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
        @DisplayName("响应体应包含 component 字段")
        void healthHasComponent() throws Exception {
            mockMvc.perform(get("/api/v1/health"))
                    .andExpect(jsonPath("$.component").value("real-time-governance-pipeline"));
        }

        @Test
        @DisplayName("响应体应包含 version 字段")
        void healthHasVersion() throws Exception {
            mockMvc.perform(get("/api/v1/health"))
                    .andExpect(jsonPath("$.version").value("0.1.0"));
        }

        @Test
        @DisplayName("响应体应包含 timestamp 字段")
        void healthHasTimestamp() throws Exception {
            mockMvc.perform(get("/api/v1/health"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    @Nested
    @DisplayName("直接方法调用")
    class DirectInvocation {

        @Test
        @DisplayName("health() 应返回包含 4 个键的 Map")
        void healthReturnsMapWithFourKeys() {
            Map<String, Object> body = controller.health();

            assertThat(body).hasSize(4);
            assertThat(body).containsKeys("status", "component", "version", "timestamp");
        }

        @Test
        @DisplayName("health() 应返回正确的状态信息")
        void healthReturnsCorrectStatusInfo() {
            Map<String, Object> body = controller.health();

            assertThat(body.get("status")).isEqualTo("UP");
            assertThat(body.get("component")).isEqualTo("real-time-governance-pipeline");
            assertThat(body.get("version")).isEqualTo("0.1.0");
            assertThat(body.get("timestamp")).isInstanceOf(String.class);
            assertThat((String) body.get("timestamp")).isNotBlank();
        }
    }
}