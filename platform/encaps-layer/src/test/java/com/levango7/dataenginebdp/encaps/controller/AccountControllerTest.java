package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.quota.Quota;
import com.levango7.dataenginebdp.encaps.quota.QuotaRepository;
import com.levango7.dataenginebdp.encaps.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * AccountController 单元测试（套餐/账单/升级）。
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock
    private QuotaRepository quotaRepository;

    @BeforeEach
    void setUpTenant() {
        TenantContext.setTenantId("100");
        TenantContext.setUserId("tester");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private AccountController controller() {
        return new AccountController(quotaRepository);
    }

    private Quota sampleQuota(String cpu) {
        Quota q = new Quota();
        q.setId(1L);
        q.setWorkspaceId(1L);
        q.setTenantId(100L);
        q.setCpuLimit(cpu);
        q.setMemoryLimit("16Gi");
        q.setStorageLimit("100Gi");
        q.setPodLimit("50");
        return q;
    }

    @Test
    void plan_returnsFreeWhenNoQuotas() {
        when(quotaRepository.findByTenantId(100L)).thenReturn(List.of());
        var resp = controller().plan();
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("plan")).isEqualTo("free");
        assertThat(body.get("planName")).isEqualTo("免费版");
    }

    @Test
    void plan_infersProWhenCpuAbove4() {
        when(quotaRepository.findByTenantId(100L)).thenReturn(List.of(sampleQuota("8")));
        var resp = controller().plan();
        assertThat(resp.getBody().get("plan")).isEqualTo("pro");
        assertThat(((java.util.List<?>) resp.getBody().get("quotas"))).hasSize(1);
    }

    @Test
    void plan_infersEnterpriseWhenCpuAbove32() {
        when(quotaRepository.findByTenantId(100L)).thenReturn(List.of(sampleQuota("40")));
        var resp = controller().plan();
        assertThat(resp.getBody().get("plan")).isEqualTo("enterprise");
    }

    @Test
    void billing_returnsMonthlyFee() {
        when(quotaRepository.findByTenantId(100L)).thenReturn(List.of(sampleQuota("8")));
        var resp = controller().billing();
        Map<String, Object> body = resp.getBody();
        assertThat(((Number) body.get("totalCost")).doubleValue()).isEqualTo(1999.0);
    }

    @Test
    void upgrade_returnsEstimatedFee() {
        var resp = controller().upgrade(Map.of("targetPlan", "enterprise"));
        assertThat(resp.getBody().get("estimatedMonthlyFee")).isEqualTo(9999);
        assertThat(resp.getBody().get("status")).isEqualTo("submitted");
    }
}
