package com.levango7.dataenginebdp.infra.orchestrator.service;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimList;
import io.fabric8.kubernetes.api.model.PersistentVolumeList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetList;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerList;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.api.model.events.v1.EventList;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetricsList;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressList;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyList;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.api.model.storage.StorageClassList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;

/**
 * Kubernetes API 客户端服务。
 *
 * <p>封装 fabric8 {@link KubernetesClient}，提供集群子资源查询：
 * NetworkPolicy / Service / Ingress / PV / PVC / StorageClass / HPA /
 * Node / 工作负载 / Event / metrics.k8s.io，以及
 * NetworkPolicy / HPA / PVC / VolumeSnapshot 的创建、更新、删除能力。</p>
 *
 * <p>配置项（{@code app.k8s.*}）：</p>
 * <ul>
 *   <li>{@code app.k8s.mock-enabled} — true 时不连接真实集群，所有查询返回空列表；
 *       写操作在 mock 模式下无意义，直接抛 {@link IllegalStateException}（诚实失败，不假装成功）</li>
 * </ul>
 *
 * <p>线程安全：fabric8 KubernetesClient 内部维护连接池，可并发使用。</p>
 */
@Slf4j
@org.springframework.stereotype.Service
public class K8sClientService {

    /**
     * VolumeSnapshot CRD 名称（snapshot.storage.k8s.io API group）。
     *
     * <p>各发行版命名字段不同（external-snapshotter 统一为 {@code volumesnapshots.snapshot.storage.k8s.io}），
     * 快照端点先探测该 CRD 是否存在，不存在则向调用方返回 501（诚实失败）。</p>
     */
    private static final String VOLUME_SNAPSHOT_CRD_NAME = "volumesnapshots.snapshot.storage.k8s.io";

    /** VolumeSnapshot 自定义资源的 apiVersion。 */
    private static final String VOLUME_SNAPSHOT_API_VERSION = "snapshot.storage.k8s.io/v1";

    /** VolumeSnapshot 自定义资源的 kind。 */
    private static final String VOLUME_SNAPSHOT_KIND = "VolumeSnapshot";

    /** ClusterEvent 列表默认返回条数（按 eventTime 倒序取 N 条）。 */
    private static final int DEFAULT_EVENT_LIMIT = 50;

    private KubernetesClient client;
    private final boolean mockEnabled;

    /**
     * 构造服务。
     *
     * @param mockEnabled 是否启用 mock 模式（不连接真实集群）
     */
    public K8sClientService(@Value("${app.k8s.mock-enabled:false}") boolean mockEnabled) {
        this.mockEnabled = mockEnabled;
        if (mockEnabled) {
            this.client = null;
            log.warn("K8s mock mode enabled, K8sClientService will return empty lists");
        } else {
            log.info("Initializing KubernetesClient from default KUBECONFIG");
            this.client = new KubernetesClientBuilder().build();
        }
    }

    /** 列出全部 NetworkPolicy（跨 namespace）。 */
    public List<NetworkPolicy> listNetworkPolicies() {
        if (mockEnabled || client == null) {
            return List.of();
        }
        NetworkPolicyList list = client.network().networkPolicies().inAnyNamespace().list();
        return list.getItems();
    }

    /** 列出全部 Service（跨 namespace）。 */
    public List<io.fabric8.kubernetes.api.model.Service> listServices() {
        if (mockEnabled || client == null) {
            return List.of();
        }
        ServiceList list = client.services().inAnyNamespace().list();
        return list.getItems();
    }

    /** 列出全部 Ingress（跨 namespace，networking.k8s.io/v1）。 */
    public List<Ingress> listIngresses() {
        if (mockEnabled || client == null) {
            return List.of();
        }
        IngressList list = client.network().v1().ingresses().inAnyNamespace().list();
        return list.getItems();
    }

    /** 列出全部 PersistentVolume。 */
    public List<PersistentVolume> listPersistentVolumes() {
        if (mockEnabled || client == null) {
            return List.of();
        }
        PersistentVolumeList list = client.persistentVolumes().list();
        return list.getItems();
    }

    /** 列出全部 PersistentVolumeClaim（跨 namespace）。 */
    public List<PersistentVolumeClaim> listPersistentVolumeClaims() {
        if (mockEnabled || client == null) {
            return List.of();
        }
        PersistentVolumeClaimList list = client.persistentVolumeClaims().inAnyNamespace().list();
        return list.getItems();
    }

