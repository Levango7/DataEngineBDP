package com.levango7.dataenginebdp.encaps.workspace;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.ConcurrentHashMap;

/**
 * K8s informer watch 缓存（任务 E）。
 *
 * <p>对 Namespace 注册 fabric8 informer，本地维护 {@code name → phase} 缓存。
 * {@link #getNamespacePhase(String)} 优先查本地缓存（API Server 压力↓），
 * miss 时回源 API（cache-aside）。</p>
 *
 * <p>开关：{@code app.k8s.informer-enabled=true}（默认 false，保持现有行为）。</p>
 */
@Slf4j
public class K8sInformerManager implements DisposableBean {

    private final KubernetesClient k8sClient;
    private final boolean enabled;
    private final ConcurrentHashMap<String, String> namespacePhaseCache = new ConcurrentHashMap<>();
    private SharedIndexInformer<Namespace> namespaceInformer;

    public K8sInformerManager(KubernetesClient k8sClient, boolean enabled) {
        this.k8sClient = k8sClient;
        this.enabled = enabled;
        if (enabled && k8sClient != null) {
            startNamespaceInformer();
        }
    }

    /** 是否启用 informer。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 启动 Namespace informer（事件驱动更新缓存）。 */
    private void startNamespaceInformer() {
        namespaceInformer = k8sClient.namespaces().inform(
                new ResourceEventHandler<>() {
                    @Override
                    public void onAdd(Namespace ns) {
                        namespacePhaseCache.put(ns.getMetadata().getName(), phaseOf(ns));
                    }

                    @Override
                    public void onUpdate(Namespace oldNs, Namespace newNs) {
                        namespacePhaseCache.put(newNs.getMetadata().getName(), phaseOf(newNs));
                    }

                    @Override
                    public void onDelete(Namespace ns, boolean deletedFinalStateUnknown) {
                        namespacePhaseCache.remove(ns.getMetadata().getName());
                    }
                });
        log.info("K8s Namespace informer 已启动（watch 缓存）");
    }

    /**
     * 查询 Namespace phase：优先本地缓存，miss 回源 API。
     *
     * @param namespace 命名空间名
     * @return phase（Active/Terminating/NotFound/Unknown）
     */
    public String getNamespacePhase(String namespace) {
        if (!enabled || namespaceInformer == null) {
            return null; // 未启用：调用方回源 API
        }
        String cached = namespacePhaseCache.get(namespace);
        if (cached != null) {
            return cached;
        }
        // cache-aside：miss 回源
        try {
            Namespace ns = k8sClient.namespaces().withName(namespace).get();
            if (ns == null) {
                namespacePhaseCache.put(namespace, "NotFound");
                return "NotFound";
            }
            String phase = phaseOf(ns);
            namespacePhaseCache.put(namespace, phase);
            return phase;
        } catch (Exception e) {
            log.warn("Namespace 回源查询失败: {} err={}", namespace, e.getMessage());
            return null;
        }
    }

    /** 获取缓存中的 phase（不触发回源，纯本地读）。 */
    public String getCachedPhase(String namespace) {
        return namespacePhaseCache.get(namespace);
    }

    private String phaseOf(Namespace ns) {
        if (ns.getStatus() != null && ns.getStatus().getPhase() != null) {
            return ns.getStatus().getPhase();
        }
        return "Unknown";
    }

    @Override
    public void destroy() {
        if (namespaceInformer != null) {
            namespaceInformer.close();
            log.info("K8s Namespace informer 已关闭");
        }
    }
}
