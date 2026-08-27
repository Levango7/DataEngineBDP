package com.levango7.dataenginebdp.encaps.service;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;

import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Kubernetes 客户端封装服务：基于 fabric8 KubernetesClient 6.x 调用 K8s API。
 *
 * <p>功能覆盖：namespace / pod / service / configmap 的查询与管理，
 * 以及集群连通性健康检查。</p>
 *
 * <p>初始化策略（由配置 {@code app.k8s.in-cluster} 决定）：
 * <ul>
 *   <li>{@code true}  — 使用 InClusterConfig（Pod 内 ServiceAccount token / CA / API server）</li>
 *   <li>{@code false} — 使用 kubeconfig 文件（默认 {@code ~/.kube/config}，可由
 *       {@code app.k8s.kubeconfig-path} 覆盖）</li>
 * </ul>
 * 若集群不可达，启动不中断，{@link #healthCheck()} 返回 {@code false} 实现优雅降级。</p>
 *
 * <p>fabric8 6.x 使用 {@link KubernetesClientBuilder} 取代旧版 {@code DefaultKubernetesClient}；
 * 客户端在 {@link PostConstruct} 中创建，{@link PreDestroy} 中关闭，避免资源泄漏。</p>
 */
@Slf4j
@Service
public class K8sClientService {

    /** 是否在 K8s 集群内运行（true=使用 in-cluster config, false=使用 kubeconfig）。 */
    @Value("${app.k8s.in-cluster:true}")
    private boolean inCluster;

    /** kubeconfig 路径（in-cluster=false 时使用）。 */
    @Value("${app.k8s.kubeconfig-path:~/.kube/config}")
    private String kubeconfigPath;

    /** 连接超时（毫秒）。 */
    @Value("${app.k8s.connect-timeout:10000}")
    private int connectTimeout;

    /** 请求超时（毫秒）。 */
    @Value("${app.k8s.request-timeout:30000}")
    private int requestTimeout;

    /** fabric8 KubernetesClient 实例（init 后非 null，destroy 后置 null）。 */
    private KubernetesClient client;

    /** 集群是否可用（init 阶段探测，false 时所有读操作返回空集，写操作抛 IllegalStateException）。 */
    private volatile boolean available = false;

    /**
     * 初始化 KubernetesClient。
     *
     * <p>构建 {@link Config} 时注入超时参数；in-cluster=true 时 fabric8 自动从
     * {@code /var/run/secrets/kubernetes.io/serviceaccount/} 读取 token 与 CA。</p>
     */
    @PostConstruct
    public void init() {
        try {
            // in-cluster=true 时 fabric8 自动探测 SA token；false 时回退到 KUBECONFIG 发现
            if (inCluster) {
                log.info("K8sClient 初始化：in-cluster 模式（ServiceAccount），connectTimeout={}ms, requestTimeout={}ms",
                        connectTimeout, requestTimeout);
            } else {
                // 展开 ~ 为 user.home（fabric8 不会自动展开）
                String resolvedPath = kubeconfigPath.startsWith("~/")
                        ? System.getProperty("user.home") + kubeconfigPath.substring(1)
                        : kubeconfigPath;
                log.info("K8sClient 初始化：kubeconfig 模式，path={}, connectTimeout={}ms, requestTimeout={}ms",
                        resolvedPath, connectTimeout, requestTimeout);
                // fabric8 6.x 通过系统属性 kubernetes.kubeconfig.file 指定 kubeconfig 路径
                System.setProperty(Config.KUBERNETES_KUBECONFIG_FILE, resolvedPath);
            }
            // ConfigBuilder 构建时会自动读取上述系统属性加载 kubeconfig
            Config config = new ConfigBuilder()
                    .withConnectionTimeout(connectTimeout)
                    .withRequestTimeout(requestTimeout)
                    .build();
            this.client = new KubernetesClientBuilder().withConfig(config).build();
            // 启动阶段探测连通性，失败不抛异常以支持优雅降级
            this.available = probeCluster();
            if (available) {
                log.info("K8sClient 初始化成功：集群连通，master-url={}", client.getMasterUrl());
            } else {
                log.warn("K8sClient 初始化完成但集群不可达：后续 K8s 操作将优雅降级（healthCheck=false）");
            }
        } catch (KubernetesClientException e) {
            log.error("K8sClient 初始化失败：{}", e.getMessage(), e);
            this.available = false;
        }
    }

    /**
     * 销毁时关闭 KubernetesClient，释放底层 OkHttp 连接池。
     */
    @PreDestroy
    public void destroy() {
        if (client != null) {
            try {
                client.close();
                log.info("K8sClient 已关闭");
            } catch (Exception e) {
                log.warn("关闭 K8sClient 时发生异常: {}", e.getMessage());
            } finally {
                client = null;
                available = false;
            }
        }
    }

    /**
     * 健康检查：验证 K8s 连接是否可用。
     *
     * <p>通过列举 namespace（轻量 GET /api/v1/namespaces）探测连通性。
     * 集群不可达时返回 {@code false}，不抛异常。</p>
     *
     * @return true 若集群可达且 client 已初始化
     */
    public boolean healthCheck() {
        if (!available || client == null) {
            return false;
        }
        return probeCluster();
    }

    /**
     * 列出所有 namespace。
     *
     * @return namespace 名称列表；集群不可用时返回空列表
     */
    public List<String> listNamespaces() {
        if (!ensureAvailable()) {
            return Collections.emptyList();
        }
        try {
            NamespaceList list = client.namespaces().list();
            if (list == null || list.getItems() == null) {
                return Collections.emptyList();
            }
            return list.getItems().stream()
                    .map(ns -> ns.getMetadata() != null ? ns.getMetadata().getName() : null)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (KubernetesClientException e) {
            log.error("列出 namespace 失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 列出指定 namespace 下的所有 pod。
     *
     * @param namespace K8s namespace（非空）
     * @return pod 列表；集群不可用或 namespace 不存在时返回空列表
     */
    public List<Pod> listPods(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            log.warn("listPods 被拒绝：namespace 为空");
            return Collections.emptyList();
        }
        if (!ensureAvailable()) {
            return Collections.emptyList();
        }
        try {
            PodList list = client.pods().inNamespace(namespace).list();
            return list == null || list.getItems() == null ? Collections.emptyList() : list.getItems();
        } catch (KubernetesClientException e) {
            log.error("列出 pod 失败: namespace={}", namespace, e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取指定 namespace 下某个 pod 的详情。
     *
     * @param namespace K8s namespace
     * @param podName   pod 名称
     * @return pod 对象；不存在或集群不可用时返回 {@code null}
     */
    public Pod getPod(String namespace, String podName) {
        if (namespace == null || namespace.isBlank() || podName == null || podName.isBlank()) {
            log.warn("getPod 被拒绝：namespace={} podName={}", namespace, podName);
            return null;
        }
        if (!ensureAvailable()) {
            return null;
        }
        try {
            return client.pods().inNamespace(namespace).withName(podName).get();
        } catch (KubernetesClientException e) {
            log.error("获取 pod 失败: namespace={}, podName={}", namespace, podName, e);
            return null;
        }
    }

    /**
     * 列出指定 namespace 下的所有 service。
     *
     * @param namespace K8s namespace
     * @return service 列表；集群不可用时返回空列表
     */
    public List<io.fabric8.kubernetes.api.model.Service> listServices(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            log.warn("listServices 被拒绝：namespace 为空");
            return Collections.emptyList();
        }
        if (!ensureAvailable()) {
            return Collections.emptyList();
        }
        try {
            ServiceList list = client.services().inNamespace(namespace).list();
            return list == null || list.getItems() == null ? Collections.emptyList() : list.getItems();
        } catch (KubernetesClientException e) {
            log.error("列出 service 失败: namespace={}", namespace, e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取指定 namespace 下某个 configmap。
     *
     * @param namespace K8s namespace
     * @param name      configmap 名称
     * @return configmap 对象；不存在或集群不可用时返回 {@code null}
     */
    public ConfigMap getConfigMap(String namespace, String name) {
        if (namespace == null || namespace.isBlank() || name == null || name.isBlank()) {
            log.warn("getConfigMap 被拒绝：namespace={} name={}", namespace, name);
            return null;
        }
        if (!ensureAvailable()) {
            return null;
        }
        try {
            return client.configMaps().inNamespace(namespace).withName(name).get();
        } catch (KubernetesClientException e) {
            log.error("获取 configmap 失败: namespace={}, name={}", namespace, name, e);
            return null;
        }
    }

    /**
     * 创建 namespace。
     *
     * @param name namespace 名称
     * @return true 若创建成功；已存在视为成功
     */
    public boolean createNamespace(String name) {
        if (name == null || name.isBlank()) {
            log.warn("createNamespace 被拒绝：name 为空");
            return false;
        }
        if (!ensureAvailable()) {
            return false;
        }
        try {
            Namespace ns = client.namespaces().resource(
                    new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                            .withNewMetadata().withName(name).endMetadata()
                            .build()
            ).serverSideApply();
            log.info("namespace 已创建（或已存在）: {}", name);
            return ns != null;
        } catch (KubernetesClientException e) {
            log.error("创建 namespace 失败: name={}", name, e);
            return false;
        }
    }

    /**
     * 删除 namespace。
     *
     * @param name namespace 名称
     * @return true 若删除成功或已不存在
     */
    public boolean deleteNamespace(String name) {
        if (name == null || name.isBlank()) {
            log.warn("deleteNamespace 被拒绝：name 为空");
            return false;
        }
        if (!ensureAvailable()) {
            return false;
        }
        try {
            // fabric8 6.x: delete() 返回 List<StatusDetails>，非空表示已删除
            java.util.List<io.fabric8.kubernetes.api.model.StatusDetails> details =
                    client.namespaces().withName(name).delete();
            boolean deleted = details != null && !details.isEmpty();
            log.info("namespace 删除结果: name={}, deleted={}", name, deleted);
            return deleted;
        } catch (KubernetesClientException e) {
            log.error("删除 namespace 失败: name={}", name, e);
            return false;
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 探测集群连通性：列举 namespace（轻量请求）。
     */
    private boolean probeCluster() {
        if (client == null) {
            return false;
        }
        try {
            client.namespaces().list();
            return true;
        } catch (KubernetesClientException e) {
            log.debug("集群探测失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 校验 client 可用性：集群不可达时记录一次警告并返回 false。
     */
    private boolean ensureAvailable() {
        if (!available || client == null) {
            log.warn("K8s 集群不可用，操作被跳过（优雅降级）");
            return false;
        }
        return true;
    }
}