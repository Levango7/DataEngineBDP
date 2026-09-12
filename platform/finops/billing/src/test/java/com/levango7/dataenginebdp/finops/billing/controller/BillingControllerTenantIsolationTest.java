package com.levango7.dataenginebdp.finops.billing.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.finops.billing.model.BillingGenerateResponse;
import com.levango7.dataenginebdp.finops.billing.service.BillingGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BillingController 租户隔离单元测试。
 *
 * <p>使用 MockMvc standaloneSetup（不加载 Spring Security FilterChain），
 * mock 掉 {@link BillingGenerator}，聚焦验证 Controller 层租户隔离逻辑：</p>
 * <ul>
 *   <li>当前租户能查询到自己的账单 → 200</li>
 *   <li>查询其他租户账单 → 404（generator 返回 empty，不泄露存在性）</li>
 *   <li>缺少租户上下文 → 401</li>
 *   <li>Controller 必须把 TenantContext 的 tenantId 传给 generator（而非硬编码或忽略）</li>
 * </ul>
 *
 * <p>对应 P0 漏洞修复：BillingController.getById 租户隔离校验。</p>
 */
class BillingControllerTenantIsolationTest {

    private final BillingGenerator billingGenerator = mock(BillingGenerator.class);
    private final BillingController controller = new BillingController(billingGenerator);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    @AfterEach
    void tearDown() {
        // 清理 ThreadLocal，避免线程池复用导致租户串号
        TenantContext.clear();
    }

    private BillingGenerateResponse sampleResponse(String id, String tenantId) {
        return BillingGenerateResponse.builder()
                .id(id)
                .tenantId(tenantId)
                .billingPeriod("2026-08")
                .totalAmount(BigDecimal.ZERO)
                .status("GENERATED")
                .build();
    }

    @Test
    void getById_returns200_whenBillBelongsToCurrentTenant() throws Exception {
        TenantContext.setTenantId("tenant-A");
        when(billingGenerator.getById(eq("bill-1"), eq("tenant-A")))
                .thenReturn(Optional.of(sampleResponse("bill-1", "tenant-A")));

        mockMvc.perform(get("/api/finops/v1/billing/bill-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("bill-1"))
                .andExpect(jsonPath("$.tenantId").value("tenant-A"));

        // 关键：Controller 必须把当前 tenant-A 传给 generator
        verify(billingGenerator).getById("bill-1", "tenant-A");
    }

    @Test
    void getById_returns404_whenBillBelongsToOtherTenant() throws Exception {
        // 场景：tenant-B 登录，试图访问属于 tenant-A 的 bill-1
        TenantContext.setTenantId("tenant-B");
        // generator 按 (bill-1, tenant-B) 联合查询返回 empty → 租户隔离
        when(billingGenerator.getById(eq("bill-1"), eq("tenant-B")))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/finops/v1/billing/bill-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("账单不存在"));

        // 关键断言：Controller 把当前 tenant-B（而非账单所属 tenant-A）传给 generator，
        // 且绝不调用无租户参数的 getById —— 跨租户越权通道已关闭。
        verify(billingGenerator).getById("bill-1", "tenant-B");
        verify(billingGenerator, never()).getById("bill-1", "tenant-A");
    }

    @Test
    void getById_returns401_whenTenantContextMissing() throws Exception {
        // 未设置 TenantContext（未认证请求）
        mockMvc.perform(get("/api/finops/v1/billing/bill-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());

        // 未认证不应触达业务层
        verifyNoInteractions(billingGenerator);
    }

    @Test
    void getById_returns401_whenTenantContextBlank() throws Exception {
        TenantContext.setTenantId("   ");

        mockMvc.perform(get("/api/finops/v1/billing/bill-1"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(billingGenerator);
    }
}