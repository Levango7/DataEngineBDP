package com.levango7.dataenginebdp.encaps.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * QuotaValidator 单元测试。
 *
 * <p>验证配额校验逻辑：</p>
 * <ul>
 *   <li>未设置配额（hard 为空）时跳过校验</li>
 *   <li>数量型资源（pods/pvc/services）按整数比较</li>
 *   <li>容量型资源（cpu/memory）按 Quantity 归一化比较</li>
 *   <li>K8s 查询失败时不抛异常（跳过校验）</li>
 *   <li>空请求/null 请求直接通过</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class QuotaValidatorTest {

    @Mock
    private K8sQuotaTranslator k8sTranslator;

    @InjectMocks
    private QuotaValidator quotaValidator;

    @Test
    @DisplayName("validateQuota — 空请求直接通过")
    void validateQuota_emptyRequest_shouldPass() {
        assertThatCode(() -> quotaValidator.validateQuota(10L, Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuota — null 请求直接通过")
    void validateQuota_nullRequest_shouldPass() {
        assertThatCode(() -> quotaValidator.validateQuota(10L, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuota — 未设置配额（hard 为空）时跳过校验")
    void validateQuota_noHard_shouldSkip() {
        when(k8sTranslator.getUsage(10L)).thenReturn(Map.of(
                "used", Map.of(),
                "hard", Map.of()
        ));

        assertThatCode(() -> quotaValidator.validateQuota(10L, Map.of("pods", "1000")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuota — K8s 查询失败时跳过校验")
    void validateQuota_k8sFailed_shouldSkip() {
        when(k8sTranslator.getUsage(10L)).thenThrow(
                new K8sQuotaTranslator.K8sTranslationException("query failed", null));

        assertThatCode(() -> quotaValidator.validateQuota(10L, Map.of("pods", "1000")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuota — 数量型资源未超限时通过")
    void validateQuota_countNotExceeded_shouldPass() {
        when(k8sTranslator.getUsage(10L)).thenReturn(Map.of(
                "used", Map.of("pods", "5"),
                "hard", Map.of("pods", "100")
        ));

        assertThatCode(() -> quotaValidator.validateQuota(10L, Map.of("pods", "10")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuota — 数量型资源超限时抛 QuotaExceededException")
    void validateQuota_countExceeded_shouldThrow() {
        when(k8sTranslator.getUsage(10L)).thenReturn(Map.of(
                "used", Map.of("pods", "95"),
                "hard", Map.of("pods", "100")
        ));

        assertThatThrownBy(() -> quotaValidator.validateQuota(10L, Map.of("pods", "10")))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("pods")
                .hasMessageContaining("95")
                .hasMessageContaining("100")
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("validateQuota — CPU 容量超限时抛 QuotaExceededException")
    void validateQuota_cpuExceeded_shouldThrow() {
        when(k8sTranslator.getUsage(10L)).thenReturn(Map.of(
                "used", Map.of("requests.cpu", "8"),
                "hard", Map.of("requests.cpu", "10")
        ));

        assertThatThrownBy(() -> quotaValidator.validateQuota(10L, Map.of("requests.cpu", "4")))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("requests.cpu");
    }

    @Test
    @DisplayName("validateQuota — CPU 毫核单位换算后未超限时通过")
    void validateQuota_cpuMillisNotExceeded_shouldPass() {
        when(k8sTranslator.getUsage(10L)).thenReturn(Map.of(
                "used", Map.of("requests.cpu", "500m"),
                "hard", Map.of("requests.cpu", "2000m")
        ));

        // 500m + 1000m = 1500m < 2000m
        assertThatCode(() -> quotaValidator.validateQuota(10L, Map.of("requests.cpu", "1")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateQuota — 内存 Gi 单位换算后超限时抛异常")
    void validateQuota_memoryGiExceeded_shouldThrow() {
        when(k8sTranslator.getUsage(10L)).thenReturn(Map.of(
                "used", Map.of("requests.memory", "18Gi"),
                "hard", Map.of("requests.memory", "20Gi")
        ));

        // 18Gi + 4Gi = 22Gi > 20Gi
        assertThatThrownBy(() -> quotaValidator.validateQuota(10L, Map.of("requests.memory", "4Gi")))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("requests.memory");
    }

    @Test
    @DisplayName("validateQuota — 内存 Mi 与 Gi 混合单位换算后未超限时通过")
    void validateQuota_memoryMixedUnits_shouldPass() {
        when(k8sTranslator.getUsage(10L)).thenReturn(Map.of(
                "used", Map.of("requests.memory", "512Mi"),
                "hard", Map.of("requests.memory", "1Gi")
        ));

        // 512Mi + 256Mi = 768Mi < 1Gi(1024Mi)
        assertThatCode(() -> quotaValidator.validateQuota(10L, Map.of("requests.memory", "256Mi")))
                .doesNotThrowAnyException();
    }
}