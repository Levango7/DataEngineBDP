package com.levango7.dataenginebdp.ruleengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.service.RuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QualityRuleController 单元测试（前端 /quality/rules 契约）。
 */
@ExtendWith(MockitoExtension.class)
class QualityRuleControllerTest {

    private MockMvc mockMvc;

    @Mock
    private RuleService ruleService;

    @InjectMocks
    private QualityRuleController qualityRuleController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(qualityRuleController).build();
    }

    private Rule sampleRule() {
        Rule r = new Rule();
        r.setId(1L);
        r.setName("非空校验");
        r.setType("QUALITY_NOT_NULL");
        r.setExpression("threshold=100%");
        r.setSeverity("BLOCK");
        r.setEnabled(true);
        r.setDescription("quality rule on ods.orders.user_id");
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        return r;
    }

    @Test
    void list_returnsPagedContract() throws Exception {
        when(ruleService.listAll()).thenReturn(List.of(sampleRule()));

        mockMvc.perform(get("/api/v1/quality/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].name").value("非空校验"))
                .andExpect(jsonPath("$.list[0].targetTable").value("ods.orders"))
                .andExpect(jsonPath("$.list[0].targetField").value("user_id"))
                .andExpect(jsonPath("$.list[0].checkType").value("not_null"));
    }

    @Test
    void create_mapsToRuleAndReturnsView() throws Exception {
        when(ruleService.create(any())).thenAnswer(inv -> {
            Rule r = inv.getArgument(0);
            r.setId(2L);
            r.setCreatedAt(LocalDateTime.now());
            return r;
        });

        String body = "{\"name\":\"唯一校验\",\"targetTable\":\"ods.users\",\"targetField\":\"email\","
                + "\"checkType\":\"unique\",\"threshold\":\"0\",\"actionOnFail\":\"WARN\"}";

        mockMvc.perform(post("/api/v1/quality/rules")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("2"))
                .andExpect(jsonPath("$.checkType").value("unique"))
                .andExpect(jsonPath("$.targetTable").value("ods.users"));
    }
}
