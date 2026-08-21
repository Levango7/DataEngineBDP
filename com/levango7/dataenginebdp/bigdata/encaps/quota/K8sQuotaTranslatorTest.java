package com.shuqing.bigdata.encaps.quota;

import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.LimitRangeList;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaList;
import io.fabric8.kubernetes.api.model.ResourceQuotaStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * K8sQuotaTranslator 单元测试。
 *
 * <p>使用 Mockito 模拟 fabric8 {@link KubernetesClient} 与 {@link NamespaceResolver}，验证翻译逻辑：</p>
 * <ul>
 *   <li>ResourceQuota 创建/更新/删除 — 异常包装与成功路径</li>
 *   <li>LimitRange 创建/更新/删除 — 含未配置 LimitRange 字段时跳过的分支</li>
 *   <li>用量查询 — ResourceQuota 存在/不存在/异常</li>
 *   <li>Namespace 解析失败抛 K8sTranslationException</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class K8sQuotaTranslatorTest {

    @Mock
    private KubernetesClient k8sClient;
    @Mock
    private NamespaceResolver namespaceResolver;

    /* ResourceQuota 链 mock */
    @Mock
    private MixedOperation<ResourceQuota, ResourceQuotaList, Resource<ResourceQuota>> rqOp;
    @Mock
    private NonNamespaceOperation<ResourceQuota, ResourceQuotaList, Resource<ResourceQuota>> rqInNs;
    @Mock
    private Resource<ResourceQuota> rqResource;

    /* LimitRange 链 mock */
    @Mock
    private MixedOperation<LimitRange, LimitRangeList, Resource<LimitRange>> lrOp;
    @Mock
    private NonNamespaceOperation<LimitRange, LimitRangeList, Resource<LimitRange>> lrInNs;
    @Mock
    private Resource<LimitRange> lrResource;

    private K8sQuotaTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new K8sQuotaTranslator(k8sClient, namespaceResolver);
    }

    private Quota sampleQuota() {
        Quota q = new Quota();
        q.setId(1L);
        q.setWorkspaceId(10L);
        q.setTenantId(100L);
        q.setCpuLimit("10");
        q.setMemoryLimit("20Gi");
        q.setStorageLimit("100Gi");
        q.setPodLimit("100");
        q.setPvcLimit("50");
        q.setServiceLimit("20");
        q.setMaxCpuPerPod("4");
        q.setMaxMemoryPerPod("8Gi");
        q.setMinCpuPerPod("100m");
        q.setMinMemoryPerPod("256Mi");
        return q;
    }

    private Quota sampleQuotaWithoutLimitRange() {
        Quota q = sampleQuota();
        q.setMaxCpuPerPod(null);
        q.setMaxMemoryPerPod(null);
        q.setMinCpuPerPod(null);
        q.setMinMemoryPerPod(null);
        return q;
    }

    /* ------------------------------ ResourceQuota 创建 ------------------------------ */

    @Test
    @DisplayName("createResourceQuota — 成功时调用 K8s API 并返回结果")
    void createResourceQuota_success_shouldCallK8sApi() {
        Quota q = sampleQuota();
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        ResourceQuota created = new ResourceQuota();
        when(k8sClient.resourceQuotas()).thenReturn(rqOp);
        when(rqOp.resource(any(ResourceQuota.class))).thenReturn(rqResource);
        when(rqResource.create()).thenReturn(created);

        ResourceQuota result = translator.createResourceQuota(q);

        assertThat(result).isSameAs(created);
    }

    @Test
    @DisplayName("createResourceQuota — K8s 异常被包装为 K8sTranslationException")
    void createResourceQuota_shouldWrapException() {
        Quota q = sampleQuota();
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        when(k8sClient.resourceQuotas())
                .thenThrow(new KubernetesClientException("quota api unavailable"));

        assertThatThrownBy(() -> translator.createResourceQuota(q))
                .isInstanceOf(K8sQuotaTranslator.K8sTranslationException.class)
                .hasMessageContaining("Failed to create ResourceQuota")
                .hasMessageContaining("ws-100-test/workspace-10-quota");
    }

    @Test
    @DisplayName("createResourceQuota — Namespace 解析失败抛 K8sTranslationException")
    void createResourceQuota_namespaceResolveFailed_shouldThrow() {
        Quota q = sampleQuota();
        when(namespaceResolver.resolve(10L)).thenReturn(null);

        assertThatThrownBy(() -> translator.createResourceQuota(q))
                .isInstanceOf(K8sQuotaTranslator.K8sTranslationException.class)
                .hasMessageContaining("Cannot resolve namespace");
    }

    /* ------------------------------ ResourceQuota 更新 ------------------------------ */

    @Test
    @DisplayName("updateResourceQuota — 成功时调用 K8s API update")
    void updateResourceQuota_success_shouldCallK8sApi() {
        Quota q = sampleQuota();
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        ResourceQuota updated = new ResourceQuota();
        when(k8sClient.resourceQuotas()).thenReturn(rqOp);
        when(rqOp.resource(any(ResourceQuota.class))).thenReturn(rqResource);
        when(rqResource.update()).thenReturn(updated);

        ResourceQuota result = translator.updateResourceQuota(q);

        assertThat(result).isSameAs(updated);
    }

    @Test
    @DisplayName("updateResourceQuota — K8s 异常被包装")
    void updateResourceQuota_shouldWrapException() {
        Quota q = sampleQuota();
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        when(k8sClient.resourceQuotas())
                .thenThrow(new KubernetesClientException("update failed"));

        assertThatThrownBy(() -> translator.updateResourceQuota(q))
                .isInstanceOf(K8sQuotaTranslator.K8sTranslationException.class)
                .hasMessageContaining("Failed to update ResourceQuota");
    }

    /* ------------------------------ ResourceQuota 删除 ------------------------------ */

    @Test
    @DisplayName("deleteResourceQuota — 存在时返回 true")
    void deleteResourceQuota_existing_shouldReturnTrue() {
        Quota q = sampleQuota();
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        when(k8sClient.resourceQuotas()).thenReturn(rqOp);
        when(rqOp.inNamespace("ws-100-test")).thenReturn(rqInNs);
        when(rqInNs.withName("workspace-10-quota")).thenReturn(rqResource);
        when(rqResource.get()).thenReturn(new ResourceQuota());
        when(rqResource.delete()).thenReturn(java.util.Collections.emptyList());

        boolean result = translator.deleteResourceQuota(q);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("deleteResourceQuota — 不存在时返回 false")
    void deleteResourceQuota_notExisting_shouldReturnFalse() {
        Quota q = sampleQuota();
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        when(k8sClient.resourceQuotas()).thenReturn(rqOp);
        when(rqOp.inNamespace("ws-100-test")).thenReturn(rqInNs);
        when(rqInNs.withName("workspace-10-quota")).thenReturn(rqResource);
        when(rqResource.get()).thenReturn(null);

        boolean result = translator.deleteResourceQuota(q);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("deleteResourceQuota — K8s 异常被包装")
    void deleteResourceQuota_shouldWrapException() {
        Quota q = sampleQuota();
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        when(k8sClient.resourceQuotas())
                .thenThrow(new KubernetesClientException("delete failed"));

        assertThatThrownBy(() -> translator.deleteResourceQuota(q))
                .isInstanceOf(K8sQuotaTranslator.K8sTranslationException.class)
                .hasMessageContaining("Failed to delete ResourceQuota");
    }

    /* ------------------------------ LimitRange 创建 ------------------------------ */

    @Test
    @DisplayName("createLimitRange — 成功时调用 K8s API 并返回结果")
    void createLimitRange_success_shouldCallK8sApi() {
        Quota q = sampleQuota();
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        LimitRange created = new LimitRange();
        when(k8sClient.limitRanges()).thenReturn(lrOp);
        when(lrOp.resource(any(LimitRange.class))).thenReturn(lrResource);
        when(lrResource.create()).thenReturn(created);

        LimitRange result = translator.createLimitRange(q);

        assertThat(result).isSameAs(created);
    }

    @Test
    @DisplayName("createLimitRange — 未配置 LimitRange 字段时跳过并返回 null")
    void createLimitRange_noFields_shouldReturnNull() {
        Quota q = sampleQuotaWithoutLimitRange();

        LimitRange result = translator.createLimitRange(q);

        assertThat(result).isNull();
        verifyNoInteractions(k8sClient);
        verifyNoInteractions(namespaceResolver);
    }

    @Test
    @DisplayName("createLimitRange — K8s 异常被包装")
    void createLimitRange_shouldWrapException() {
        Quota q = sampleQuota();
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        when(k8sClient.limitRanges())
                .thenThrow(new KubernetesClientException("lr api unavailable"));

        assertThatThrownBy(() -> translator.createLimitRange(q))
                .isInstanceOf(K8sQuotaTranslator.K8sTranslationException.class)
                .hasMessageContaining("Failed to create LimitRange");
    }

    /* ------------------------------ LimitRange 更新 ------------------------------ */

    @Test
    @DisplayName("updateLimitRange — 成功时调用 K8s API update")
    void updateLimitRange_success_shouldCallK8sApi() {
        Quota q = sampleQuota();
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        LimitRange updated = new LimitRange();
        when(k8sClient.limitRanges()).thenReturn(lrOp);
        when(lrOp.resource(any(LimitRange.class))).thenReturn(lrResource);
        when(lrResource.update()).thenReturn(updated);

        LimitRange result = translator.updateLimitRange(q);

        assertThat(result).isSameAs(updated);
    }

    @Test
    @DisplayName("updateLimitRange — 未配置字段时尝试删除既有 LimitRange")
    void updateLimitRange_noFields_shouldDeleteExisting() {
        Quota q = sampleQuotaWithoutLimitRange();
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        when(k8sClient.limitRanges()).thenReturn(lrOp);
        when(lrOp.inNamespace("ws-100-test")).thenReturn(lrInNs);
        when(lrInNs.withName("workspace-10-limits")).thenReturn(lrResource);
        when(lrResource.get()).thenReturn(null); // 不存在，跳过删除

        LimitRange result = translator.updateLimitRange(q);

        assertThat(result).isNull();
    }

    /* ------------------------------ LimitRange 删除 ------------------------------ */

    @Test
    @DisplayName("deleteLimitRange — 存在时返回 true")
    void deleteLimitRange_existing_shouldReturnTrue() {
        Quota q = sampleQuota();
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        when(k8sClient.limitRanges()).thenReturn(lrOp);
        when(lrOp.inNamespace("ws-100-test")).thenReturn(lrInNs);
        when(lrInNs.withName("workspace-10-limits")).thenReturn(lrResource);
        when(lrResource.get()).thenReturn(new LimitRange());
        when(lrResource.delete()).thenReturn(java.util.Collections.emptyList());

        boolean result = translator.deleteLimitRange(q);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("deleteLimitRange — 不存在时返回 false")
    void deleteLimitRange_notExisting_shouldReturnFalse() {
        Quota q = sampleQuota();
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        when(k8sClient.limitRanges()).thenReturn(lrOp);
        when(lrOp.inNamespace("ws-100-test")).thenReturn(lrInNs);
        when(lrInNs.withName("workspace-10-limits")).thenReturn(lrResource);
        when(lrResource.get()).thenReturn(null);

        boolean result = translator.deleteLimitRange(q);

        assertThat(result).isFalse();
    }

    /* ------------------------------ 用量查询 ------------------------------ */

    @Test
    @DisplayName("getUsage — ResourceQuota 存在时返回 used 与 hard Map")
    void getUsage_existing_shouldReturnMaps() {
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        ResourceQuota rq = new ResourceQuota();
        ResourceQuotaStatus status = new ResourceQuotaStatus();
        Map<String, io.fabric8.kubernetes.api.model.Quantity> used = new HashMap<>();
        used.put("pods", new io.fabric8.kubernetes.api.model.Quantity("5"));
        Map<String, io.fabric8.kubernetes.api.model.Quantity> hard = new HashMap<>();
        hard.put("pods", new io.fabric8.kubernetes.api.model.Quantity("100"));
        status.setUsed(used);
        status.setHard(hard);
        rq.setStatus(status);

        when(k8sClient.resourceQuotas()).thenReturn(rqOp);
        when(rqOp.inNamespace("ws-100-test")).thenReturn(rqInNs);
        when(rqInNs.withName("workspace-10-quota")).thenReturn(rqResource);
        when(rqResource.get()).thenReturn(rq);

        Map<String, Map<String, String>> result = translator.getUsage(10L);

        assertThat(result).containsKeys("used", "hard");
        assertThat(result.get("used")).containsEntry("pods", "5");
        assertThat(result.get("hard")).containsEntry("pods", "100");
    }

    @Test
    @DisplayName("getUsage — ResourceQuota 不存在时返回空 Map")
    void getUsage_notExisting_shouldReturnEmptyMaps() {
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        when(k8sClient.resourceQuotas()).thenReturn(rqOp);
        when(rqOp.inNamespace("ws-100-test")).thenReturn(rqInNs);
        when(rqInNs.withName("workspace-10-quota")).thenReturn(rqResource);
        when(rqResource.get()).thenReturn(null);

        Map<String, Map<String, String>> result = translator.getUsage(10L);

        assertThat(result).containsKeys("used", "hard");
        assertThat(result.get("used")).isEmpty();
        assertThat(result.get("hard")).isEmpty();
    }

    @Test
    @DisplayName("getUsage — K8s 异常被包装")
    void getUsage_shouldWrapException() {
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        when(k8sClient.resourceQuotas())
                .thenThrow(new KubernetesClientException("query failed"));

        assertThatThrownBy(() -> translator.getUsage(10L))
                .isInstanceOf(K8sQuotaTranslator.K8sTranslationException.class)
                .hasMessageContaining("Failed to query ResourceQuota usage");
    }

    @Test
    @DisplayName("getUsage — ResourceQuota 存在但 status 为 null 时返回空 Map")
    void getUsage_nullStatus_shouldReturnEmptyMaps() {
        when(namespaceResolver.resolve(10L)).thenReturn("ws-100-test");
        ResourceQuota rq = new ResourceQuota();
        when(k8sClient.resourceQuotas()).thenReturn(rqOp);
        when(rqOp.inNamespace("ws-100-test")).thenReturn(rqInNs);
        when(rqInNs.withName("workspace-10-quota")).thenReturn(rqResource);
        when(rqResource.get()).thenReturn(rq);

        Map<String, Map<String, String>> result = translator.getUsage(10L);

        assertThat(result.get("used")).isEmpty();
        assertThat(result.get("hard")).isEmpty();
    }
}