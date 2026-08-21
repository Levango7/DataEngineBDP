package com.shuqing.bigdata.encaps.workspace;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.NamespaceStatus;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.ResourceQuota;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * K8sWorkspaceTranslator 单元测试。
 *
 * <p>使用 Mockito 模拟 fabric8 {@link KubernetesClient}，验证翻译逻辑：</p>
 * <ul>
 *   <li>Namespace 创建时调用 K8s API</li>
 *   <li>NetworkPolicy / RBAC / ResourceQuota 异常包装与成功路径</li>
 *   <li>删除 Namespace 分支（存在/不存在/异常）</li>
 *   <li>状态查询分支（Active/NotFound/Unknown）</li>
 * </ul>
 *
 * <p>测试策略：</p>
 * <ul>
 *   <li>异常包装测试：直接让顶层 API 抛异常</li>
 *   <li>状态/删除测试：手动 mock namespaces().withName().get()/delete() 链</li>
 *   <li>创建测试：手动 mock 各 API 组的 resource().create() 链</li>
 * </ul>
 *
 * <p>注意：fabric8 6.x 移除了 Doneable 模式，{@code MixedOperation} 为 3 泛型参数。
 * 测试中使用 raw type 与通配符规避 fabric8 复杂泛型签名，不影响运行时行为。</p>
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class K8sWorkspaceTranslatorTest {

    @Mock
    private KubernetesClient k8sClient;

    /* Namespace 链 mock */
    @Mock
    private NonNamespaceOperation<Namespace, NamespaceList, Resource<Namespace>> namespaceOp;
    @Mock
    private Resource<Namespace> namespaceResource;

    private K8sWorkspaceTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new K8sWorkspaceTranslator(k8sClient);
    }

    private Workspace sampleWorkspace() {
        Workspace ws = new Workspace();
        ws.setId(1L);
        ws.setName("test-ws");
        ws.setTenantId(100L);
        ws.setNamespace("ws-100-test-ws");
        ws.setResourceQuota("cpu=4,memory=8Gi");
        return ws;
    }

    /* ------------------------------ 异常包装测试 ------------------------------ */

    @Test
    @DisplayName("createNamespace — K8s 异常被包装为 K8sTranslationException")
    void createNamespace_shouldWrapException() {
        Workspace ws = sampleWorkspace();
        when(k8sClient.namespaces())
                .thenThrow(new KubernetesClientException("namespace api unavailable"));

        assertThatThrownBy(() -> translator.createNamespace(ws))
                .isInstanceOf(K8sWorkspaceTranslator.K8sTranslationException.class)
                .hasMessageContaining("Failed to create Namespace")
                .hasMessageContaining("ws-100-test-ws");
    }

    @Test
    @DisplayName("createNetworkPolicy — K8s 异常被包装为 K8sTranslationException")
    void createNetworkPolicy_shouldWrapException() {
        Workspace ws = sampleWorkspace();
        when(k8sClient.network())
                .thenThrow(new KubernetesClientException("network api unavailable"));

        assertThatThrownBy(() -> translator.createNetworkPolicy(ws))
                .isInstanceOf(K8sWorkspaceTranslator.K8sTranslationException.class)
                .hasMessageContaining("Failed to create NetworkPolicy");
    }

    @Test
    @DisplayName("createRBAC — K8s 异常被包装为 K8sTranslationException")
    void createRBAC_shouldWrapException() {
        Workspace ws = sampleWorkspace();
        when(k8sClient.rbac())
                .thenThrow(new KubernetesClientException("rbac api unavailable"));

        assertThatThrownBy(() -> translator.createRBAC(ws))
                .isInstanceOf(K8sWorkspaceTranslator.K8sTranslationException.class)
                .hasMessageContaining("Failed to create RoleBinding");
    }

    @Test
    @DisplayName("createResourceQuota — K8s 异常被包装为 K8sTranslationException")
    void createResourceQuota_shouldWrapException() {
        Workspace ws = sampleWorkspace();
        when(k8sClient.resourceQuotas())
                .thenThrow(new KubernetesClientException("quota api unavailable"));

        assertThatThrownBy(() -> translator.createResourceQuota(ws))
                .isInstanceOf(K8sWorkspaceTranslator.K8sTranslationException.class)
                .hasMessageContaining("Failed to create ResourceQuota");
    }

    @Test
    @DisplayName("deleteNamespace — K8s 异常被包装为 K8sTranslationException")
    void deleteNamespace_shouldWrapException() {
        Workspace ws = sampleWorkspace();
        when(k8sClient.namespaces())
                .thenThrow(new KubernetesClientException("connection refused"));

        assertThatThrownBy(() -> translator.deleteNamespace(ws))
                .isInstanceOf(K8sWorkspaceTranslator.K8sTranslationException.class)
                .hasMessageContaining("Failed to delete Namespace");
    }

    @Test
    @DisplayName("getNamespaceStatus — K8s 异常被包装为 K8sTranslationException")
    void getNamespaceStatus_shouldWrapException() {
        Workspace ws = sampleWorkspace();
        when(k8sClient.namespaces())
                .thenThrow(new KubernetesClientException("query failed"));

        assertThatThrownBy(() -> translator.getNamespaceStatus(ws))
                .isInstanceOf(K8sWorkspaceTranslator.K8sTranslationException.class)
                .hasMessageContaining("Failed to query Namespace");
    }

    /* ------------------------------ 状态查询分支测试 ------------------------------ */

    @Test
    @DisplayName("getNamespaceStatus — Namespace 不存在时返回 NotFound")
    void getNamespaceStatus_notExisting_shouldReturnNotFound() {
        Workspace ws = sampleWorkspace();
        when(k8sClient.namespaces()).thenReturn(namespaceOp);
        when(namespaceOp.withName("ws-100-test-ws")).thenReturn(namespaceResource);
        when(namespaceResource.get()).thenReturn(null);

        String result = translator.getNamespaceStatus(ws);

        assertThat(result).isEqualTo("NotFound");
    }

    @Test
    @DisplayName("getNamespaceStatus — Namespace 存在且 phase=Active 时返回 Active")
    void getNamespaceStatus_active_shouldReturnActive() {
        Workspace ws = sampleWorkspace();
        Namespace ns = new Namespace();
        NamespaceStatus status = new NamespaceStatus();
        status.setPhase("Active");
        ns.setStatus(status);
        when(k8sClient.namespaces()).thenReturn(namespaceOp);
        when(namespaceOp.withName("ws-100-test-ws")).thenReturn(namespaceResource);
        when(namespaceResource.get()).thenReturn(ns);

        String result = translator.getNamespaceStatus(ws);

        assertThat(result).isEqualTo("Active");
    }

    @Test
    @DisplayName("getNamespaceStatus — Namespace 存在但 status 为 null 时返回 Unknown")
    void getNamespaceStatus_nullStatus_shouldReturnUnknown() {
        Workspace ws = sampleWorkspace();
        Namespace ns = new Namespace();
        when(k8sClient.namespaces()).thenReturn(namespaceOp);
        when(namespaceOp.withName("ws-100-test-ws")).thenReturn(namespaceResource);
        when(namespaceResource.get()).thenReturn(ns);

        String result = translator.getNamespaceStatus(ws);

        assertThat(result).isEqualTo("Unknown");
    }

    @Test
    @DisplayName("getNamespaceStatus — Namespace 存在但 phase 为 null 时返回 Unknown")
    void getNamespaceStatus_nullPhase_shouldReturnUnknown() {
        Workspace ws = sampleWorkspace();
        Namespace ns = new Namespace();
        NamespaceStatus status = new NamespaceStatus();
        ns.setStatus(status);
        when(k8sClient.namespaces()).thenReturn(namespaceOp);
        when(namespaceOp.withName("ws-100-test-ws")).thenReturn(namespaceResource);
        when(namespaceResource.get()).thenReturn(ns);

        String result = translator.getNamespaceStatus(ws);

        assertThat(result).isEqualTo("Unknown");
    }

    /* ------------------------------ 删除分支测试 ------------------------------ */

    @Test
    @DisplayName("deleteNamespace — Namespace 不存在时返回 false")
    void deleteNamespace_notExisting_shouldReturnFalse() {
        Workspace ws = sampleWorkspace();
        when(k8sClient.namespaces()).thenReturn(namespaceOp);
        when(namespaceOp.withName("ws-100-test-ws")).thenReturn(namespaceResource);
        when(namespaceResource.get()).thenReturn(null);

        boolean result = translator.deleteNamespace(ws);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("deleteNamespace — Namespace 存在时返回 true")
    void deleteNamespace_existing_shouldReturnTrue() {
        Workspace ws = sampleWorkspace();
        Namespace existing = new Namespace();
        when(k8sClient.namespaces()).thenReturn(namespaceOp);
        when(namespaceOp.withName("ws-100-test-ws")).thenReturn(namespaceResource);
        when(namespaceResource.get()).thenReturn(existing);
        when(namespaceResource.delete()).thenReturn(java.util.Collections.emptyList());

        boolean result = translator.deleteNamespace(ws);

        assertThat(result).isTrue();
    }

    /* ------------------------------ 创建调用测试 ------------------------------ */

    @Test
    @DisplayName("createNamespace — 调用 K8s API 并返回创建结果")
    void createNamespace_shouldCallK8sApi() {
        Workspace ws = sampleWorkspace();
        Namespace created = new Namespace();
        when(k8sClient.namespaces()).thenReturn(namespaceOp);
        when(namespaceOp.resource(any(Namespace.class))).thenReturn(namespaceResource);
        when(namespaceResource.create()).thenReturn(created);

        Namespace result = translator.createNamespace(ws);

        assertThat(result).isSameAs(created);
    }

    @Test
    @DisplayName("createNetworkPolicy — 成功时返回 allow-same-tenant NetworkPolicy")
    void createNetworkPolicy_success_shouldReturnPolicy() {
        Workspace ws = sampleWorkspace();
        NetworkPolicy created = new NetworkPolicy();
        // 使用 raw type mock 避免 fabric8 6.x 复杂泛型签名
        MixedOperation rawNpOp = mock(MixedOperation.class);
        Resource rawNpRes = mock(Resource.class);
        when(k8sClient.network()).thenReturn(mock(io.fabric8.kubernetes.client.dsl.NetworkAPIGroupDSL.class,
                withSettings().defaultAnswer(org.mockito.Answers.RETURNS_DEEP_STUBS)));
        when(k8sClient.network().networkPolicies()).thenReturn(rawNpOp);
        when(rawNpOp.resource(any(NetworkPolicy.class))).thenReturn(rawNpRes);
        when(rawNpRes.create()).thenReturn(created);

        NetworkPolicy result = translator.createNetworkPolicy(ws);

        assertThat(result).isSameAs(created);
        // 应该创建两条 NetworkPolicy（deny-all + allow-same-tenant）
        verify(rawNpOp, times(2)).resource(any(NetworkPolicy.class));
        verify(rawNpRes, times(2)).create();
    }

    @Test
    @DisplayName("createRBAC — 成功时返回 RoleBinding")
    void createRBAC_success_shouldReturnRoleBinding() {
        Workspace ws = sampleWorkspace();
        RoleBinding created = new RoleBinding();
        MixedOperation rawRbOp = mock(MixedOperation.class);
        Resource rawRbRes = mock(Resource.class);
        when(k8sClient.rbac()).thenReturn(mock(io.fabric8.kubernetes.client.dsl.RbacAPIGroupDSL.class,
                withSettings().defaultAnswer(org.mockito.Answers.RETURNS_DEEP_STUBS)));
        when(k8sClient.rbac().roleBindings()).thenReturn(rawRbOp);
        when(rawRbOp.resource(any(RoleBinding.class))).thenReturn(rawRbRes);
        when(rawRbRes.create()).thenReturn(created);

        RoleBinding result = translator.createRBAC(ws);

        assertThat(result).isSameAs(created);
    }

    @Test
    @DisplayName("createResourceQuota — 解析 cpu=4,memory=8Gi 为 requests+limits")
    void createResourceQuota_shouldParseQuotaString() {
        Workspace ws = sampleWorkspace();
        ResourceQuota created = new ResourceQuota();
        MixedOperation rawRqOp = mock(MixedOperation.class);
        Resource rawRqRes = mock(Resource.class);
        when(k8sClient.resourceQuotas()).thenReturn(rawRqOp);
        when(rawRqOp.resource(any(ResourceQuota.class))).thenReturn(rawRqRes);
        when(rawRqRes.create()).thenReturn(created);

        ResourceQuota result = translator.createResourceQuota(ws);

        assertThat(result).isSameAs(created);
    }

    @Test
    @DisplayName("createResourceQuota — 空配额字符串不抛异常")
    void createResourceQuota_emptyQuota_shouldNotFail() {
        Workspace ws = sampleWorkspace();
        ws.setResourceQuota("");
        ResourceQuota created = new ResourceQuota();
        MixedOperation rawRqOp = mock(MixedOperation.class);
        Resource rawRqRes = mock(Resource.class);
        when(k8sClient.resourceQuotas()).thenReturn(rawRqOp);
        when(rawRqOp.resource(any(ResourceQuota.class))).thenReturn(rawRqRes);
        when(rawRqRes.create()).thenReturn(created);

        ResourceQuota result = translator.createResourceQuota(ws);

        assertThat(result).isSameAs(created);
    }

    @Test
    @DisplayName("createResourceQuota — null 配额字符串不抛异常")
    void createResourceQuota_nullQuota_shouldNotFail() {
        Workspace ws = sampleWorkspace();
        ws.setResourceQuota(null);
        ResourceQuota created = new ResourceQuota();
        MixedOperation rawRqOp = mock(MixedOperation.class);
        Resource rawRqRes = mock(Resource.class);
        when(k8sClient.resourceQuotas()).thenReturn(rawRqOp);
        when(rawRqOp.resource(any(ResourceQuota.class))).thenReturn(rawRqRes);
        when(rawRqRes.create()).thenReturn(created);

        ResourceQuota result = translator.createResourceQuota(ws);

        assertThat(result).isSameAs(created);
    }
}
