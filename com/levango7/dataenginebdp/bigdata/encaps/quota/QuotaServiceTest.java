package com.shuqing.bigdata.encaps.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * QuotaService 单元测试。
 *
 * <p>使用 Mockito 模拟 {@link QuotaRepository} 与 {@link K8sQuotaTranslator}，
 * 验证业务编排逻辑：</p>
 * <ul>
 *   <li>setQuota — SETTING → 翻译 → ACTIVE/FAILED 状态流转；重复设置抛 IllegalStateException</li>
 *   <li>updateQuota — UPDATING → 更新 K8s → ACTIVE/FAILED</li>
 *   <li>deleteQuota — DELETING → 删 K8s → DELETED</li>
 *   <li>listQuotas — 按 tenantId/workspaceId 过滤</li>
 *   <li>getUsage — 委托翻译器</li>
 *   <li>K8s 翻译失败不抛异常，状态置为 FAILED</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock
    private QuotaRepository quotaRepository;

    @Mock
    private K8sQuotaTranslator k8sTranslator;

    @InjectMocks
    private QuotaService quotaService;

    private Quota sampleSetRequest() {
        Quota q = new Quota();
        q.setWorkspaceId(10L);
        q.setTenantId(100L);
        q.setCpuLimit("10");
        q.setMemoryLimit("20Gi");
        q.setStorageLimit("100Gi");
        q.setPodLimit("100");
        q.setPvcLimit("50");
        q.setServiceLimit("20");
        return q;
    }

    private Quota sampleExistingQuota() {
        Quota q = sampleSetRequest();
        q.setId(1L);
        q.setStatus(Quota.QuotaStatus.ACTIVE);
        return q;
    }

    /* ------------------------------ setQuota ------------------------------ */

    @Test
    @DisplayName("setQuota — K8s 翻译成功时状态为 ACTIVE")
    void setQuota_translationSuccess_shouldBeActive() {
        Quota req = sampleSetRequest();

        when(quotaRepository.findByWorkspaceId(10L)).thenReturn(Optional.empty());
        when(quotaRepository.save(any(Quota.class))).thenAnswer(invocation -> {
            Quota q = invocation.getArgument(0);
            if (q.getId() == null) {
                q.setId(1L);
            }
            return q;
        });

        Quota result = quotaService.setQuota(req);

        assertThat(result.getStatus()).isEqualTo(Quota.QuotaStatus.ACTIVE);
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();

        verify(k8sTranslator).createResourceQuota(any());
        verify(k8sTranslator).createLimitRange(any());
        verify(quotaRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("setQuota — K8s 翻译失败时状态为 FAILED 且不抛异常")
    void setQuota_translationFailed_shouldBeFailed() {
        Quota req = sampleSetRequest();

        when(quotaRepository.findByWorkspaceId(10L)).thenReturn(Optional.empty());
        when(quotaRepository.save(any(Quota.class))).thenAnswer(invocation -> {
            Quota q = invocation.getArgument(0);
            if (q.getId() == null) {
                q.setId(1L);
            }
            return q;
        });
        doThrow(new K8sQuotaTranslator.K8sTranslationException("rq conflict",
                new RuntimeException("rq conflict")))
                .when(k8sTranslator).createResourceQuota(any());

        Quota result = quotaService.setQuota(req);

        assertThat(result.getStatus()).isEqualTo(Quota.QuotaStatus.FAILED);
        verify(k8sTranslator, never()).createLimitRange(any());
    }

    @Test
    @DisplayName("setQuota — 同一 Workspace 已存在活跃 Quota 时抛 IllegalStateException")
    void setQuota_existingActive_shouldThrow() {
        Quota req = sampleSetRequest();
        when(quotaRepository.findByWorkspaceId(10L)).thenReturn(Optional.of(sampleExistingQuota()));

        assertThatThrownBy(() -> quotaService.setQuota(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Active quota already exists");

        verify(quotaRepository, never()).save(any());
        verifyNoInteractions(k8sTranslator);
    }

    @Test
    @DisplayName("setQuota — 缺失字段填充默认值")
    void setQuota_missingFields_shouldApplyDefaults() {
        Quota req = new Quota();
        req.setWorkspaceId(10L);
        req.setTenantId(100L);
        // 不设置任何配额字段

        when(quotaRepository.findByWorkspaceId(10L)).thenReturn(Optional.empty());
        when(quotaRepository.save(any(Quota.class))).thenAnswer(invocation -> {
            Quota q = invocation.getArgument(0);
            if (q.getId() == null) {
                q.setId(1L);
            }
            return q;
        });

        Quota result = quotaService.setQuota(req);

        assertThat(result.getCpuLimit()).isEqualTo(QuotaService.DEFAULT_CPU_LIMIT);
        assertThat(result.getMemoryLimit()).isEqualTo(QuotaService.DEFAULT_MEMORY_LIMIT);
        assertThat(result.getStorageLimit()).isEqualTo(QuotaService.DEFAULT_STORAGE_LIMIT);
        assertThat(result.getPodLimit()).isEqualTo(QuotaService.DEFAULT_POD_LIMIT);
        assertThat(result.getPvcLimit()).isEqualTo(QuotaService.DEFAULT_PVC_LIMIT);
        assertThat(result.getServiceLimit()).isEqualTo(QuotaService.DEFAULT_SERVICE_LIMIT);
    }

    /* ------------------------------ getQuota / listQuotas ------------------------------ */

    @Test
    @DisplayName("getQuota — 存在时返回 Optional 含值")
    void getQuota_existing_shouldReturn() {
        Quota q = sampleExistingQuota();
        when(quotaRepository.findById(1L)).thenReturn(Optional.of(q));

        Optional<Quota> result = quotaService.getQuota(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getQuota — 不存在时返回 Optional 空")
    void getQuota_nonExisting_shouldReturnEmpty() {
        when(quotaRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<Quota> result = quotaService.getQuota(999L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getQuotaByWorkspace — 按 workspaceId 查询")
    void getQuotaByWorkspace_shouldDelegate() {
        Quota q = sampleExistingQuota();
        when(quotaRepository.findByWorkspaceId(10L)).thenReturn(Optional.of(q));

        Optional<Quota> result = quotaService.getQuotaByWorkspace(10L);

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("listQuotas — tenantId 为 null 时返回全部")
    void listQuotas_nullTenantId_shouldReturnAll() {
        Quota q1 = sampleExistingQuota();
        when(quotaRepository.findAll()).thenReturn(List.of(q1));

        List<Quota> result = quotaService.listQuotas(null);

        assertThat(result).hasSize(1);
        verify(quotaRepository).findAll();
    }

    @Test
    @DisplayName("listQuotas — 指定 tenantId 时按租户过滤")
    void listQuotas_withTenantId_shouldFilter() {
        Quota q1 = sampleExistingQuota();
        when(quotaRepository.findByTenantId(100L)).thenReturn(List.of(q1));

        List<Quota> result = quotaService.listQuotas(100L);

        assertThat(result).hasSize(1);
        verify(quotaRepository).findByTenantId(100L);
    }

    @Test
    @DisplayName("listQuotas — 同时指定 tenantId 与 workspaceId 时按 workspaceId 过滤")
    void listQuotas_withWorkspaceId_shouldFilterByWorkspace() {
        Quota q1 = sampleExistingQuota();
        when(quotaRepository.findAllByWorkspaceId(10L)).thenReturn(List.of(q1));

        List<Quota> result = quotaService.listQuotas(100L, 10L);

        assertThat(result).hasSize(1);
        verify(quotaRepository).findAllByWorkspaceId(10L);
        verify(quotaRepository, never()).findByTenantId(any());
    }

    /* ------------------------------ updateQuota ------------------------------ */

    @Test
    @DisplayName("updateQuota — 存在时更新可变字段并流转 UPDATING → ACTIVE")
    void updateQuota_existing_shouldUpdateAndBeActive() {
        Quota existing = sampleExistingQuota();
        existing.setCpuLimit("10");

        Quota update = new Quota();
        update.setCpuLimit("20");
        update.setMemoryLimit("40Gi");

        when(quotaRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(quotaRepository.save(any(Quota.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Quota> result = quotaService.updateQuota(1L, update);

        assertThat(result).isPresent();
        Quota updated = result.get();
        assertThat(updated.getCpuLimit()).isEqualTo("20");
        assertThat(updated.getMemoryLimit()).isEqualTo("40Gi");
        assertThat(updated.getStatus()).isEqualTo(Quota.QuotaStatus.ACTIVE);
        verify(k8sTranslator).updateResourceQuota(any());
        verify(k8sTranslator).updateLimitRange(any());
    }

    @Test
    @DisplayName("updateQuota — K8s 更新失败时状态为 FAILED")
    void updateQuota_k8sFailed_shouldBeFailed() {
        Quota existing = sampleExistingQuota();

        when(quotaRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(quotaRepository.save(any(Quota.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new K8sQuotaTranslator.K8sTranslationException("update failed",
                new RuntimeException("update failed")))
                .when(k8sTranslator).updateResourceQuota(any());

        Optional<Quota> result = quotaService.updateQuota(1L, new Quota());

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(Quota.QuotaStatus.FAILED);
    }

    @Test
    @DisplayName("updateQuota — 不存在时返回 Optional 空")
    void updateQuota_nonExisting_shouldReturnEmpty() {
        when(quotaRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<Quota> result = quotaService.updateQuota(999L, new Quota());

        assertThat(result).isEmpty();
        verify(quotaRepository, never()).save(any());
    }

    /* ------------------------------ deleteQuota ------------------------------ */

    @Test
    @DisplayName("deleteQuota — 存在时 DELETING → 删 K8s → DELETED")
    void deleteQuota_existing_shouldDeleteAndReturnTrue() {
        Quota existing = sampleExistingQuota();
        when(quotaRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(quotaRepository.save(any(Quota.class))).thenAnswer(inv -> inv.getArgument(0));
        when(k8sTranslator.deleteResourceQuota(any())).thenReturn(true);
        when(k8sTranslator.deleteLimitRange(any())).thenReturn(true);

        boolean result = quotaService.deleteQuota(1L);

        assertThat(result).isTrue();
        assertThat(existing.getStatus()).isEqualTo(Quota.QuotaStatus.DELETED);
        verify(k8sTranslator).deleteResourceQuota(any());
        verify(k8sTranslator).deleteLimitRange(any());
    }

    @Test
    @DisplayName("deleteQuota — K8s 删除失败时仍置为 DELETED")
    void deleteQuota_k8sFailed_shouldStillBeDeleted() {
        Quota existing = sampleExistingQuota();
        when(quotaRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(quotaRepository.save(any(Quota.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new K8sQuotaTranslator.K8sTranslationException("delete failed",
                new RuntimeException("delete failed")))
                .when(k8sTranslator).deleteResourceQuota(any());

        boolean result = quotaService.deleteQuota(1L);

        assertThat(result).isTrue();
        assertThat(existing.getStatus()).isEqualTo(Quota.QuotaStatus.DELETED);
    }

    @Test
    @DisplayName("deleteQuota — 不存在时返回 false 且不调用 K8s")
    void deleteQuota_nonExisting_shouldReturnFalse() {
        when(quotaRepository.findById(999L)).thenReturn(Optional.empty());

        boolean result = quotaService.deleteQuota(999L);

        assertThat(result).isFalse();
        verify(k8sTranslator, never()).deleteResourceQuota(any());
        verify(quotaRepository, never()).save(any());
    }

    /* ------------------------------ getUsage ------------------------------ */

    @Test
    @DisplayName("getUsage — 委托翻译器查询")
    void getUsage_shouldDelegate() {
        Map<String, Map<String, String>> usage = Map.of(
                "used", Map.of("pods", "5"),
                "hard", Map.of("pods", "100")
        );
        when(k8sTranslator.getUsage(10L)).thenReturn(usage);

        Map<String, Map<String, String>> result = quotaService.getUsage(10L);

        assertThat(result).isEqualTo(usage);
        verify(k8sTranslator).getUsage(10L);
    }
}