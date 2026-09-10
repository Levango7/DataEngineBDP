package com.levango7.dataenginebdp.ruleengine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.service.QualityCheckExecutionService;
import com.levango7.dataenginebdp.ruleengine.service.RuleService;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QualityRuleController 单元测试（前端 /quality/rules 契约 + 租户隔离）。
 *
 * <p>租户上下文通过 {@link TenantContext} 在每个测试前设置、测试后清理，
 * 模拟 {@code JwtAuthFilter} 在请求线程写入 tenantId 的行为。
 * 所有 list/get/create/update/delete/check/summary 端点均要求租户上下文，
 * 缺失时 fail-closed（抛 {@link IllegalStateException}）。</p>
 */
@ExtendWith(MockitoExtension.class)
class QualityRuleControllerTest {

    /** 测试用租户 ID。 */
    private static final String TENANT_ID = "tenant-test";

    private MockMvc mockMvc;

    @Mock
    private RuleService ruleService;

    @Mock
    private QualityCheckExecutionService executionService;

    @InjectMocks
    private QualityRuleController qualityRuleController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(qualityRuleController).build();
        // 模拟 JwtAuthFilter 在请求线程写入 tenantId
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        // 清理线程上下文，避免线程池复用导致租户串号
        TenantContext.clear();
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
        r.setTenantId(TENANT_ID);
        r.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        r.setUpdatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        return r;
    }

    @Test
    void list_returnsPagedContract() throws Exception {
        when(ruleService.findByTenantId(eq(TENANT_ID))).thenReturn(List.of(sampleRule()));

        mockMvc.perform(get("/api/v1/quality/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.list[0].name").value("非空校验"))
                .andExpect(jsonPath("$.list[0].targetTable").value("ods.orders"))
                .andExpect(jsonPath("$.list[0].targetField").value("user_id"))
                .andExpect(jsonPath("$.list[0].checkType").value("not_null"));
    }

    @Test
    void list_supportsPageAndPageSizeParams() throws Exception {
        Rule oldest = sampleRule();
        Rule newest = sampleRule();
        newest.setId(2L);
        newest.setName("唯一校验");
        newest.setCreatedAt(LocalDateTime.of(2026, 8, 2, 10, 0));

        when(ruleService.findByTenantId(eq(TENANT_ID))).thenReturn(List.of(oldest, newest));

        mockMvc.perform(get("/api/v1/quality/rules")
                        .param("page", "2")
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].name").value("非空校验"));
    }

    @Test
    void list_sortsByCreatedAtDesc() throws Exception {
        Rule first = sampleRule();
        Rule second = sampleRule();
        second.setId(2L);
        second.setName("唯一校验");
        second.setCreatedAt(LocalDateTime.of(2026, 8, 2, 10, 0));

        when(ruleService.findByTenantId(eq(TENANT_ID))).thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/v1/quality/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list.length()").value(2))
                .andExpect(jsonPath("$.list[0].id").value("2"))
                .andExpect(jsonPath("$.list[0].name").value("唯一校验"))
                .andExpect(jsonPath("$.list[1].id").value("1"));
    }

    @Test
    void list_capsPageSizeAt100() throws Exception {
        when(ruleService.findByTenantId(eq(TENANT_ID))).thenReturn(List.of(sampleRule()));

        mockMvc.perform(get("/api/v1/quality/rules")
                        .param("page", "0")
                        .param("pageSize", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.list.length()").value(1));
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