    /** 列出全部 StorageClass。 */
    public List<StorageClass> listStorageClasses() {
        if (mockEnabled || client == null) {
            return List.of();
        }
        StorageClassList list = client.storage().storageClasses().list();
        return list.getItems();
    }

    /** 列出全部 HorizontalPodAutoscaler（autoscaling/v2，跨 namespace）。 */
    public List<HorizontalPodAutoscaler> listHpas() {
        if (mockEnabled || client == null) {
            return List.of();
        }
        // fabric8 6.x：通过 autoscaling().v2() 专用 API 调用 HPA
        HorizontalPodAutoscalerList list = client.autoscaling().v2()
                .horizontalPodAutoscalers().inAnyNamespace().list();
        return list.getItems();
    }

    /** 列出集群中所有自定义资源（按 kind 过滤，跨 namespace）。 */
    public <T extends HasMetadata> List<T> listResources(Class<T> clazz) {
        if (mockEnabled || client == null) {
            return List.of();
        }
        return client.resources(clazz).list().getItems();
    }

    /* ================================================================ */
    /* 读：Node / 工作负载 / Pod / Event / NodeMetrics                    */
    /* ================================================================ */

    /** 列出全部 Node（跨集群，无需分 namespace）。 */
    public List<Node> listNodes() {
        if (mockEnabled || client == null) {
            return List.of();
        }
        NodeList list = client.nodes().list();
        return list != null && list.getItems() != null ? list.getItems() : List.of();
    }

    /** 列出全部 Deployment（跨 namespace）。 */
    public List<Deployment> listDeployments() {
        if (mockEnabled || client == null) {
            return List.of();
        }
        DeploymentList list = client.apps().deployments().inAnyNamespace().list();
        return list != null && list.getItems() != null ? list.getItems() : List.of();
    }

    /** 列出全部 StatefulSet（跨 namespace）。 */
    public List<StatefulSet> listStatefulSets() {
        if (mockEnabled || client == null) {
            return List.of();
        }
        StatefulSetList list = client.apps().statefulSets().inAnyNamespace().list();
        return list != null && list.getItems() != null ? list.getItems() : List.of();
    }

    /** 列出全部 DaemonSet（跨 namespace）。 */
    public List<DaemonSet> listDaemonSets() {
        if (mockEnabled || client == null) {
            return List.of();
        }
        DaemonSetList list = client.apps().daemonSets().inAnyNamespace().list();
        return list != null && list.getItems() != null ? list.getItems() : List.of();
    }

    /** 列出全部 Pod（跨 namespace）。 */
    public List<Pod> listPods() {
        if (mockEnabled || client == null) {
            return List.of();
        }
        PodList list = client.pods().inAnyNamespace().list();
        return list != null && list.getItems() != null ? list.getItems() : List.of();
    }

    /**
     * 列出集群事件（events.k8s.io/v1，跨 namespace），按 eventTime 倒序。
     *
     * <p>返回条数取 {@link #DEFAULT_EVENT_LIMIT}（50）条，供前端
     * {@code GET .../scale/events} 展示。</p>
     */
    public List<Event> listEvents() {
        return listEvents(DEFAULT_EVENT_LIMIT);
    }

