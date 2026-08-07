package com.shuqing.bigdata.ruleengine.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.ruleengine.agent.core.Agent;
import com.shuqing.bigdata.ruleengine.agent.core.AgentContext;
import com.shuqing.bigdata.ruleengine.agent.core.AgentResult;
import com.shuqing.bigdata.ruleengine.agent.service.AgentService;
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

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AgentController MockMvc 测试。
 */
@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AgentService agentService;

    @InjectMocks
    private AgentController agentController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(agentController).build();
    }

    @Test
    @DisplayName("GET /api/v1/agents — 列出所有角色返回200")
    void listRoles_shouldReturn200() throws Exception {
        when(agentService.listRoles()).thenReturn(Set.of(
                Agent.Role.PLANNING, Agent.Role.SQL, Agent.Role.AUDIT));

        mockMvc.perform(get("/api/v1/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("POST /api/v1/agents/SQL/execute — 执行成功返回200")
    void execute_sql_shouldReturn200() throws Exception {
        AgentResult result = AgentResult.success(Agent.Role.SQL,
                Map.of("sql", "SELECT 1"), List.of("sql-1"), List.of(),
                100L, "anonymous", "req-1");
        when(agentService.execute(eq(Agent.Role.SQL), any(AgentContext.class)))
                .thenReturn(result);

        AgentExecutionRequest request = new AgentExecutionRequest();
        request.setUserInput("查询用户数");

        mockMvc.perform(post("/api/v1/agents/SQL/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.role").value("SQL"));
    }

    @Test
    @DisplayName("POST /api/v1/agents/AUDIT/execute — QUOTA_EXCEEDED 返回 429")
    void execute_quotaExceeded_shouldReturn429() throws Exception {
        AgentResult result = AgentResult.failure(Agent.Role.AUDIT,
                AgentResult.Status.QUOTA_EXCEEDED, "CONCURRENT_LIMIT_EXCEEDED",
                "too many", 10L, "anonymous", "req-1");
        when(agentService.execute(eq(Agent.Role.AUDIT), any(AgentContext.class)))
                .thenReturn(result);

        AgentExecutionRequest request = new AgentExecutionRequest();
        request.setUserInput("DROP TABLE users");

        mockMvc.perform(post("/api/v1/agents/AUDIT/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("POST /api/v1/agents/UNKNOWN/execute — 未知角色返回 400")
    void execute_unknownRole_shouldReturn400() throws Exception {
        AgentExecutionRequest request = new AgentExecutionRequest();
        request.setUserInput("test");

        mockMvc.perform(post("/api/v1/agents/UNKNOWN/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/agents/SQL/execute — INVALID_INPUT 返回 400")
    void execute_invalidInput_shouldReturn400() throws Exception {
        AgentResult result = AgentResult.failure(Agent.Role.SQL,
                AgentResult.Status.INVALID_INPUT, "MISSING_QUESTION",
                "question must not be blank", 0L, "anonymous", "req-1");
        when(agentService.execute(eq(Agent.Role.SQL), any(AgentContext.class)))
                .thenReturn(result);

        AgentExecutionRequest request = new AgentExecutionRequest();

        mockMvc.perform(post("/api/v1/agents/SQL/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_QUESTION"));
    }

    @Test
    @DisplayName("GET /api/v1/agents/describe — 返回角色元数据")
    void describeAll_shouldReturn200() throws Exception {
        Map<Agent.Role, Map<String, Object>> desc = new EnumMap<>(Agent.Role.class);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("role", "SQL");
        meta.put("implementation", "SqlAgent");
        desc.put(Agent.Role.SQL, meta);
        when(agentService.describe()).thenReturn(desc);

        mockMvc.perform(get("/api/v1/agents/describe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.SQL.implementation").value("SqlAgent"));
    }

    @Test
    @DisplayName("GET /api/v1/agents/SQL/describe — 返回单角色元数据")
    void describeOne_shouldReturn200() throws Exception {
        Map<Agent.Role, Map<String, Object>> desc = new EnumMap<>(Agent.Role.class);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("role", "SQL");
        meta.put("implementation", "SqlAgent");
        desc.put(Agent.Role.SQL, meta);
        when(agentService.describe()).thenReturn(desc);

        mockMvc.perform(get("/api/v1/agents/SQL/describe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.implementation").value("SqlAgent"));
    }

    @Test
    @DisplayName("GET /api/v1/agents/UNKNOWN/describe — 未知角色返回 400")
    void describeOne_unknownRole_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/agents/UNKNOWN/describe"))
                .andExpect(status().isBadRequest());
    }
}