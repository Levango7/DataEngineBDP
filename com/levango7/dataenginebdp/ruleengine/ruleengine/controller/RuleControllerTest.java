package com.shuqing.bigdata.ruleengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.ruleengine.model.Rule;
import com.shuqing.bigdata.ruleengine.model.RuleExecutionRequest;
import com.shuqing.bigdata.ruleengine.model.RuleExecutionResult;
import com.shuqing.bigdata.ruleengine.service.RuleExecutionService;
import com.shuqing.bigdata.ruleengine.service.RuleService;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RuleController MockMvc 测试。
 */
@ExtendWith(MockitoExtension.class)
class RuleControllerTest {

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
        mockMvc = MockMvcBuilders.standaloneSetup(ruleController).build();
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

        when(ruleService.listAll()).thenReturn(List.of(r1, r2));

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

        when(ruleService.getById(1L)).thenReturn(rule);

        mockMvc.perform(get("/api/v1/rules/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("found-rule"));
    }

    @Test
    @DisplayName("GET /api/v1/rules/{id} — 不存在时返回404")
    void getRule_nonExistingId_shouldReturn404() throws Exception {
        when(ruleService.getById(999L)).thenReturn(null);

        mockMvc.perform(get("/api/v1/rules/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/rules/{id} — 存在时返回204")
    void deleteRule_existingId_shouldReturn204() throws Exception {
        when(ruleService.delete(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/rules/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/rules/{id} — 不存在时返回404")
    void deleteRule_nonExistingId_shouldReturn404() throws Exception {
        when(ruleService.delete(999L)).thenReturn(false);

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
}
