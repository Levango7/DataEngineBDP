package com.levango7.dataenginebdp.encaps.workspace;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K8sWorkspaceTranslator 真实 k3s 集成测试。
 *
 * <p>验证翻译器在真实集群创建 Namespace / NetworkPolicy / ResourceQuota。
 * 通过 {@code -Dk8s.it=true} 启用（本地 k3s 或 CI 有集群时跑）；
 * 未启用时自动跳过，不阻塞普通构建。</p>
 *
 * <p>运行：{@code mvn test -Dtest=K8sWorkspaceTranslatorIT -Dk8s.it=true}</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "k8s.it", matches = "true")
class K8sWorkspaceTranslatorIT {

    private KubernetesClient k8s;
    private K8sWorkspaceTranslator translator;
    private static final String NS = "it-k8s-ws";

    @BeforeAll
    void setUp() {
        // fabric8 默认读 KUBECONFIG / ~/.kube/config（真实集群）
        k8s = new KubernetesClientBuilder().build();
        // 集群不可达时直接失败（真实测试必须连上）
        assertThat(k8s.namespaces().list().getItems()).isNotNull();
        translator = new K8sWorkspaceTranslator(k8s);
    }

    @AfterAll
    void tearDown() {
        try {
            k8s.namespaces().withName(NS).delete();
        } catch (Exception ignored) {
        }
        k8s.close();
    }

    @Test
    void createWorkspace_createsNamespaceNetworkPolicyQuota() {
        Workspace ws = new Workspace();
        ws.setId(888L);
        ws.setName("it-ws");
        ws.setNamespace(NS);
        ws.setTenantId(999L);

        translator.createNamespace(ws);
        translator.createNetworkPolicy(ws);
        translator.createResourceQuota(ws);

        // Namespace 真实创建
        var ns = k8s.namespaces().withName(NS).get();
        assertThat(ns).as("namespace 应在真实集群中创建").isNotNull();
        assertThat(ns.getMetadata().getLabels()).containsEntry("tenantId", "999");

        // NetworkPolicy 创建
        var netpols = k8s.network().networkPolicies().inNamespace(NS).list().getItems();
        assertThat(netpols).as("应有网络策略").isNotEmpty();

        // ResourceQuota 创建
        var quotas = k8s.resourceQuotas().inNamespace(NS).list().getItems();
        assertThat(quotas).as("应有资源配额").isNotEmpty();
    }
}
