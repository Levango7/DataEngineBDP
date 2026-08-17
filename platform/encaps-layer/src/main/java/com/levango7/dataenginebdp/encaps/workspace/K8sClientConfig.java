package com.levango7.dataenginebdp.encaps.workspace;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kubernetes Client 配置。
 *
 * <p>使用 fabric8 {@link KubernetesClientBuilder} 构造 {@link KubernetesClient}，
 * 默认从 KUBECONFIG 环境变量或 {@code ~/.kube/config} 加载集群连接信息。
 * 测试环境通过 {@link #mockEnabled} 开关关闭自动连接，由测试代码注入 mock client。</p>
 *
 * <p>配置项（{@code app.k8s.*}）：</p>
 * <ul>
 *   <li>{@code app.k8s.mock-enabled} — 是否启用 mock 模式（不连接真实集群），默认 {@code false}</li>
 * </ul>
 */
@Configuration
public class K8sClientConfig {

    private static final Logger log = LoggerFactory.getLogger(K8sClientConfig.class);

    /**
     * 创建 KubernetesClient Bean。
     *
     * <p>当 {@code app.k8s.mock-enabled=true} 时返回一个不连接真实集群的空 client
     *（fabric8 懒连接，构建时不发起网络请求），避免下游 bean（如 K8sQuotaTranslator）
     * 因 null 注入失败。否则使用默认 KUBECONFIG 路径自动发现集群连接信息。</p>
     *
     * @param mockEnabled 是否启用 mock 模式
     * @return KubernetesClient 实例
     */
    @Bean
    public KubernetesClient kubernetesClient(
            @Value("${app.k8s.mock-enabled:false}") boolean mockEnabled) {
        if (mockEnabled) {
            log.warn("K8s mock mode enabled, returning a non-connected KubernetesClient "
                    + "(lazy connect; API calls will fail until a real cluster is available)");
            return new KubernetesClientBuilder().build();
        }
        log.info("Initializing KubernetesClient from default KUBECONFIG");
        return new KubernetesClientBuilder().build();
    }

    /**
     * K8s informer watch 缓存（任务 E）。
     *
     * <p>默认关闭（{@code app.k8s.informer-enabled=false}，保持现有直连行为）；
     * 开启后对 Namespace 注册 informer，本地缓存 name→phase（读路径降 API 压力）。</p>
     *
     * @param informerEnabled 是否启用 informer（默认 false）
     * @return K8sInformerManager（mock 模式下返回禁用实例）
     */
    @Bean
    public K8sInformerManager k8sInformerManager(
            @Value("${app.k8s.informer-enabled:false}") boolean informerEnabled,
            KubernetesClient kubernetesClient) {
        return new K8sInformerManager(kubernetesClient, informerEnabled);
    }
}