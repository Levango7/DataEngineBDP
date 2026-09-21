package com.levango7.dataenginebdp.finops.billing.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.finops.billing.model.BillingGenerateRequest;
import com.levango7.dataenginebdp.finops.billing.model.BillingGenerateResponse;
import com.levango7.dataenginebdp.finops.billing.service.BillingGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BillingController 出账接口单元测试。
 *
 * <p>使用 MockMvc standaloneSetup（不加载 Spring Security FilterChain），
 * mock 掉 {@link BillingGenerator}，验证三个接口的契约与租户上下文校验：</p>
 * <ul>
 *   <li>POST /generate 有租户上下文时以 201 返回生成的账单，并把 TenantContext 的租户传给业务层</li>
 *   <li>POST /generate 无请求体时控制器自行构造空请求，不把 null 透传给业务层</li>
 *   <li>GET / 与 POST /generate 缺少 / 空白租户上下文时返回 401，且不触达业务层</li>
 *   <li>GET / 返回 {tenant, count, bills} 包裹结构</li>
 * </ul>
 */
class BillingControllerApiTest {

    private final BillingGenerator billingGenerator = mock(BillingGenerator.class);
    private final BillingController controller = new BillingController(billingGenerator);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static BillingGenerateResponse sampleResponse(String id, String tenantId, String period) {
        return BillingGenerateResponse.builder()
                .id(id)
                .tenantId(tenantId)
                .billingPeriod(period)
                .totalAmount(new BigDecimal("24.0000"))
                .status("GENERATED")
                .generatedAt(Instant.parse("2026-08-01T00:00:00Z"))
                .periodStart(Instant.parse("2026-08-01T00:00:00Z"))
                .periodEnd(Instant.parse("2026-09-01T00:00:00Z"))
                .note("出账闭环账单")
                .build();
    }

    @Test
    void generate_returns201_andPassesTenantContextToGenerator() throws Exception {
        TenantContext.setTenantId("tenant-A");
        when(billingGenerator.generate(eq("tenant-A"), any()))
                .thenReturn(sampleResponse("bill-1", "tenant-A", "2026-08"));

        mockMvc.perform(post("/api/finops/v1/billing/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"billingPeriod\":\"2026-08\",\"namespace\":\"ns-1\","
                                + "\"overwrite\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("bill-1"))
                .andExpect(jsonPath("$.tenantId").value("tenant-A"))
                .andExpect(jsonPath("$.billingPeriod").value("2026-08"))
                .andExpect(jsonPath("$.status").value("GENERATED"));

        ArgumentCaptor<BillingGenerateRequest> captor =
                ArgumentCaptor.forClass(BillingGenerateRequest.class);
        verify(billingGenerator).generate(eq("tenant-A"), captor.capture());
        assertThat(captor.getValue().getBillingPeriod()).isEqualTo("2026-08");
        assertThat(captor.getValue().getNamespace()).isEqualTo("ns-1");
        assertThat(captor.getValue().isOverwrite()).isFalse();
    }

    @Test
    void generate_buildsEmptyRequest_whenBodyAbsent() throws Exception {
        TenantContext.setTenantId("tenant-A");
        when(billingGenerator.generate(eq("tenant-A"), any()))
                .thenReturn(sampleResponse("bill-2", "tenant-A", "2026-09"));

        mockMvc.perform(post("/api/finops/v1/billing/generate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("bill-2"));

        // 控制器必须用空请求兜底，不能把 null 透传给业务层
        ArgumentCaptor<BillingGenerateRequest> captor =
                ArgumentCaptor.forClass(BillingGenerateRequest.class);
        verify(billingGenerator).generate(eq("tenant-A"), captor.capture());
        assertThat(captor.getValue()).isNotNull();
        assertThat(captor.getValue().getBillingPeriod()).isNull();
        assertThat(captor.getValue().getStart()).isNull();
        assertThat(captor.getValue().getEnd()).isNull();
    }

    @Test
    void generate_returns401_whenTenantContextMissing() throws Exception {
        mockMvc.perform(post("/api/finops/v1/billing/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"billingPeriod\":\"2026-08\",\"overwrite\":false}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());

        verifyNoInteractions(billingGenerator);
    }

    @Test
    void generate_returns401_whenTenantContextBlank() throws Exception {
        TenantContext.setTenantId("  ");

        mockMvc.perform(post("/api/finops/v1/billing/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"billingPeriod\":\"2026-08\",\"overwrite\":false}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(billingGenerator);
    }

    @Test
    void listByTenant_returnsWrappedBillList() throws Exception {
        TenantContext.setTenantId("tenant-A");
        when(billingGenerator.listByTenant("tenant-A")).thenReturn(List.of(
                sampleResponse("bill-new", "tenant-A", "2026-08"),
                sampleResponse("bill-old", "tenant-A", "2026-07")));

        mockMvc.perform(get("/api/finops/v1/billing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant").value("tenant-A"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.bills[0].id").value("bill-new"))
                .andExpect(jsonPath("$.bills[1].id").value("bill-old"));

        verify(billingGenerator).listByTenant("tenant-A");
    }

    @Test
    void listByTenant_returns401_whenTenantContextMissing() throws Exception {
        mockMvc.perform(get("/api/finops/v1/billing"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());

        verifyNoInteractions(billingGenerator);
    }

    @Test
    void listByTenant_returns401_whenTenantContextBlank() throws Exception {
        TenantContext.setTenantId("   ");

        mockMvc.perform(get("/api/finops/v1/billing"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(billingGenerator);
    }
}
