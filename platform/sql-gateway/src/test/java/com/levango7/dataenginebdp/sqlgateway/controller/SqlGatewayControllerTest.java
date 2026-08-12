package com.levango7.dataenginebdp.sqlgateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.sqlgateway.model.RouteRule;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteRequest;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteResponse;
import com.levango7.dataenginebdp.sqlgateway.service.SqlRoutingService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SqlGatewayController MockMvc 测试。
 */
@ExtendWith(MockitoExtension.class)
class SqlGatewayControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SqlRoutingService routingService;

    @InjectMocks
    private SqlGatewayController sqlGatewayController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(sqlGatewayController).build();
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
        when(routingService.listRoutes()).thenReturn(List.of(rule));

        mockMvc.perform(get("/api/v1/sql/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/sql/routes — 添加路由规则返回200")
    void addRoute_shouldReturn200() throws Exception {
        RouteRule input = new RouteRule("INSERT", "doris", 10, true);
        input.setId(null);

        RouteRule saved = new RouteRule("INSERT", "doris", 10, true);
        saved.setId(1L);

        when(routingService.addRoute(any(RouteRule.class))).thenReturn(saved);

        mockMvc.perform(post("/api/v1/sql/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
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
}
