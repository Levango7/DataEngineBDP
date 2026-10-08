package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.quota.Quota;
import com.levango7.dataenginebdp.encaps.quota.QuotaRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        q.setTenantId("100");
        q.setCpuLimit(cpu);
        q.setMemoryLimit("16Gi");
        q.setStorageLimit("100Gi");
        q.setPodLimit("50");
        return q;
    }

    @Test
    void plan_returnsFreeWhenNoQuotas() {
        when(quotaRepository.findByTenantId("100")).thenReturn(List.of());
        var resp = controller().plan();
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("plan")).isEqualTo("free");
        assertThat(body.get("planName")).isEqualTo("免费版");
    }

    @Test
    void plan_infersProWhenCpuAbove4() {
        when(quotaRepository.findByTenantId("100")).thenReturn(List.of(sampleQuota("8")));
        var resp = controller().plan();
        assertThat(resp.getBody().get("plan")).isEqualTo("pro");
        assertThat(((java.util.List<?>) resp.getBody().get("quotas"))).hasSize(1);
    }

    @Test
    void plan_infersEnterpriseWhenCpuAbove32() {
        when(quotaRepository.findByTenantId("100")).thenReturn(List.of(sampleQuota("40")));
        var resp = controller().plan();
        assertThat(resp.getBody().get("plan")).isEqualTo("enterprise");
    }

    @Test
    void billing_returnsMonthlyFee() {
        when(quotaRepository.findByTenantId("100")).thenReturn(List.of(sampleQuota("8")));
        var resp = controller().billing();
        Map<String, Object> body = resp.getBody();
        assertThat(((Number) body.get("totalCost")).doubleValue()).isEqualTo(1999.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void billing_itemKeysMatchFrontendContract() {
        // 前端 frontend/src/api/account.ts 的 BillingItem 声明是 id/name/usage/cost，
        // 而 Account.vue 直接 row.cost.toLocaleString()。键名一旦错位，整页会被
        // ErrorBoundary 换成"页面渲染出错"（台账 #56：批次 B 让 /account 第一次拿到数据后才暴露）。
        when(quotaRepository.findByTenantId("100")).thenReturn(List.of(sampleQuota("8")));
        Map<String, Object> body = controller().billing().getBody();
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertThat(items).hasSize(1);
        Map<String, Object> item = items.get(0);
        assertThat(item).containsOnlyKeys("id", "name", "usage", "cost");
        assertThat(item.get("id")).isInstanceOf(String.class);
        assertThat(item.get("name")).isInstanceOf(String.class);
        assertThat(item.get("usage")).isInstanceOf(String.class);
        assertThat(((Number) item.get("cost")).doubleValue())
                .isEqualTo(((Number) body.get("totalCost")).doubleValue());
    }

    @Test
    void upgrade_returnsEstimatedFee() {
        var resp = controller().upgrade(Map.of("targetPlan", "enterprise"));
        assertThat(resp.getBody().get("estimatedMonthlyFee")).isEqualTo(9999);
        assertThat(resp.getBody().get("status")).isEqualTo("submitted");
    }

    @Test
    void upgrade_unknownPlan_shouldBeRejected() {
        // 台账 #62：前端弹窗的默认档位是 flagship，而后端只有 free/pro/enterprise。
        // 此前未知档位被 PLANS.getOrDefault(..., pro) 兜成 pro 静默受理，等于按调用方
        // 没要求过的档记账；现在必须 fail-closed。
        assertThatThrownBy(() -> controller().upgrade(Map.of("targetPlan", "flagship")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void upgrade_missingPlanKey_shouldBeRejected() {
        // 缺键与未知值同罪：req.getOrDefault("targetPlan","pro") 会让不带字段的请求
        // 也静默落到 pro，调用方拿不到任何错误信号。
        assertThatThrownBy(() -> controller().upgrade(Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