    /**
     * 列出集群事件（events.k8s.io/v1，跨 namespace），按 eventTime 倒序取 {@code limit} 条。
     *
     * @param limit 最大返回条数（<=0 时返回空列表）
     * @return 事件列表（时间倒序；无事件时为空列表）
     */
    public List<Event> listEvents(int limit) {
        if (mockEnabled || client == null || limit <= 0) {
            return List.of();
        }
        EventList list = client.events().v1().events().inAnyNamespace().list();
        if (list == null || list.getItems() == null) {
            return List.of();
        }
        List<Event> sorted = list.getItems().stream()
                .sorted(java.util.Comparator.comparing(
                        (Event e) -> e.getEventTime() != null ? e.getEventTime().toString() : "",
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .reversed())
                .limit(limit)
                .toList();
        return sorted;
    }

    /**
     * 查询全部 Node 的 usage 指标（metrics.k8s.io）。
     *
     * <p><b>口径说明（honest failure）：</b>集群未部署 metrics-server 时
     * metrics.k8s.io API 不存在，本方法返回 {@code null}（区别于「有 API 但无数据」的
     * 空列表），调用方据此把 cpuUsed/memUsed 置 null 并标注
     * {@code metricsAvailable=false}，<b>不编造用量数字</b>。</p>
     *
     * @return NodeMetrics 列表；metrics.k8s.io 不可用或 mock 模式下返回 {@code null}
     */
    public List<NodeMetrics> listNodeMetrics() {
        if (mockEnabled || client == null) {
            return null;
        }
        try {
            NodeMetricsList list = client.top().nodes().metrics();
            return list != null && list.getItems() != null ? list.getItems() : List.of();
        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            // 典型场景：集群未安装 metrics-server（404）/权限不足（403）——如实上报不可用，不编造
            log.warn("metrics.k8s.io unavailable (code={}), node usage metrics not returned: {}",
                    e.getCode(), e.getMessage());
            return null;
        }
    }

    /* ================================================================ */
    /* 探测：VolumeSnapshot CRD                                          */
    /* ================================================================ */

    /**
     * 探测 VolumeSnapshot CRD（{@code volumesnapshots.snapshot.storage.k8s.io}）是否存在。
     *
     * <p>mock 模式或无 client 时返回 {@code false}（无集群即无 CRD，调用方据此返回 501）。</p>
     *
     * @return true = CRD 已安装，可创建快照
     */
    public boolean volumeSnapshotCrdExists() {
        if (mockEnabled || client == null) {
            return false;
        }
        try {
            CustomResourceDefinition crd = client.apiextensions().v1()
                    .customResourceDefinitions()
                    .withName(VOLUME_SNAPSHOT_CRD_NAME)
                    .get();
            return crd != null;
        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            log.warn("VolumeSnapshot CRD probe failed (code={}): {}", e.getCode(), e.getMessage());
            return false;
        }
    }

    /* ================================================================ */
    /* 写：NetworkPolicy                                                 */
    /* ================================================================ */

    /**
     * 创建 NetworkPolicy。
     *
     * @param policy 已构造好的 NetworkPolicy（含 metadata.namespace）
     * @return 集群返回的对象
     * @throws IllegalStateException mock 模式下不可写
     */
    public NetworkPolicy createNetworkPolicy(NetworkPolicy policy) {
        requireWritable();
        return client.network().networkPolicies()
                .inNamespace(policy.getMetadata().getNamespace())
                .resource(policy)
                .create();
    }

    /**
     * 删除 NetworkPolicy。
     *
     * @param namespace 命名空间
     * @param name      策略名
     * @return true = 集群中存在并已删除；false = 不存在（删除零条）
     * @throws IllegalStateException mock 模式下不可写
     */
    public boolean deleteNetworkPolicy(String namespace, String name) {
        requireWritable();
        // fabric8 8.x：Resource#delete() 返回被删对象的状态列表，空列表 = 目标不存在
        return !client.network().networkPolicies().inNamespace(namespace)
                .withName(name)
                .delete()
                .isEmpty();
    }

    /* ================================================================ */
    /* 写：HorizontalPodAutoscaler                                       */
    /* ================================================================ */

    /**
     * 创建 HPA。
     *
     * @param hpa 已构造好的 HPA（autoscaling/v2，含 metadata.namespace）
     * @return 集群返回的对象
     * @throws IllegalStateException mock 模式下不可写
     */
    public HorizontalPodAutoscaler createHpa(HorizontalPodAutoscaler hpa) {
        requireWritable();
        return client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(hpa.getMetadata().getNamespace())
                .resource(hpa)
                .create();
    }

    /**
     * 更新 HPA（整对象 replace；不存在时 fabric8 抛 404 KubernetesClientException，由 Controller 转译）。
     *
     * @param hpa 完整 HPA 对象（metadata.name + spec.min/max）
     * @return 集群返回的对象
     * @throws IllegalStateException mock 模式下不可写
     */
    public HorizontalPodAutoscaler replaceHpa(HorizontalPodAutoscaler hpa) {
        requireWritable();
        return client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(hpa.getMetadata().getNamespace())
                .resource(hpa)
                .replace();
    }

    /**
     * 删除 HPA。
     *
     * @param namespace 命名空间
     * @param name      HPA 名
     * @return true = 存在并已删除；false = 不存在（空状态列表）
     * @throws IllegalStateException mock 模式下不可写
     */
    public boolean deleteHpa(String namespace, String name) {
        requireWritable();
        return !client.autoscaling().v2().horizontalPodAutoscalers().inNamespace(namespace)
                .withName(name)
                .delete()
                .isEmpty();
    }

    /* ================================================================ */
    /* 写：PersistentVolumeClaim / VolumeSnapshot                        */
    /* ================================================================ */

    /**
     * 创建 PVC。
     *
     * @param pvc 已构造好的 PVC（含 metadata.namespace）
     * @return 集群返回的对象
     * @throws IllegalStateException mock 模式下不可写
     */
    public PersistentVolumeClaim createPvc(PersistentVolumeClaim pvc) {
        requireWritable();
        return client.persistentVolumeClaims()
                .inNamespace(pvc.getMetadata().getNamespace())
                .resource(pvc)
                .create();
    }

    /**
     * 删除 PVC。
     *
     * @param namespace 命名空间
     * @param name      PVC 名
     * @return true = 存在并已删除；false = 不存在（空状态列表）
     * @throws IllegalStateException mock 模式下不可写
     */
    public boolean deletePvc(String namespace, String name) {
        requireWritable();
        return !client.persistentVolumeClaims().inNamespace(namespace)
                .withName(name)
                .delete()
                .isEmpty();
    }

    /**
     * 创建 VolumeSnapshot 自定义资源（snapshot.storage.k8s.io/v1）。
     *
     * <p>前提：{@link #volumeSnapshotCrdExists()} 已确认 CRD 存在；否则应由调用方返回 501。
     * 使用 fabric8 泛型自定义资源机制（{@code genericKubernetesResources}），
     * spec（source/volumeSnapshotClassName）通过 additionalProperties 承载。</p>
     *
     * @param namespace               PVC 所在命名空间（快照同命名空间）
     * @param pvcName                 源 PVC 名
     * @param snapshotName            快照名
     * @param volumeSnapshotClassName 快照类名（通常取 PVC 的 StorageClass）
     * @return 集群返回的自定义资源对象
     * @throws IllegalStateException mock 模式下不可写
     */
    public GenericKubernetesResource createVolumeSnapshot(String namespace,
                                                          String pvcName,
                                                          String snapshotName,
                                                          String volumeSnapshotClassName) {
        requireWritable();
        ResourceDefinitionContext ctx = new ResourceDefinitionContext.Builder()
                .withGroup("snapshot.storage.k8s.io")
                .withVersion("v1")
                .withKind(VOLUME_SNAPSHOT_KIND)
                .withPlural("volumesnapshots")
                .withNamespaced(true)
                .build();
        Map<String, Object> spec = new java.util.LinkedHashMap<>();
        spec.put("source", Map.of("persistentVolumeClaimName", pvcName));
        if (volumeSnapshotClassName != null && !volumeSnapshotClassName.isBlank()) {
            spec.put("volumeSnapshotClassName", volumeSnapshotClassName);
        }
        GenericKubernetesResource snapshot = new io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder()
                .withApiVersion(VOLUME_SNAPSHOT_API_VERSION)
                .withKind(VOLUME_SNAPSHOT_KIND)
                .withNewMetadata()
                    .withName(snapshotName)
                    .withNamespace(namespace)
                .endMetadata()
                .withAdditionalProperties(Map.of("spec", spec))
                .build();
        return client.genericKubernetesResources(ctx)
                .inNamespace(namespace)
                .resource(snapshot)
                .create();
    }

    /** 写操作前置校验：mock 模式 / 无 client 时无可写目标，诚实失败而非假装成功。 */
    private void requireWritable() {
        if (mockEnabled || client == null) {
            throw new IllegalStateException(
                    "K8s client unavailable (mock-enabled or not connected); write refused");
        }
    }

    /** 暴露底层 client（用于测试或扩展）。 */
    public KubernetesClient getClient() {
        return client;
    }

    /** 是否启用 mock 模式。 */
    public boolean isMockEnabled() {
        return mockEnabled;
    }

    /**
     * 优雅停机：关闭 KubernetesClient 并释放底层连接池资源。
     *
     * <p>多次调用安全：client 已为 null 时无操作。close() 抛异常时捕获并记录警告，
     * 不向上传播，确保 Spring 容器关闭流程不被中断。</p>
     */
    @PreDestroy
    public void destroy() {
        if (client != null) {
            try {
                client.close();
                log.info("KubernetesClient closed successfully");
            } catch (Exception e) {
                log.warn("Failed to close KubernetesClient: {}", e.getMessage());
            } finally {
                client = null;
            }
        }
    }
}
