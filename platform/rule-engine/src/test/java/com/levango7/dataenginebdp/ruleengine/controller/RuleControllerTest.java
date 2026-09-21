package com.levango7.dataenginebdp.ruleengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionRequest;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionResult;
import com.levango7.dataenginebdp.ruleengine.service.RuleExecutionService;
import com.levango7.dataenginebdp.ruleengine.service.RuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.servlet.ServletException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RuleController MockMvc 测试。
 */
@ExtendWith(MockitoExtension.class)
class RuleControllerTest {

    private static final String TEST_TENANT_ID = "test-tenant";

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RuleService ruleService;

    @Mock
    private RuleExecutionService ruleExecutionService;

    @InjectMocks
    private RuleController ruleController;

    @BeforeEach
    void setUp() {
        // 生产控制器在 R10 加固后 fail-closed（缺 TenantContext 直接拒绝）。
        // standaloneSetup 不挂 JwtAuthFilter，故由测试侧显式写入上下文。
        TenantContext.setTenantId(TEST_TENANT_ID);
        TenantContext.setUserId("test-user");
        mockMvc = MockMvcBuilders.standaloneSetup(ruleController).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("POST /api/v1/rules — 创建规则返回201")
    void createRule_shouldReturn201() throws Exception {
        Rule input = new Rule();
        input.setName("dq-rule-1");
        input.setType("DQ");

        Rule saved = new Rule();
        saved.setId(1L);
        saved.setName("dq-rule-1");
        saved.setType("DQ");
        saved.setEnabled(true);
        saved.setCreatedAt(LocalDateTime.now());
        saved.setUpdatedAt(LocalDateTime.now());

        when(ruleService.create(any(Rule.class))).thenReturn(saved);

        mockMvc.perform(post("/api/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("dq-rule-1"));
    }

    @Test
    @DisplayName("GET /api/v1/rules — 列出所有规则返回200")
    void listRules_shouldReturn200() throws Exception {
        Rule r1 = new Rule();
        r1.setId(1L);
        r1.setName("r1");
        Rule r2 = new Rule();
        r2.setId(2L);
        r2.setName("r2");

        when(ruleService.findByTenantId(TEST_TENANT_ID)).thenReturn(List.of(r1, r2));

        mockMvc.perform(get("/api/v1/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/rules/{id} — 存在时返回200")
    void getRule_existingId_shouldReturn200() throws Exception {
        Rule rule = new Rule();
        rule.setId(1L);
        rule.setName("found-rule");

        when(ruleService.getByIdAndTenantId(1L, TEST_TENANT_ID)).thenReturn(rule);

        mockMvc.perform(get("/api/v1/rules/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("found-rule"));
    }

    @Test
    @DisplayName("GET /api/v1/rules/{id} — 不存在时返回404")
    void getRule_nonExistingId_shouldReturn404() throws Exception {
        when(ruleService.getByIdAndTenantId(999L, TEST_TENANT_ID)).thenReturn(null);

        mockMvc.perform(get("/api/v1/rules/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/rules/{id} — 存在时返回204")
    void deleteRule_existingId_shouldReturn204() throws Exception {
        when(ruleService.delete(1L, TEST_TENANT_ID)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/rules/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/rules/{id} — 不存在时返回404")
    void deleteRule_nonExistingId_shouldReturn404() throws Exception {
        when(ruleService.delete(999L, TEST_TENANT_ID)).thenReturn(false);

        mockMvc.perform(delete("/api/v1/rules/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/rules/execute — 执行规则返回200")
    void executeRule_shouldReturn200() throws Exception {
        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(1L);

        RuleExecutionResult result = RuleExecutionResult.builder()
                .ruleId(1L)
                .status("PASS")
                .message("DQ_CHECK_PASSED")
                .durationMs(10L)
                .executedAt(LocalDateTime.now())
                .build();

        when(ruleExecutionService.execute(any(RuleExecutionRequest.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/rules/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASS"));
    }

    @Test
    @DisplayName("GET /api/v1/rules/types — 列出规则类型返回200")
    void listRuleTypes_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/rules/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0]").value("DQ"))
                .andExpect(jsonPath("$[1]").value("MASK"))
                .andExpect(jsonPath("$[2]").value("ALERT"));
    }

    @Test
    @DisplayName("GET /api/v1/rules — 缺租户上下文时 fail-closed（R10 安全语义）")
    void listRules_withoutTenantContext_shouldFailClosed() {
        TenantContext.clear();

        ServletException ex = assertThrows(ServletException.class, () ->
                mockMvc.perform(get("/api/v1/rules")));

        assertTrue(ex.getMessage().contains("缺少租户上下文")
                        || ex.getCause() instanceof IllegalStateException,
                "缺租户上下文时应 fail-closed 抛 IllegalStateException，实际: " + ex);
    }
}
