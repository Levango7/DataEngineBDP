package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.K8sClientService;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Kubernetes 集群管理端点：暴露 namespace / pod / service / configmap 查询与健康检查。
 *
 * <p>所有读操作在集群不可达时返回 503（健康检查端点返回 200 + {@code status:DOWN}），
 * 由 {@link K8sClientService} 内部优雅降级决定。</p>
 *
 * <p>安全控制（R8 修复）：
 * <ul>
 *   <li>类级 {@code @PreAuthorize("hasRole('SUPER_ADMIN')")}：仅平台超管可访问 K8s 集群管理端点，
 *       防止任意认证用户遍历所有 namespace 的 pod/service/configmap。</li>
 *   <li>租户隔离：从 {@link TenantContext} 读取当前租户 ID 作为 namespace 过滤条件，
 *       请求的 namespace 必须与当前租户 ID 匹配，否则返回 403。
 *       listNamespaces 仅返回当前租户对应的 namespace。</li>
 *   <li>configmap 脱敏：返回 data 时对 key 名包含 password/secret/token/key 等敏感字段的
 *       值替换为 {@code ***}，防止数据库密码、API 密钥等敏感信息泄露。</li>
 * </ul></p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/k8s")
@Tag(name = "Kubernetes 集群管理", description = "K8s namespace/pod/service/configmap 查询与健康检查")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class K8sController {

    private final K8sClientService k8sClientService;

    /** configmap data 字段中需要脱敏的 key 关键词（小写匹配）。 */
    private static final List<String> SENSITIVE_KEY_KEYWORDS = List.of(
            "password", "passwd", "pwd",
            "secret", "token", "apikey", "api_key",
            "key", "credential", "privatekey", "private_key");

    /** 脱敏占位值。 */
    private static final String MASKED_VALUE = "***";

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
     * 列出当前租户对应的 namespace。
     *
     * <p>租户隔离：仅返回当前租户上下文中的 namespace（即 tenantId），
     * 不再暴露集群全部 namespace 列表。</p>
     *
     * @return 200 + namespace 名称列表；集群不可用时返回 503；缺少租户上下文返回 403
     */
    @Operation(summary = "列出 namespace", description = "查询当前租户对应的 K8s namespace（租户隔离）")
    @GetMapping("/namespaces")
    public ResponseEntity<?> listNamespaces() {
        String tenantNs = requireTenantNamespace();
        if (tenantNs == null) {
            return ResponseEntity.status(403).body(Map.of("error", "缺少租户上下文"));
        }
        if (!k8sClientService.healthCheck()) {
            return ResponseEntity.status(503).body(Map.of("error", "K8s 集群不可达"));
        }
        // 租户隔离：仅返回当前租户对应的 namespace，而非集群全部 namespace
        List<String> namespaces = k8sClientService.listNamespaces().stream()
                .filter(ns -> Objects.equals(ns, tenantNs))
                .collect(Collectors.toList());
        log.info("列出 namespace（租户隔离）: tenant={}, count={}", tenantNs, namespaces.size());
        return ResponseEntity.ok(Map.of("items", namespaces, "total", namespaces.size()));
    }

    /**
     * 列出指定 namespace 下的 pod。
     *
     * <p>租户隔离：namespace 必须与当前租户 ID 匹配，否则返回 403。</p>
     *
     * @param namespace K8s namespace
     * @return 200 + pod 列表；集群不可用时返回 503；namespace 越权返回 403
     */
    @Operation(summary = "列出 pod", description = "查询指定 namespace 下的所有 pod（租户隔离）")
    @GetMapping("/{namespace}/pods")
    public ResponseEntity<?> listPods(@PathVariable String namespace) {
        if (!isNamespaceAllowed(namespace)) {
            return ResponseEntity.status(403).body(Map.of("error", "namespace 越权，仅允许访问当前租户 namespace"));
        }
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
     * <p>租户隔离：namespace 必须与当前租户 ID 匹配，否则返回 403。</p>
     *
     * @param namespace K8s namespace
     * @param podName   pod 名称
     * @return 200 + pod 详情；不存在返回 404；集群不可用返回 503；namespace 越权返回 403
     */
    @Operation(summary = "获取 pod 详情", description = "按 namespace 和名称获取 pod 详情（租户隔离）")
    @GetMapping("/{namespace}/pods/{podName}")
    public ResponseEntity<?> getPod(@PathVariable String namespace, @PathVariable String podName) {
        if (!isNamespaceAllowed(namespace)) {
            return ResponseEntity.status(403).body(Map.of("error", "namespace 越权，仅允许访问当前租户 namespace"));
        }
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
     * <p>租户隔离：namespace 必须与当前租户 ID 匹配，否则返回 403。</p>
     *
     * @param namespace K8s namespace
     * @return 200 + service 列表；集群不可用时返回 503；namespace 越权返回 403
     */
    @Operation(summary = "列出 service", description = "查询指定 namespace 下的所有 service（租户隔离）")
    @GetMapping("/{namespace}/services")
    public ResponseEntity<?> listServices(@PathVariable String namespace) {
        if (!isNamespaceAllowed(namespace)) {
            return ResponseEntity.status(403).body(Map.of("error", "namespace 越权，仅允许访问当前租户 namespace"));
        }
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
     * <p>租户隔离：namespace 必须与当前租户 ID 匹配，否则返回 403。</p>
     * <p>敏感字段脱敏：data 中 key 名包含 password/secret/token/key 等关键词的值替换为 {@code ***}。</p>
     *
     * @param namespace K8s namespace
     * @param name      configmap 名称
     * @return 200 + configmap 详情（已脱敏）；不存在返回 404；集群不可用返回 503；namespace 越权返回 403
     */
    @Operation(summary = "获取 configmap", description = "按 namespace 和名称获取 configmap 详情（租户隔离+敏感字段脱敏）")
    @GetMapping("/{namespace}/configmaps/{name}")
    public ResponseEntity<?> getConfigMap(@PathVariable String namespace, @PathVariable String name) {
        if (!isNamespaceAllowed(namespace)) {
            return ResponseEntity.status(403).body(Map.of("error", "namespace 越权，仅允许访问当前租户 namespace"));
        }
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
        body.put("data", maskSensitiveData(cm.getData()));
        return ResponseEntity.ok(body);
    }

    // ==================== 安全辅助 ====================

    /**
     * 获取当前租户对应的 namespace（即 tenantId）。
     *
     * @return 租户 namespace；若缺少租户上下文则返回 {@code null}
     */
    private String requireTenantNamespace() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        return tenantId;
    }

    /**
     * 校验请求的 namespace 是否属于当前租户。
     *
     * <p>租户隔离核心：namespace 必须与 TenantContext 中的 tenantId 完全匹配，
     * 防止跨租户遍历他租户的 pod/service/configmap。</p>
     *
     * @param namespace 请求的 namespace
     * @return true 若允许访问；false 若 namespace 为空或与当前租户不匹配
     */
    private boolean isNamespaceAllowed(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return false;
        }
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        return Objects.equals(namespace, tenantId);
    }

    /**
     * 对 configmap 的 data 进行敏感字段脱敏。
     *
     * <p>key 名（小写）包含 password/secret/token/key/credential 等关键词时，
     * 将对应值替换为 {@code ***}，防止数据库密码、API 密钥等敏感信息通过 API 暴露。</p>
     *
     * @param data 原始 configmap data
     * @return 脱敏后的 data；输入为 null 时返回空 map
     */
    private Map<String, String> maskSensitiveData(Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && isSensitiveKey(key)) {
                masked.put(key, MASKED_VALUE);
            } else {
                masked.put(key, value);
            }
        }
        return masked;
    }

    /**
     * 判断 configmap data 的 key 是否为敏感字段。
     *
     * @param key data key
     * @return true 若 key 名包含敏感关键词
     */
    private boolean isSensitiveKey(String key) {
        String lowerKey = key.toLowerCase();
        for (String keyword : SENSITIVE_KEY_KEYWORDS) {
            if (lowerKey.contains(keyword)) {
                return true;
            }
        }
        return false;
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
