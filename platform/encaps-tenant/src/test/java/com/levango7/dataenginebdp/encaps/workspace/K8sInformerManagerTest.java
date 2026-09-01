package com.levango7.dataenginebdp.encaps.workspace;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K8sInformerManager 单元测试（任务 E）。
 *
 * <p>enabled=false 时 getNamespacePhase 返回 null（调用方回源 API）；
 * enabled=true + fabric8 mock server 验证 informer 初始 list 缓存 phase。</p>
 *
 * <p>fabric8 7.x 迁移说明：KubernetesServer（before/after 手动生命周期）
 * 已移除，改用 JUnit5 扩展 {@link EnableKubernetesMockClient} 注入 mock
 * {@link KubernetesClient}（crud=true 启用 CRUD mock，等价旧构造参数）。</p>
 */
@EnableKubernetesMockClient(crud = true)
class K8sInformerManagerTest {

    private io.fabric8.kubernetes.client.KubernetesClient client;

    @Test
    void disabled_returnsNull_soCallerFallsBack() {
        K8sInformerManager manager = new K8sInformerManager(null, false);
        assertThat(manager.isEnabled()).isFalse();
        assertThat(manager.getNamespacePhase("any-ns")).isNull();
        assertThat(manager.getCachedPhase("any-ns")).isNull();
    }

    @Test
    void enabled_withNullClient_degradesSafely() {
        // enabled=true 但 client 为 null（mock 模式）：不启动 informer，get 返回 null
        K8sInformerManager manager = new K8sInformerManager(null, true);
        assertThat(manager.isEnabled()).isTrue();
        assertThat(manager.getNamespacePhase("ns")).isNull();
    }

    @Test
    void informerEvent_updatesPhaseCache() throws Exception {
        // 预创建 namespace（informer 启动时 list 到）
        client.namespaces().resource(new NamespaceBuilder()
                .withNewMetadata().withName("it-informer-ns")
                .endMetadata()
                .withNewStatus().withPhase("Active").endStatus()
                .build()).create();

        K8sInformerManager manager = new K8sInformerManager(client, true);
        // informer 初始 list 后应缓存到 ns（等待异步同步）
        Thread.sleep(1500);
        String phase = manager.getCachedPhase("it-informer-ns");
        assertThat(phase).as("informer 应通过初始 list 缓存 ns phase").isEqualTo("Active");
    }
}
