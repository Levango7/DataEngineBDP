package com.levango7.dataenginebdp.infra.orchestrator.controller;

import com.levango7.dataenginebdp.infra.orchestrator.model.ClusterCreateRequest;
import com.levango7.dataenginebdp.infra.orchestrator.model.ClusterInfo;
import com.levango7.dataenginebdp.infra.orchestrator.model.ClusterScaleRequest;
import com.levango7.dataenginebdp.infra.orchestrator.model.EnvironmentType;
import com.levango7.dataenginebdp.infra.orchestrator.model.SupplyResult;
import com.levango7.dataenginebdp.infra.orchestrator.registry.EnvironmentProfile;
import com.levango7.dataenginebdp.infra.orchestrator.registry.ProviderDescriptor;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.infra.orchestrator.service.K8sClientService;
import com.levango7.dataenginebdp.infra.orchestrator.service.ProviderRegistryService;
import com.levango7.dataenginebdp.infra.orchestrator.service.SupplyOrchestrator;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨环境统一集群供应 REST API 入口。
 *
 * <p>L0.5 编排层对上暴露的唯一集群管理 API，{@code environment} 字段（请求体或路径变量）
 * 决定路由到哪个下游 Provider。前端集群创建向导只需调用本 API，无需感知底层环境差异。</p>
 *
 * <p>API 端点：</p>
 * <ul>
 *   <li>{@code POST   /api/v1/clusters}                                 - 创建集群（请求体含 environment）</li>
 *   <li>{@code DELETE /api/v1/clusters/{environment}/{clusterId}}        - 销毁集群</li>
 *   <li>{@code GET    /api/v1/clusters/{environment}/{clusterId}}        - 查询集群</li>
 *   <li>{@code GET    /api/v1/clusters}                                  - 列出所有集群（跨环境）</li>
 *   <li>{@code GET    /api/v1/clusters/{environment}}                    - 列出指定环境集群</li>
 *   <li>{@code POST   /api/v1/clusters/{environment}/{clusterId}/scale}  - 扩缩容</li>
 *   <li>{@code GET    /api/v1/clusters/providers}                        - 列出已注册 Provider</li>
 *   <li>{@code GET    /api/v1/clusters/environments}                     - 列出支持的环境类型</li>
 *   <li>{@code GET    /api/v1/clusters/profiles}                         - 列出环境默认配置</li>
 * </ul>
 */
@RestController
@Tag(name = "基础设施编排-集群供应", description = "跨环境集群创建/销毁/扩缩容")
@RequestMapping("/api/v1/clusters")
public class ClusterController {

    private static final Logger log = LoggerFactory.getLogger(ClusterController.class);

    private final SupplyOrchestrator orchestrator;
    private final ProviderRegistryService providerRegistryService;
    private final K8sClientService k8sClientService;

    /**
     * 构造 Controller。
     *
     * @param orchestrator            供应编排器
     * @param providerRegistryService Provider 注册服务
     * @param k8sClientService        K8s API 客户端服务
     */
    public ClusterController(SupplyOrchestrator orchestrator,
                            ProviderRegistryService providerRegistryService,
                            K8sClientService k8sClientService) {
        this.orchestrator = orchestrator;
        this.providerRegistryService = providerRegistryService;
        this.k8sClientService = k8sClientService;
    }

