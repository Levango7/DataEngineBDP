package com.levango7.dataenginebdp.infra.orchestrator.service;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimList;
import io.fabric8.kubernetes.api.model.PersistentVolumeList;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerList;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressList;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyList;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.api.model.storage.StorageClassList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

/**
 * Kubernetes API 客户端服务。
 *
 * <p>封装 fabric8 {@link KubernetesClient}，提供集群子资源查询：
 * NetworkPolicy / Service / Ingress / PV / PVC / StorageClass / HPA。</p>
 *
 * <p>配置项（{@code app.k8s.*}）：</p>
 * <ul>
 *   <li>{@code app.k8s.mock-enabled} — true 时不连接真实集群，所有查询返回空列表</li>
 * </ul>
 *
 * <p>线程安全：fabric8 KubernetesClient 内部维护连接池，可并发使用。</p>
 */
@Slf4j
@org.springframework.stereotype.Service
public class K8sClientService {

    private final KubernetesClient client;
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

    /** 暴露底层 client（用于测试或扩展）。 */
    public KubernetesClient getClient() {
        return client;
    }

    /** 是否启用 mock 模式。 */
    public boolean isMockEnabled() {
        return mockEnabled;
    }
}
