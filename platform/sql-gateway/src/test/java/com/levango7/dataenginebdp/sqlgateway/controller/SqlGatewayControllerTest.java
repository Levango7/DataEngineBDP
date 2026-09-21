package com.levango7.dataenginebdp.sqlgateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.sqlgateway.model.RouteRule;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteRequest;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteResponse;
import com.levango7.dataenginebdp.sqlgateway.service.SqlRoutingService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SqlGatewayController MockMvc 测试。
 */
@ExtendWith(MockitoExtension.class)
class SqlGatewayControllerTest {

    /** 测试租户 ID（模拟 JwtAuthFilter 认证后写入的上下文）。 */
    private static final String TEST_TENANT_ID = "test-tenant";

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SqlRoutingService routingService;

    @InjectMocks
    private SqlGatewayController sqlGatewayController;

    @BeforeEach
    void setUp() {
        // R10 起 requireTenant() 为 fail-closed：standalone MockMvc 未挂 JwtAuthFilter，
        // 需显式注入租户上下文，否则读写接口抛 IllegalStateException。
        TenantContext.setTenantId(TEST_TENANT_ID);
        TenantContext.setUserId("test-user");
        mockMvc = MockMvcBuilders.standaloneSetup(sqlGatewayController).build();
    }

    @AfterEach
    void tearDown() {
        // 清理 ThreadLocal，避免线程复用串号
        TenantContext.clear();
    }

    @Test
    @DisplayName("POST /api/v1/sql/execute — 执行SQL返回200")
    void executeSql_shouldReturn200() throws Exception {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setSql("SELECT 1");
        request.setEngine("trino");

        SqlExecuteResponse response = SqlExecuteResponse.builder()
                .queryId("q-001")
                .status("SUCCESS")
                .columns(List.of("1"))
                .rows(List.of(List.of(1)))
                .durationMs(100L)
                .engine("trino")
                .build();

        when(routingService.execute(any(SqlExecuteRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/sql/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryId").value("q-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.engine").value("trino"));
    }

    @Test
    @DisplayName("GET /api/v1/sql/routes — 列出路由规则返回200")
    void listRoutes_shouldReturn200() throws Exception {
        RouteRule rule = new RouteRule("SELECT", "trino", 1, true);
        when(routingService.listRoutes(TEST_TENANT_ID)).thenReturn(List.of(rule));

        mockMvc.perform(get("/api/v1/sql/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/sql/routes — 添加路由规则返回201 CREATED + Location 头")
    void addRoute_shouldReturn201() throws Exception {
        RouteRule input = new RouteRule("INSERT", "doris", 10, true);
        input.setId(null);

        RouteRule saved = new RouteRule("INSERT", "doris", 10, true);
        saved.setId(1L);

        when(routingService.addRoute(any(RouteRule.class), eq(TEST_TENANT_ID))).thenReturn(saved);

        mockMvc.perform(post("/api/v1/sql/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.engine").value("doris"));
    }

    @Test
    @DisplayName("GET /api/v1/sql/engines — 列出可用引擎返回200")
    void listEngines_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/sql/engines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("trino"))
                .andExpect(jsonPath("$[1]").value("doris"));
    }

    @Test
    @DisplayName("POST /api/v1/sql/execute — 降级响应返回200")
    void executeSql_degraded_shouldReturn200() throws Exception {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setSql("SELECT 1");

        SqlExecuteResponse response = SqlExecuteResponse.builder()
                .queryId("q-002")
                .status("DEGRADED")
                .columns(List.of())
                .rows(List.of())
                .durationMs(5000L)
                .engine("trino")
                .build();

        when(routingService.execute(any(SqlExecuteRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/sql/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"));
    }

    @Test
    @DisplayName("POST /api/v1/sql/execute — 缺租户上下文时 fail-closed（R10 安全语义）")
    void executeSql_withoutTenantContext_shouldFailClosed() {
        // 清除上下文，模拟未认证请求绕过 JwtAuthFilter 的极端情况
        TenantContext.clear();

        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setSql("SELECT 1");
        request.setEngine("trino");

        ServletException ex = assertThrows(ServletException.class, () ->
                mockMvc.perform(post("/api/v1/sql/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))));

        assertTrue(ex.getMessage().contains("缺少租户上下文")
                        || ex.getCause() instanceof IllegalStateException,
                "缺租户上下文时应 fail-closed 抛 IllegalStateException，实际: " + ex);
    }
}