    /**
     * 创建集群 - 统一入口。
     *
     * <p>请求体含 {@code environment} 字段，编排层据此路由到对应 Provider。</p>
     *
     * @param request 创建请求
     * @return 供应结果（201 Created）
     */
    @PostMapping
    public ResponseEntity<SupplyResult> createCluster(@Valid @RequestBody ClusterCreateRequest request) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            request.setTenantId(tenantId);
        }
        log.info("POST /api/v1/clusters - createCluster env={} name={} tenant={}",
                request.getEnvironment(), request.getClusterName(), request.getTenantId());
        SupplyResult result = orchestrator.createCluster(request);
        HttpStatus status = result.getPhase() == SupplyResult.Phase.SUCCEEDED
                ? HttpStatus.CREATED
                : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(result);
    }

    /**
     * 销毁集群。
     *
     * @param environment 环境类型字符串
     * @param clusterId   集群 ID
     * @return 集群信息
     */
    @DeleteMapping("/{environment}/{clusterId}")
    public ResponseEntity<ClusterInfo> destroyCluster(@PathVariable String environment,
                                                      @PathVariable String clusterId) {
        EnvironmentType env = EnvironmentType.fromString(environment);
        log.info("DELETE /api/v1/clusters/{}/{} - destroyCluster", env, clusterId);
        ClusterInfo info = orchestrator.destroyCluster(env, clusterId);
        return ResponseEntity.ok(info);
    }

    /**
     * 查询集群信息。
     *
     * @param environment 环境类型字符串
     * @param clusterId   集群 ID
     * @return 集群信息
     */
    @GetMapping("/{environment}/{clusterId}")
    public ResponseEntity<ClusterInfo> getClusterInfo(@PathVariable String environment,
                                                      @PathVariable String clusterId) {
        EnvironmentType env = EnvironmentType.fromString(environment);
        log.info("GET /api/v1/clusters/{}/{} - getClusterInfo", env, clusterId);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        return ResponseEntity.ok(info);
    }

    /**
     * 扩缩容集群。
     *
     * @param environment 环境类型字符串
     * @param clusterId   集群 ID
     * @param scaleReq    扩缩容请求
     * @return 集群信息
     */
    @PostMapping("/{environment}/{clusterId}/scale")
    public ResponseEntity<ClusterInfo> scaleCluster(@PathVariable String environment,
                                                    @PathVariable String clusterId,
                                                    @Valid @RequestBody ClusterScaleRequest scaleReq) {
        EnvironmentType env = EnvironmentType.fromString(environment);
        log.info("POST /api/v1/clusters/{}/{}/scale - scaleCluster target={}",
                env, clusterId, scaleReq.getTargetNodeCount());
        ClusterInfo info = orchestrator.scaleCluster(env, clusterId, scaleReq);
        return ResponseEntity.ok(info);
    }

    /**
     * 列出指定环境的全部集群。
     *
     * @param environment 环境类型字符串
     * @return 集群列表
     */
    @GetMapping("/{environment}")
    public ResponseEntity<List<ClusterInfo>> listClustersByEnvironment(@PathVariable String environment) {
        EnvironmentType env = EnvironmentType.fromString(environment);
        log.info("GET /api/v1/clusters/{} - listClusters", env);
        List<ClusterInfo> clusters = orchestrator.listClusters(env);
        return ResponseEntity.ok(clusters);
    }

    /**
     * 列出所有集群（跨环境聚合）。
     *
     * @return 集群列表
     */
    @GetMapping
    public ResponseEntity<List<ClusterInfo>> listAllClusters() {
        log.info("GET /api/v1/clusters - listAllClusters");
        List<ClusterInfo> clusters = orchestrator.listAllClusters();
        return ResponseEntity.ok(clusters);
    }

    /**
     * 列出已注册 Provider。
     *
     * @return Provider 描述符列表
     */
    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> listProviders() {
        List<ProviderDescriptor> providers = providerRegistryService.listProviders();
        return ResponseEntity.ok(Map.of(
                "providers", providers,
                "total", providers.size(),
                "enabled", providers.stream().filter(ProviderDescriptor::isEnabled).count()));
    }

    /**
     * 列出支持的全部环境类型。
     *
     * @return 环境类型列表
     */
    @GetMapping("/environments")
    public ResponseEntity<Map<String, Object>> listEnvironments() {
        List<Map<String, String>> envs = java.util.Arrays.stream(EnvironmentType.values())
                .map(env -> Map.of(
                        "name", env.name(),
                        "providerKind", env.getProviderKind(),
                        "subType", env.getSubType(),
                        "description", env.getDescription()))
                .toList();
        return ResponseEntity.ok(Map.of("environments", envs, "total", envs.size()));
    }

    /**
     * 列出各环境的默认配置 Profile。
     *
     * @return 环境 → 默认配置
     */
    @GetMapping("/profiles")
    public ResponseEntity<Map<EnvironmentType, EnvironmentProfile.ProfileDefaults>> listProfiles() {
        return ResponseEntity.ok(providerRegistryService.getEnvironmentProfile().allProfiles());
    }

    /**
     * 全局异常处理 - 非法参数（如未知环境类型、未注册 Provider）。
     *
     * @param e 异常
     * @return 400 Bad Request
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }

    /* ================================================================ */
    /* 集群子资源端点（对齐前端 infra.ts：网络/存储/HPA）                */
    /* ================================================================ */

    /**
     * 获取集群网络配置。
     *
     * <p>对齐前端 {@code getNetworkConfig}。聚合 K8s API 中的
     * NetworkPolicy / Service / Ingress 列表，并附带 CNI 基本信息。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 200 + 网络配置（含 policies / services / ingresses 三列表）
     */
    @GetMapping("/{environment}/{clusterId}/network")
    public ResponseEntity<Map<String, Object>> getNetworkConfig(@PathVariable String environment,
                                                                @PathVariable String clusterId) {
        log.info("GET /api/v1/clusters/{}/{}/network", environment, clusterId);
        Map<String, Object> cfg = new LinkedHashMap<>();
        // CNI 基本信息（k3s 默认 flannel，可由环境变量覆盖）
        cfg.put("podCidr", "10.244.0.0/16");
        cfg.put("serviceCidr", "10.96.0.0/12");
        cfg.put("cni", "flannel");
        cfg.put("mtu", 1450);

        // 真实资源列表
        List<Map<String, Object>> policies = k8sClientService.listNetworkPolicies().stream()
                .map(this::networkPolicyToView).toList();
        List<Map<String, Object>> services = k8sClientService.listServices().stream()
                .map(this::serviceToView).toList();
        List<Map<String, Object>> ingresses = k8sClientService.listIngresses().stream()
                .map(this::ingressToView).toList();
        cfg.put("policies", policies);
        cfg.put("services", services);
        cfg.put("ingresses", ingresses);
        cfg.put("policyCount", policies.size());
        cfg.put("serviceCount", services.size());
        cfg.put("ingressCount", ingresses.size());
        return ResponseEntity.ok(cfg);
    }

    /**
     * 获取集群存储配置。
     *
     * <p>对齐前端 {@code getStorageClasses}。聚合 K8s API 中的
     * StorageClass / PV / PVC 列表。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 200 + StorageClass / PV / PVC 列表
     */
    @GetMapping("/{environment}/{clusterId}/storage")
    public ResponseEntity<List<Map<String, Object>>> getStorage(@PathVariable String environment,
                                                                @PathVariable String clusterId) {
        log.info("GET /api/v1/clusters/{}/{}/storage", environment, clusterId);
        // 主响应：StorageClass 列表（对齐前端 getStorageClasses 契约）
        List<Map<String, Object>> storageClasses = k8sClientService.listStorageClasses().stream()
                .map(this::storageClassToView).toList();
        return ResponseEntity.ok(storageClasses);
    }

    /**
     * 获取集群 StorageClass 列表（对齐前端 {@code getStorageClasses} 路径别名）。
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 200 + StorageClass 列表
     */
    @GetMapping("/{environment}/{clusterId}/storage/classes")
    public ResponseEntity<List<Map<String, Object>>> getStorageClasses(@PathVariable String environment,
                                                                       @PathVariable String clusterId) {
        return getStorage(environment, clusterId);
    }

    /**
     * 获取集群 PVC 列表。
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 200 + PVC 列表
     */
    @GetMapping("/{environment}/{clusterId}/storage/pvcs")
    public ResponseEntity<List<Map<String, Object>>> getPvcs(@PathVariable String environment,
                                                             @PathVariable String clusterId) {
        log.info("GET /api/v1/clusters/{}/{}/storage/pvcs", environment, clusterId);
        List<Map<String, Object>> pvcs = k8sClientService.listPersistentVolumeClaims().stream()
                .map(this::pvcToView).toList();
        return ResponseEntity.ok(pvcs);
    }

    /**
     * 获取集群 PV 列表。
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 200 + PV 列表
     */
    @GetMapping("/{environment}/{clusterId}/storage/pvs")
    public ResponseEntity<List<Map<String, Object>>> getPvs(@PathVariable String environment,
                                                            @PathVariable String clusterId) {
        log.info("GET /api/v1/clusters/{}/{}/storage/pvs", environment, clusterId);
        List<Map<String, Object>> pvs = k8sClientService.listPersistentVolumes().stream()
                .map(this::pvToView).toList();
        return ResponseEntity.ok(pvs);
    }

    /**
     * 获取集群 HPA 配置。
     *
     * <p>对齐前端 {@code getHpas}。从 K8s API 查询 autoscaling/v2 HPA 列表。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 200 + HPA 策略列表
     */
    @GetMapping("/{environment}/{clusterId}/hpa")
    public ResponseEntity<List<Map<String, Object>>> getHpa(@PathVariable String environment,
                                                            @PathVariable String clusterId) {
        log.info("GET /api/v1/clusters/{}/{}/hpa", environment, clusterId);
        List<Map<String, Object>> hpas = k8sClientService.listHpas().stream()
                .map(this::hpaToView).toList();
        return ResponseEntity.ok(hpas);
    }

    /* ============================ K8s 资源 → 视图 ============================ */

    /** NetworkPolicy → 前端视图。 */
    private Map<String, Object> networkPolicyToView(NetworkPolicy np) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", np.getMetadata().getName());
        m.put("namespace", np.getMetadata().getNamespace());
        List<String> types = np.getSpec() != null && np.getSpec().getPolicyTypes() != null
                ? np.getSpec().getPolicyTypes() : List.of();
        m.put("type", types.isEmpty() ? "ingress" : types.get(0).toLowerCase());
        m.put("ports", List.of());
        m.put("selector", np.getSpec() != null && np.getSpec().getPodSelector() != null
                ? String.valueOf(np.getSpec().getPodSelector().getMatchLabels()) : "");
        return m;
    }

    /** Service → 前端视图。 */
    private Map<String, Object> serviceToView(io.fabric8.kubernetes.api.model.Service svc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", svc.getMetadata().getName());
        m.put("namespace", svc.getMetadata().getNamespace());
        m.put("type", svc.getSpec() != null ? svc.getSpec().getType() : "");
        m.put("clusterIP", svc.getSpec() != null ? svc.getSpec().getClusterIP() : "");
        m.put("ports", svc.getSpec() != null && svc.getSpec().getPorts() != null
                ? svc.getSpec().getPorts().size() : 0);
        return m;
    }

    /** Ingress → 前端视图。 */
    private Map<String, Object> ingressToView(Ingress ing) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", ing.getMetadata().getName());
        m.put("namespace", ing.getMetadata().getNamespace());
        m.put("className", ing.getSpec() != null ? ing.getSpec().getIngressClassName() : "");
        m.put("hosts", ing.getSpec() != null && ing.getSpec().getRules() != null
                ? ing.getSpec().getRules().size() : 0);
        return m;
    }

    /** StorageClass → 前端视图。 */
    private Map<String, Object> storageClassToView(StorageClass sc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", sc.getMetadata().getName());
        m.put("provisioner", sc.getProvisioner());
        m.put("reclaimPolicy", sc.getReclaimPolicy());
        m.put("volumeBindingMode", sc.getVolumeBindingMode());
        m.put("default", sc.getMetadata().getAnnotations() != null
                && sc.getMetadata().getAnnotations()
                        .containsKey("storageclass.kubernetes.io/is-default-class"));
        return m;
    }

    /** PVC → 前端视图。 */
    private Map<String, Object> pvcToView(PersistentVolumeClaim pvc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", pvc.getMetadata().getName());
        m.put("namespace", pvc.getMetadata().getNamespace());
        m.put("storageClassName", pvc.getSpec() != null ? pvc.getSpec().getStorageClassName() : "");
        m.put("capacity", pvc.getStatus() != null && pvc.getStatus().getCapacity() != null
                ? String.valueOf(pvc.getStatus().getCapacity().get("storage")) : "");
        m.put("status", pvc.getStatus() != null ? pvc.getStatus().getPhase() : "");
        m.put("volumeName", pvc.getSpec() != null ? pvc.getSpec().getVolumeName() : "");
        m.put("createdAt", pvc.getMetadata() != null && pvc.getMetadata().getCreationTimestamp() != null
                ? pvc.getMetadata().getCreationTimestamp() : "");
        return m;
    }

    /** PV → 前端视图。 */
    private Map<String, Object> pvToView(PersistentVolume pv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", pv.getMetadata().getName());
        m.put("capacity", pv.getSpec() != null && pv.getSpec().getCapacity() != null
                ? String.valueOf(pv.getSpec().getCapacity().get("storage")) : "");
        m.put("status", pv.getStatus() != null ? pv.getStatus().getPhase() : "");
        m.put("reclaimPolicy", pv.getSpec() != null ? pv.getSpec().getPersistentVolumeReclaimPolicy() : "");
        m.put("storageClassName", pv.getSpec() != null ? pv.getSpec().getStorageClassName() : "");
        return m;
    }

    /** HPA → 前端视图。 */
    private Map<String, Object> hpaToView(HorizontalPodAutoscaler hpa) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", hpa.getMetadata().getName());
        m.put("namespace", hpa.getMetadata().getNamespace());
        m.put("targetDeployment", hpa.getSpec() != null && hpa.getSpec().getScaleTargetRef() != null
                ? hpa.getSpec().getScaleTargetRef().getName() : "");
        m.put("minReplicas", hpa.getSpec() != null && hpa.getSpec().getMinReplicas() != null
                ? hpa.getSpec().getMinReplicas() : 1);
        m.put("maxReplicas", hpa.getSpec() != null ? hpa.getSpec().getMaxReplicas() : 0);
        m.put("currentReplicas", hpa.getStatus() != null && hpa.getStatus().getCurrentReplicas() != null
                ? hpa.getStatus().getCurrentReplicas() : 0);
        m.put("status", hpa.getStatus() != null && hpa.getStatus().getCurrentMetrics() != null
                ? "active" : "paused");
        return m;
    }
}