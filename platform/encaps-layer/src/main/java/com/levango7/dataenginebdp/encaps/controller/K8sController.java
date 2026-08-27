package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.service.K8sClientService;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kubernetes 集群管理端点：暴露 namespace / pod / service / configmap 查询与健康检查。
 *
 * <p>所有读操作在集群不可达时返回 503（健康检查端点返回 200 + {@code status:DOWN}），
 * 由 {@link K8sClientService} 内部优雅降级决定。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/k8s")
@Tag(name = "Kubernetes 集群管理", description = "K8s namespace/pod/service/configmap 查询与健康检查")
public class K8sController {

    private final K8sClientService k8sClientService;

    /**
     * 健康检查：验证 K8s 集群连通性。
     *
     * @return 200 + {@code {status:UP|DOWN}}
     */
    @Operation(summary = "K8s 健康检查", description = "验证 K8s 集群连通性，集群不可达时 status=DOWN")
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean ok = k8sClientService.healthCheck();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ok ? "UP" : "DOWN");
        body.put("available", ok);
        return ResponseEntity.ok(body);
    }

    /**
     * 列出所有 namespace。
     *
     * @return 200 + namespace 名称列表；集群不可用时返回 503
     */
    @Operation(summary = "列出 namespace", description = "查询 K8s 集群所有 namespace")
    @GetMapping("/namespaces")
    public ResponseEntity<?> listNamespaces() {
        if (!k8sClientService.healthCheck()) {
            return ResponseEntity.status(503).body(Map.of("error", "K8s 集群不可达"));
        }
        List<String> namespaces = k8sClientService.listNamespaces();
        log.info("列出 namespace: count={}", namespaces.size());
        return ResponseEntity.ok(Map.of("items", namespaces, "total", namespaces.size()));
    }

    /**
     * 列出指定 namespace 下的 pod。
     *
     * @param namespace K8s namespace
     * @return 200 + pod 列表；集群不可用时返回 503
     */
    @Operation(summary = "列出 pod", description = "查询指定 namespace 下的所有 pod")
    @GetMapping("/{namespace}/pods")
    public ResponseEntity<?> listPods(@PathVariable String namespace) {
        if (!k8sClientService.healthCheck()) {
            return ResponseEntity.status(503).body(Map.of("error", "K8s 集群不可达"));
        }
        List<Pod> pods = k8sClientService.listPods(namespace);
        List<Map<String, Object>> view = pods.stream().map(this::podToView).toList();
        log.info("列出 pod: namespace={}, count={}", namespace, view.size());
        return ResponseEntity.ok(Map.of("items", view, "total", view.size()));
    }

    /**
     * 获取 pod 详情。
     *
     * @param namespace K8s namespace
     * @param podName   pod 名称
     * @return 200 + pod 详情；不存在返回 404；集群不可用返回 503
     */
    @Operation(summary = "获取 pod 详情", description = "按 namespace 和名称获取 pod 详情")
    @GetMapping("/{namespace}/pods/{podName}")
    public ResponseEntity<?> getPod(@PathVariable String namespace, @PathVariable String podName) {
        if (!k8sClientService.healthCheck()) {
            return ResponseEntity.status(503).body(Map.of("error", "K8s 集群不可达"));
        }
        Pod pod = k8sClientService.getPod(namespace, podName);
        if (pod == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(podToView(pod));
    }

    /**
     * 列出指定 namespace 下的 service。
     *
     * @param namespace K8s namespace
     * @return 200 + service 列表；集群不可用时返回 503
     */
    @Operation(summary = "列出 service", description = "查询指定 namespace 下的所有 service")
    @GetMapping("/{namespace}/services")
    public ResponseEntity<?> listServices(@PathVariable String namespace) {
        if (!k8sClientService.healthCheck()) {
            return ResponseEntity.status(503).body(Map.of("error", "K8s 集群不可达"));
        }
        List<Service> services = k8sClientService.listServices(namespace);
        List<Map<String, Object>> view = services.stream().map(this::serviceToView).toList();
        log.info("列出 service: namespace={}, count={}", namespace, view.size());
        return ResponseEntity.ok(Map.of("items", view, "total", view.size()));
    }

    /**
     * 获取 configmap 详情。
     *
     * @param namespace K8s namespace
     * @param name      configmap 名称
     * @return 200 + configmap 详情；不存在返回 404；集群不可用返回 503
     */
    @Operation(summary = "获取 configmap", description = "按 namespace 和名称获取 configmap 详情")
    @GetMapping("/{namespace}/configmaps/{name}")
    public ResponseEntity<?> getConfigMap(@PathVariable String namespace, @PathVariable String name) {
        if (!k8sClientService.healthCheck()) {
            return ResponseEntity.status(503).body(Map.of("error", "K8s 集群不可达"));
        }
        ConfigMap cm = k8sClientService.getConfigMap(namespace, name);
        if (cm == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", cm.getMetadata() != null ? cm.getMetadata().getName() : null);
        body.put("namespace", cm.getMetadata() != null ? cm.getMetadata().getNamespace() : null);
        body.put("data", cm.getData());
        return ResponseEntity.ok(body);
    }

    // ==================== 视图映射 ====================

    /** pod 视图：提取关键字段，避免序列化整个 fabric8 模型。 */
    private Map<String, Object> podToView(Pod pod) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (pod.getMetadata() != null) {
            m.put("name", pod.getMetadata().getName());
            m.put("namespace", pod.getMetadata().getNamespace());
        }
        if (pod.getStatus() != null) {
            m.put("phase", pod.getStatus().getPhase());
            m.put("podIP", pod.getStatus().getPodIP());
            m.put("hostIP", pod.getStatus().getHostIP());
            m.put("startTime", pod.getStatus().getStartTime());
        }
        return m;
    }

    /** service 视图：提取关键字段。 */
    private Map<String, Object> serviceToView(Service svc) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (svc.getMetadata() != null) {
            m.put("name", svc.getMetadata().getName());
            m.put("namespace", svc.getMetadata().getNamespace());
        }
        if (svc.getSpec() != null) {
            m.put("type", svc.getSpec().getType());
            m.put("clusterIP", svc.getSpec().getClusterIP());
            m.put("ports", svc.getSpec().getPorts());
        }
        return m;
    }
}