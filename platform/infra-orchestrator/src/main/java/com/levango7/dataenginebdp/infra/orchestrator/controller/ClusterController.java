package com.levango7.dataenginebdp.infra.orchestrator.controller;

import com.levango7.dataenginebdp.infra.orchestrator.model.ClusterCreateRequest;
import com.levango7.dataenginebdp.infra.orchestrator.model.ClusterInfo;
import com.levango7.dataenginebdp.infra.orchestrator.model.ClusterScaleRequest;
import com.levango7.dataenginebdp.infra.orchestrator.model.EnvironmentType;
import com.levango7.dataenginebdp.infra.orchestrator.model.HpaCreateRequest;
import com.levango7.dataenginebdp.infra.orchestrator.model.NetworkPolicyCreateRequest;
import com.levango7.dataenginebdp.infra.orchestrator.model.PvcCreateRequest;
import com.levango7.dataenginebdp.infra.orchestrator.model.SupplyResult;
import com.levango7.dataenginebdp.infra.orchestrator.registry.EnvironmentProfile;
import com.levango7.dataenginebdp.infra.orchestrator.registry.ProviderDescriptor;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.infra.orchestrator.service.K8sClientService;
import com.levango7.dataenginebdp.infra.orchestrator.service.ProviderRegistryService;
import com.levango7.dataenginebdp.infra.orchestrator.service.SupplyOrchestrator;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpecBuilder;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPort;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPortBuilder;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 *
 * <p>集群子资源端点（对齐前端 infra.ts，均带 R13 租户守卫三段式）：</p>
 * <ul>
 *   <li>{@code GET    .../{environment}/{clusterId}/nodes}               - 节点列表</li>
 *   <li>{@code GET    .../{environment}/{clusterId}/components}           - 工作负载组件（Deployment/StatefulSet/DaemonSet）</li>
 *   <li>{@code GET    .../{environment}/{clusterId}/network/cnis}         - CNI 信息</li>
 *   <li>{@code GET    .../{environment}/{clusterId}/scale/events}         - 集群事件</li>
 *   <li>{@code GET    .../{environment}/{clusterId}/scale/summary}        - 扩缩容汇总</li>
 *   <li>{@code GET    .../{environment}/{clusterId}/network/policies}     - NetworkPolicy 列表</li>
 *   <li>{@code POST   .../{environment}/{clusterId}/network/policies}     - 创建 NetworkPolicy</li>
 *   <li>{@code DELETE .../{environment}/{clusterId}/network/policies/{name}} - 删除 NetworkPolicy</li>
 *   <li>{@code GET    .../{environment}/{clusterId}/storage/usage}        - 存储用量（spec/status 口径）</li>
 *   <li>{@code POST   .../{environment}/{clusterId}/hpa}                  - 创建 HPA</li>
 *   <li>{@code PUT    .../{environment}/{clusterId}/hpa/{name}}           - 更新 HPA</li>
 *   <li>{@code DELETE .../{environment}/{clusterId}/hpa/{name}}           - 删除 HPA</li>
 *   <li>{@code POST   .../{environment}/{clusterId}/storage/pvcs}         - 创建 PVC</li>
 *   <li>{@code DELETE .../{environment}/{clusterId}/storage/pvcs/{name}}  - 删除 PVC</li>
 *   <li>{@code POST   .../{environment}/{clusterId}/storage/pvcs/{name}/snapshot} - 创建 VolumeSnapshot（CRD 缺失返回 501）</li>
 * </ul>
 */
@RestController
@Tag(name = "基础设施编排-集群供应", description = "跨环境集群创建/销毁/扩缩容")
@RequestMapping("/api/v1/clusters")
@PreAuthorize("isAuthenticated()")
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
    @Operation(summary = "创建集群 - 统一入口")
    @PostMapping
    public ResponseEntity<SupplyResult> createCluster(@Valid @RequestBody ClusterCreateRequest request) {
        // R12 安全修复：fail-closed，tenantId 缺失直接拒绝，不信任请求体
        String tenantId = requireTenant();
        request.setTenantId(tenantId);
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
    @Operation(summary = "销毁集群")
    @DeleteMapping("/{environment}/{clusterId}")
    public ResponseEntity<ClusterInfo> destroyCluster(@PathVariable String environment,
                                                      @PathVariable String clusterId) {
        // R12 安全修复：fail-closed + 租户校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        log.info("DELETE /api/v1/clusters/{}/{} - destroyCluster tenant={}", env, clusterId, tenantId);
        ClusterInfo info = orchestrator.destroyCluster(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        return ResponseEntity.ok(info);
    }

    /**
     * 查询集群信息。
     *
     * @param environment 环境类型字符串
     * @param clusterId   集群 ID
     * @return 集群信息
     */
    @Operation(summary = "查询集群信息")
    @GetMapping("/{environment}/{clusterId}")
    public ResponseEntity<ClusterInfo> getClusterInfo(@PathVariable String environment,
                                                      @PathVariable String clusterId) {
        // R12 安全修复：fail-closed + 租户校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        log.info("GET /api/v1/clusters/{}/{} - getClusterInfo tenant={}", env, clusterId, tenantId);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
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
    @Operation(summary = "扩缩容集群")
    @PostMapping("/{environment}/{clusterId}/scale")
    public ResponseEntity<ClusterInfo> scaleCluster(@PathVariable String environment,
                                                    @PathVariable String clusterId,
                                                    @Valid @RequestBody ClusterScaleRequest scaleReq) {
        // R12 安全修复：fail-closed + 租户校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        log.info("POST /api/v1/clusters/{}/{}/scale - scaleCluster target={} tenant={}",
                env, clusterId, scaleReq.getTargetNodeCount(), tenantId);
        ClusterInfo info = orchestrator.scaleCluster(env, clusterId, scaleReq);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        return ResponseEntity.ok(info);
    }

    /**
     * 列出指定环境的全部集群。
     *
     * @param environment 环境类型字符串
     * @return 集群列表
     */
    @Operation(summary = "列出指定环境的全部集群")
    @GetMapping("/{environment}")
    public ResponseEntity<List<ClusterInfo>> listClustersByEnvironment(@PathVariable String environment) {
        // R13 安全修复：fail-closed 租户校验 + 按租户过滤结果
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        log.info("GET /api/v1/clusters/{} - listClusters tenant={}", env, tenantId);
        List<ClusterInfo> clusters = orchestrator.listClusters(env);
        // 按租户过滤，仅返回当前租户的集群
        List<ClusterInfo> filtered = clusters.stream()
                .filter(c -> tenantId.equals(c.getTenantId()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    /**
     * 列出所有集群（跨环境聚合）。
     *
     * @return 集群列表
     */
    @Operation(summary = "列出所有集群（跨环境聚合）")
    @GetMapping
    public ResponseEntity<List<ClusterInfo>> listAllClusters() {
        // R13 安全修复：fail-closed 租户校验 + 按租户过滤结果
        String tenantId = requireTenant();
        log.info("GET /api/v1/clusters - listAllClusters tenant={}", tenantId);
        List<ClusterInfo> clusters = orchestrator.listAllClusters();
        // 按租户过滤，仅返回当前租户的集群
        List<ClusterInfo> filtered = clusters.stream()
                .filter(c -> tenantId.equals(c.getTenantId()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    /**
     * 列出已注册 Provider。
     *
     * @return Provider 描述符列表
     */
    @Operation(summary = "列出已注册 Provider")
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
    @Operation(summary = "列出支持的全部环境类型")
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
    @Operation(summary = "查询环境默认配置Profile列表")
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

    /**
     * 从 TenantContext 获取租户 ID，缺失则 fail-closed（R12 安全修复）。
     *
     * @return 当前请求的租户 ID
     * @throws IllegalStateException 若 TenantContext 未设置租户 ID
     */
    private static String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /**
     * 校验集群归属当前租户（R12/R13 安全修复）。
     *
     * <p>R13 安全修复：改为 fail-closed — 若集群信息的 tenantId 为空（下游 Provider 未回填），
     * 也拒绝访问，避免无主集群被任意租户访问。若非空且与当前租户不匹配则抛 404 等价异常
     * （不暴露存在性）。</p>
     *
     * @param info      集群信息
     * @param tenantId  当前租户 ID
     * @param clusterId 集群 ID（用于日志）
     * @throws IllegalArgumentException 集群不存在或不属于当前租户
     */
    private static void requireTenantOwnedCluster(ClusterInfo info, String tenantId, String clusterId) {
        if (info == null) {
            throw new IllegalArgumentException("cluster not found: " + clusterId);
        }
        String clusterTenant = info.getTenantId();
        // R13 安全修复：fail-closed — 集群租户为空时拒绝访问，不放行
        if (clusterTenant == null || clusterTenant.isBlank()) {
            throw new IllegalArgumentException("cluster not found: " + clusterId);
        }
        if (!tenantId.equals(clusterTenant)) {
            throw new IllegalArgumentException("cluster not found: " + clusterId);
        }
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
    @Operation(summary = "获取集群网络配置")
    @GetMapping("/{environment}/{clusterId}/network")
    public ResponseEntity<Map<String, Object>> getNetworkConfig(@PathVariable String environment,
                                                                @PathVariable String clusterId) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("GET /api/v1/clusters/{}/{}/network tenant={}", environment, clusterId, tenantId);
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
    @Operation(summary = "获取集群存储配置")
    @GetMapping("/{environment}/{clusterId}/storage")
    public ResponseEntity<List<Map<String, Object>>> getStorage(@PathVariable String environment,
                                                                @PathVariable String clusterId) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("GET /api/v1/clusters/{}/{}/storage tenant={}", environment, clusterId, tenantId);
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
    @Operation(summary = "查询详情集群")
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
    @Operation(summary = "获取集群 PVC 列表")
    @GetMapping("/{environment}/{clusterId}/storage/pvcs")
    public ResponseEntity<List<Map<String, Object>>> getPvcs(@PathVariable String environment,
                                                              @PathVariable String clusterId) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("GET /api/v1/clusters/{}/{}/storage/pvcs tenant={}", environment, clusterId, tenantId);
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
    @Operation(summary = "获取集群 PV 列表")
    @GetMapping("/{environment}/{clusterId}/storage/pvs")
    public ResponseEntity<List<Map<String, Object>>> getPvs(@PathVariable String environment,
                                                            @PathVariable String clusterId) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("GET /api/v1/clusters/{}/{}/storage/pvs tenant={}", environment, clusterId, tenantId);
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
    @Operation(summary = "获取集群 HPA 配置")
    @GetMapping("/{environment}/{clusterId}/hpa")
    public ResponseEntity<List<Map<String, Object>>> getHpa(@PathVariable String environment,
                                                            @PathVariable String clusterId) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("GET /api/v1/clusters/{}/{}/hpa tenant={}", environment, clusterId, tenantId);
        List<Map<String, Object>> hpas = k8sClientService.listHpas().stream()
                .map(this::hpaToView).toList();
        return ResponseEntity.ok(hpas);
    }

    /* ================================================================ */
    /* 集群子资源端点 P0-1：节点 / 组件 / CNI / 扩缩容 / 网络策略 / HPA / PVC */
    /* 全部端点带 R13 守卫三段式；响应形状对齐 frontend/src/api/infra.ts     */
    /* ================================================================ */

    /**
     * 列出集群节点（GET .../nodes）。
     *
     * <p>对齐前端 {@code getClusterNodes}（{@code ClusterNode[]}）。CPU/内存取
     * {@code status.capacity} 的真实容量；已用取 metrics.k8s.io 的 NodeMetrics——
     * 集群未部署 metrics-server 时指标不可得，{@code cpuUsed}/{@code memUsed} 置 null
     * 并返回 {@code metricsAvailable=false}，<b>不编造用量数字</b>。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 200 + 节点列表
     */
    @Operation(summary = "列出集群节点")
    @GetMapping("/{environment}/{clusterId}/nodes")
    public ResponseEntity<List<Map<String, Object>>> getNodes(@PathVariable String environment,
                                                              @PathVariable String clusterId) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("GET /api/v1/clusters/{}/{}/nodes tenant={}", environment, clusterId, tenantId);

        List<Node> nodes = k8sClientService.listNodes();
        List<NodeMetrics> nodeMetrics = k8sClientService.listNodeMetrics();
        boolean metricsAvailable = nodeMetrics != null;
        Map<String, NodeMetrics> metricByName = new LinkedHashMap<>();
        if (metricsAvailable) {
            for (NodeMetrics m : nodeMetrics) {
                if (m.getMetadata() != null && m.getMetadata().getName() != null) {
                    metricByName.put(m.getMetadata().getName(), m);
                }
            }
        }
        // Pod 分布（每节点 Pod 数）：按 spec.nodeName 聚合，K8s 真实值
        Map<String, Long> podCountByNode = new LinkedHashMap<>();
        for (io.fabric8.kubernetes.api.model.Pod pod : k8sClientService.listPods()) {
            if (pod.getSpec() == null || pod.getSpec().getNodeName() == null) {
                continue;
            }
            podCountByNode.merge(pod.getSpec().getNodeName(), 1L, Long::sum);
        }
        List<Map<String, Object>> views = nodes.stream()
                .map(n -> nodeToView(n, metricByName.get(n.getMetadata().getName()),
                        podCountByNode.getOrDefault(n.getMetadata().getName(), 0L), metricsAvailable))
                .toList();
        return ResponseEntity.ok(views);
    }

    /**
     * 列出集群工作负载组件（Deployments + StatefulSets + DaemonSets）。
     *
     * <p>对齐前端 {@code getClusterComponents}（{@code ClusterComponent[]}）：
     * {@code name}/{@code status}/{@code meta} 为契约字段，另附
     * {@code type/namespace/readyReplicas/replicas/images} 明细。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 200 + 组件列表
     */
    @Operation(summary = "列出集群工作负载组件")
    @GetMapping("/{environment}/{clusterId}/components")
    public ResponseEntity<List<Map<String, Object>>> getComponents(@PathVariable String environment,
                                                                   @PathVariable String clusterId) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("GET /api/v1/clusters/{}/{}/components tenant={}", environment, clusterId, tenantId);

        List<Map<String, Object>> components = new ArrayList<>();
        for (Deployment d : k8sClientService.listDeployments()) {
            components.add(workloadToView(d.getMetadata(), "Deployment",
                    replicas(d.getSpec() != null ? d.getSpec().getReplicas() : null),
                    ready(d.getStatus() != null ? d.getStatus().getReadyReplicas() : null),
                    firstImage(d.getSpec() != null ? d.getSpec().getTemplate() : null)));
        }
        for (StatefulSet s : k8sClientService.listStatefulSets()) {
            components.add(workloadToView(s.getMetadata(), "StatefulSet",
                    replicas(s.getSpec() != null ? s.getSpec().getReplicas() : null),
                    ready(s.getStatus() != null ? s.getStatus().getReadyReplicas() : null),
                    firstImage(s.getSpec() != null ? s.getSpec().getTemplate() : null)));
        }
        for (DaemonSet ds : k8sClientService.listDaemonSets()) {
            components.add(workloadToView(ds.getMetadata(), "DaemonSet",
                    orZero(ds.getStatus() != null ? ds.getStatus().getDesiredNumberScheduled() : null),
                    orZero(ds.getStatus() != null ? ds.getStatus().getNumberReady() : null),
                    firstImage(ds.getSpec() != null ? ds.getSpec().getTemplate() : null)));
        }
        return ResponseEntity.ok(components);
    }

    /**
     * 列出集群 CNI 插件信息（GET .../network/cnis）。
     *
     * <p>CNI 名称取自对 {@code kube-system} 命名空间 DaemonSet 的真实探测
     * （kube-flannel / calico-node / cilium / kube-ovn-cni 名称匹配）；未探测到时沿用
     * {@code getNetworkConfig} 既有常量（k3s 默认 flannel：podCidr 10.244.0.0/16、
     * serviceCidr 10.96.0.0/12、mtu 1450）并在 {@code source} 字段标注来源。
     * 各节点 {@code spec.podCIDR} 的真实值通过 {@code nodePodCidrs} 返回。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 200 + CNI 信息
     */
    @Operation(summary = "列出集群 CNI 插件信息")
    @GetMapping("/{environment}/{clusterId}/network/cnis")
    public ResponseEntity<Map<String, Object>> getCniPlugins(@PathVariable String environment,
                                                             @PathVariable String clusterId) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("GET /api/v1/clusters/{}/{}/network/cnis tenant={}", environment, clusterId, tenantId);

        String detected = detectCniPlugin(k8sClientService.listDaemonSets());
        boolean detectedFromCluster = detected != null;
        String cni = detectedFromCluster ? detected : "flannel";
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("cni", cni);
        cfg.put("detected", detectedFromCluster);
        // K8s 没有集群级 Pod/Service CIDR 查询端点（由 controller-manager --cluster-cidr 配置），
        // 无真实来源时沿用 getNetworkConfig 既有常量，故以 source 字段如实标注
        cfg.put("podCidr", "10.244.0.0/16");
        cfg.put("serviceCidr", "10.96.0.0/12");
        cfg.put("mtu", 1450);
        // 真实来源：各节点 spec.podCIDR（flannel 为每节点 /24）
        Set<String> nodePodCidrs = new java.util.LinkedHashSet<>();
        for (Node node : k8sClientService.listNodes()) {
            if (node.getSpec() != null && node.getSpec().getPodCIDR() != null
                    && !node.getSpec().getPodCIDR().isBlank()) {
                nodePodCidrs.add(node.getSpec().getPodCIDR());
            }
        }
        cfg.put("nodePodCidrs", List.copyOf(nodePodCidrs));
        cfg.put("source", Map.of(
                "cni", detectedFromCluster
                        ? "kube-system DaemonSet 名称探测（集群真实配置）"
                        : "default-constant（未探测到 CNI DaemonSet，沿用 k3s 默认 flannel）",
                "podCidr", "default-constant（K8s API 无集群级 CIDR 查询端点）",
                "serviceCidr", "default-constant（K8s API 无集群级 CIDR 查询端点）",
                "mtu", "default-constant（沿用 getNetworkConfig 既有值）",
                "nodePodCidrs", "cluster-nodes-spec-podCIDR（真实值）"));
        // 兼容 infra.ts CniPlugin[] 数组形状：cnis 承载插件名数组
        cfg.put("cnis", List.of(cni));
        return ResponseEntity.ok(cfg);
    }

    /**
     * 列出集群事件（GET .../scale/events）。
     *
     * <p>返回 K8s Event（events.k8s.io/v1）原始字段（类型/原因/对象/消息/时间），
     * 按 eventTime 倒序取 N 条。前端 {@code ScaleEvent} 契约中的 trigger /
     * fromReplicas / toReplicas / durationMs 在 K8s Event 中无对应来源，
     * <b>不编造</b>（见类级注释降级说明）。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 200 + 事件列表（时间倒序）
     */
    @Operation(summary = "列出集群事件")
    @GetMapping("/{environment}/{clusterId}/scale/events")
    public ResponseEntity<List<Map<String, Object>>> getScaleEvents(@PathVariable String environment,
                                                                    @PathVariable String clusterId) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("GET /api/v1/clusters/{}/{}/scale/events tenant={}", environment, clusterId, tenantId);
        List<Map<String, Object>> events = k8sClientService.listEvents().stream()
                .map(this::eventToView).toList();
        return ResponseEntity.ok(events);
    }

    /**
     * 集群扩缩容汇总（GET .../scale/summary）。
     *
     * <p>口径：节点数 = Node 数；总副本/就绪副本 = Deployment + StatefulSet 的
     * spec.replicas / status.readyReplicas 聚合（DaemonSet 按节点调度，不计入副本）；
     * Pending Pod 数 = status.phase 为 Pending 的 Pod 计数。全部取 K8s 真实值。
     * 前端 {@code ScalingPolicySummary} 的 scaleUpToday/scaleDownToday/avgDurationMs
     * 无 K8s 来源（HPA 事件不含旧副本数与耗时），未编造。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 200 + 汇总
     */
    @Operation(summary = "集群扩缩容汇总")
    @GetMapping("/{environment}/{clusterId}/scale/summary")
    public ResponseEntity<Map<String, Object>> getScaleSummary(@PathVariable String environment,
                                                               @PathVariable String clusterId) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("GET /api/v1/clusters/{}/{}/scale/summary tenant={}", environment, clusterId, tenantId);

        int totalReplicas = 0;
        int readyReplicas = 0;
        for (Deployment d : k8sClientService.listDeployments()) {
            totalReplicas += replicas(d.getSpec() != null ? d.getSpec().getReplicas() : null);
            readyReplicas += ready(d.getStatus() != null ? d.getStatus().getReadyReplicas() : null);
        }
        for (StatefulSet s : k8sClientService.listStatefulSets()) {
            totalReplicas += replicas(s.getSpec() != null ? s.getSpec().getReplicas() : null);
            readyReplicas += ready(s.getStatus() != null ? s.getStatus().getReadyReplicas() : null);
        }
        List<io.fabric8.kubernetes.api.model.Pod> pods = k8sClientService.listPods();
        long pendingPods = pods.stream()
                .filter(p -> "Pending".equals(p.getStatus() != null ? p.getStatus().getPhase() : null))
                .count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("nodeCount", k8sClientService.listNodes().size());
        summary.put("totalReplicas", totalReplicas);
        summary.put("readyReplicas", readyReplicas);
        summary.put("pendingPods", pendingPods);
        summary.put("podCount", pods.size());
        summary.put("source", "deployment+statefulset spec/status 聚合（DaemonSet 不计入副本）；"
                + "pendingPods=status.phase=Pending 计数；nodeCount=Node 数");
        return ResponseEntity.ok(summary);
    }

    /**
     * 列出集群 NetworkPolicy（GET .../network/policies）。
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 200 + NetworkPolicy 列表（对齐 infra.ts NetworkPolicy[]）
     */
    @Operation(summary = "列出集群 NetworkPolicy")
    @GetMapping("/{environment}/{clusterId}/network/policies")
    public ResponseEntity<List<Map<String, Object>>> getNetworkPolicies(@PathVariable String environment,
                                                                        @PathVariable String clusterId) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("GET /api/v1/clusters/{}/{}/network/policies tenant={}", environment, clusterId, tenantId);
        List<Map<String, Object>> policies = k8sClientService.listNetworkPolicies().stream()
                .map(this::networkPolicyToView).toList();
        return ResponseEntity.ok(policies);
    }

    /**
     * 创建 NetworkPolicy（POST .../network/policies）。
     *
     * <p>入参契约 = 前端 InfraNet.vue 创建表单提交的 NetworkPolicy JSON：
     * {@code name/namespace/type(ingress|egress|both)/ports[]/selector}。
     * {@code ports} 按 TCP 端口写入 ingress/egress 规则；空数组 = 不限端口。
     * {@code selector} 解析为 {@code podSelector.matchLabels}（格式 {@code k=v,k=v}）。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @param req         创建请求
     * @return 201 + 创建后的策略视图
     */
    @Operation(summary = "创建 NetworkPolicy")
    @PostMapping("/{environment}/{clusterId}/network/policies")
    public ResponseEntity<Map<String, Object>> createNetworkPolicy(@PathVariable String environment,
                                                                   @PathVariable String clusterId,
                                                                   @Valid @RequestBody NetworkPolicyCreateRequest req) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("POST /api/v1/clusters/{}/{}/network/policies name={} type={} tenant={}",
                environment, clusterId, req.getName(), req.getType(), tenantId);

        String type = req.getType() == null ? "" : req.getType().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("ingress", "egress", "both").contains(type)) {
            throw new IllegalArgumentException("invalid network policy type: " + req.getType()
                    + ", supported: ingress|egress|both");
        }
        List<NetworkPolicyPort> ports = (req.getPorts() == null ? List.<Integer>of() : req.getPorts()).stream()
                .map(p -> new NetworkPolicyPortBuilder()
                        .withProtocol("TCP")
                        .withPort(new io.fabric8.kubernetes.api.model.IntOrString(p))
                        .build())
                .toList();
        NetworkPolicyBuilder builder = new NetworkPolicyBuilder()
                .withNewMetadata()
                    .withName(req.getName())
                    .withNamespace(req.getNamespace())
                .endMetadata()
                .withNewSpec()
                    .withPodSelector(parseSelector(req.getSelector()))
                .endSpec();
        if ("ingress".equals(type) || "both".equals(type)) {
            builder = builder.editSpec()
                    .addNewIngress()
                        .withPorts(ports)
                    .endIngress()
                    .endSpec();
        }
        if ("egress".equals(type) || "both".equals(type)) {
            builder = builder.editSpec()
                    .addNewEgress()
                        .withPorts(ports)
                    .endEgress()
                    .endSpec();
        }
        // both 显式声明 policyTypes，避免 K8s 只推断 Ingress 的默认行为
        if ("both".equals(type)) {
            builder = builder.editSpec()
                    .withPolicyTypes("Ingress", "Egress")
                    .endSpec();
        }
        NetworkPolicy created = k8sClientService.createNetworkPolicy(builder.build());
        return ResponseEntity.status(HttpStatus.CREATED).body(networkPolicyToView(created));
    }

    /**
     * 删除 NetworkPolicy（DELETE .../network/policies/{name}）。
     *
     * <p>前端删除调用只带 name（不带 namespace），故先按 name 解析命名空间再删除；
     * 找不到（或删除零条）时按本类既有 handle 风格返回 400 + error。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @param name        策略名
     * @return 204 No Content
     */
    @Operation(summary = "删除 NetworkPolicy")
    @DeleteMapping("/{environment}/{clusterId}/network/policies/{name}")
    public ResponseEntity<Void> deleteNetworkPolicy(@PathVariable String environment,
                                                    @PathVariable String clusterId,
                                                    @PathVariable String name) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("DELETE /api/v1/clusters/{}/{}/network/policies/{} tenant={}",
                environment, clusterId, name, tenantId);
        String namespace = k8sClientService.listNetworkPolicies().stream()
                .filter(np -> name.equals(np.getMetadata().getName()))
                .map(np -> np.getMetadata().getNamespace())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "networkpolicy not found: " + name));
        if (!k8sClientService.deleteNetworkPolicy(namespace, name)) {
            throw new IllegalArgumentException("networkpolicy not found: " + name);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 创建 HPA（POST .../hpa）。
     *
     * <p>构造 autoscaling/v2 HorizontalPodAutoscaler：scaleTargetRef 固定
     * {@code apps/v1 Deployment}（前端只支持 Deployment 目标），CPU 阈值映射
     * ResourceMetricSource/Utilization；memoryThreshold>0 时追加内存指标。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @param req         创建请求
     * @return 201 + 创建后的 HPA 视图
     */
    @Operation(summary = "创建 HPA")
    @PostMapping("/{environment}/{clusterId}/hpa")
    public ResponseEntity<Map<String, Object>> createHpa(@PathVariable String environment,
                                                         @PathVariable String clusterId,
                                                         @Valid @RequestBody HpaCreateRequest req) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("POST /api/v1/clusters/{}/{}/hpa name={} target={} tenant={}",
                environment, clusterId, req.getName(), req.getTargetDeployment(), tenantId);
        HorizontalPodAutoscaler hpa = buildHpa(req);
        HorizontalPodAutoscaler created = k8sClientService.createHpa(hpa);
        return ResponseEntity.status(HttpStatus.CREATED).body(hpaToView(created));
    }

    /**
     * 更新 HPA（PUT .../hpa/{name}）。
     *
     * <p>整对象 replace（含 min/max/CPU 阈值）。目标不存在时 K8s 返回 404，
     * fabric8 抛 KubernetesClientException，此处转译为本类既有风格的
     * IllegalArgumentException（400 + error），不暴露堆栈。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @param name        HPA 名（路径优先于请求体 name）
     * @param req         更新请求
     * @return 200 + 更新后的 HPA 视图
     */
    @Operation(summary = "更新 HPA")
    @PutMapping("/{environment}/{clusterId}/hpa/{name}")
    public ResponseEntity<Map<String, Object>> updateHpa(@PathVariable String environment,
                                                         @PathVariable String clusterId,
                                                         @PathVariable String name,
                                                         @Valid @RequestBody HpaCreateRequest req) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("PUT /api/v1/clusters/{}/{}/hpa/{} tenant={}", environment, clusterId, name, tenantId);
        req.setName(name);
        HorizontalPodAutoscaler hpa = buildHpa(req);
        HorizontalPodAutoscaler updated;
        try {
            updated = k8sClientService.replaceHpa(hpa);
        } catch (KubernetesClientException e) {
            if (e.getCode() == HttpStatus.NOT_FOUND.value()) {
                throw new IllegalArgumentException("hpa not found: " + name);
            }
            throw e;
        }
        return ResponseEntity.ok(hpaToView(updated));
    }

    /**
     * 删除 HPA（DELETE .../hpa/{name}）。命名空间按 name 解析；不存在返回 400 + error。
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @param name        HPA 名
     * @return 204 No Content
     */
    @Operation(summary = "删除 HPA")
    @DeleteMapping("/{environment}/{clusterId}/hpa/{name}")
    public ResponseEntity<Void> deleteHpa(@PathVariable String environment,
                                          @PathVariable String clusterId,
                                          @PathVariable String name) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("DELETE /api/v1/clusters/{}/{}/hpa/{} tenant={}", environment, clusterId, name, tenantId);
        String namespace = k8sClientService.listHpas().stream()
                .filter(h -> name.equals(h.getMetadata().getName()))
                .map(h -> h.getMetadata().getNamespace())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("hpa not found: " + name));
        if (!k8sClientService.deleteHpa(namespace, name)) {
            throw new IllegalArgumentException("hpa not found: " + name);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 创建 PVC（POST .../storage/pvcs）。
     *
     * <p>入参契约 = 前端 InfraStore.vue 创建表单：{@code name/namespace/
     * storageClassName/capacity}。accessModes 固定 RWO（前端未暴露该字段）。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @param req         创建请求
     * @return 201 + 创建后的 PVC 视图
     */
    @Operation(summary = "创建 PVC")
    @PostMapping("/{environment}/{clusterId}/storage/pvcs")
    public ResponseEntity<Map<String, Object>> createPvc(@PathVariable String environment,
                                                         @PathVariable String clusterId,
                                                         @Valid @RequestBody PvcCreateRequest req) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("POST /api/v1/clusters/{}/{}/storage/pvcs name={} tenant={}",
                environment, clusterId, req.getName(), tenantId);
        // 容量即时解析：fabric8 Quantity 构造是惰性的（畸形串不抛错），
        // 必须在构造时用 getAmountInBytes 强制解析，否则畸形串会被 K8s 拒绝且错误不透明
        Quantity capacity;
        try {
            Quantity parsed = new Quantity(req.getCapacity());
            Quantity.getAmountInBytes(parsed);
            capacity = parsed;
        } catch (RuntimeException e) {
            // 非法容量字符串（K8s Quantity 解析失败）→ 400，与既有 handle 风格一致
            throw new IllegalArgumentException("invalid capacity: " + req.getCapacity());
        }
        io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder pvcBuilder =
                new io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder()
                        .withNewMetadata()
                            .withName(req.getName())
                            .withNamespace(req.getNamespace())
                        .endMetadata()
                        .withNewSpec()
                            .withAccessModes("ReadWriteOnce")
                            .withNewResources()
                                .withRequests(Map.of("storage", capacity))
                            .endResources()
                        .endSpec();
        if (req.getStorageClassName() != null && !req.getStorageClassName().isBlank()) {
            // editSpec() 返回的 nested 必须回写，否则 withStorageClassName 变更会丢失
            pvcBuilder = pvcBuilder.editSpec()
                    .withStorageClassName(req.getStorageClassName())
                    .endSpec();
        }
        io.fabric8.kubernetes.api.model.PersistentVolumeClaim created =
                k8sClientService.createPvc(pvcBuilder.build());
        return ResponseEntity.status(HttpStatus.CREATED).body(pvcToView(created));
    }

    /**
     * 删除 PVC（DELETE .../storage/pvcs/{name}）。命名空间按 name 解析；不存在返回 400 + error。
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @param name        PVC 名
     * @return 204 No Content
     */
    @Operation(summary = "删除 PVC")
    @DeleteMapping("/{environment}/{clusterId}/storage/pvcs/{name}")
    public ResponseEntity<Void> deletePvc(@PathVariable String environment,
                                          @PathVariable String clusterId,
                                          @PathVariable String name) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("DELETE /api/v1/clusters/{}/{}/storage/pvcs/{} tenant={}",
                environment, clusterId, name, tenantId);
        String namespace = k8sClientService.listPersistentVolumeClaims().stream()
                .filter(p -> name.equals(p.getMetadata().getName()))
                .map(p -> p.getMetadata().getNamespace())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("pvc not found: " + name));
        if (!k8sClientService.deletePvc(namespace, name)) {
            throw new IllegalArgumentException("pvc not found: " + name);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 为 PVC 创建 VolumeSnapshot（POST .../storage/pvcs/{name}/snapshot）。
     *
     * <p><b>诚实失败：</b>先探测 {@code volumesnapshots.snapshot.storage.k8s.io} CRD，
     * 不存在（集群未装 external-snapshotter/CSI 快照能力）时返回
     * <b>501 Not Implemented + error</b>，不假装成功。存在时在 PVC 同命名空间创建
     * VolumeSnapshot（快照类取 PVC 的 StorageClass，符合 CSI 快照惯例）。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @param name        源 PVC 名
     * @return 201 + SnapshotResult（snapshotName；另附 pvcName/namespace）
     */
    @Operation(summary = "创建 PVC 快照")
    @PostMapping("/{environment}/{clusterId}/storage/pvcs/{name}/snapshot")
    public ResponseEntity<Map<String, Object>> createSnapshot(@PathVariable String environment,
                                                              @PathVariable String clusterId,
                                                              @PathVariable String name) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("POST /api/v1/clusters/{}/{}/storage/pvcs/{}/snapshot tenant={}",
                environment, clusterId, name, tenantId);

        if (!k8sClientService.volumeSnapshotCrdExists()) {
            // CRD 缺失 = 集群无快照能力：501 + 明确错误（严禁造假成功）
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                    "error", "VolumeSnapshot CRD (volumesnapshots.snapshot.storage.k8s.io) "
                            + "not installed in this cluster; snapshot capability unavailable"));
        }
        PersistentVolumeClaim pvc = k8sClientService.listPersistentVolumeClaims().stream()
                .filter(p -> name.equals(p.getMetadata().getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("pvc not found: " + name));
        String namespace = pvc.getMetadata().getNamespace();
        String snapshotName = name + "-snapshot-"
                + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(Instant.now().atOffset(ZoneOffset.UTC));
        String snapshotClass = pvc.getSpec() != null ? pvc.getSpec().getStorageClassName() : null;
        k8sClientService.createVolumeSnapshot(namespace, name, snapshotName, snapshotClass);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotName", snapshotName);
        result.put("pvcName", name);
        result.put("namespace", namespace);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * 集群存储用量（GET .../storage/usage）。
     *
     * <p>口径：{@code totalCapacityBytes} = Σ PVC {@code spec.resources.requests.storage}；
     * {@code usedCapacityBytes} = Σ PVC {@code status.capacity.storage}（未 Bound 的 PVC
     * 计 0）。集群无存储 metrics API，<b>不编造</b>用量，来源在 {@code source} 字段注明。</p>
     *
     * @param environment 环境类型
     * @param clusterId   集群 ID
     * @return 200 + StorageUsage
     */
    @Operation(summary = "集群存储用量")
    @GetMapping("/{environment}/{clusterId}/storage/usage")
    public ResponseEntity<Map<String, Object>> getStorageUsage(@PathVariable String environment,
                                                               @PathVariable String clusterId) {
        // R13 安全修复：fail-closed 租户校验 + 集群归属校验
        String tenantId = requireTenant();
        EnvironmentType env = EnvironmentType.fromString(environment);
        ClusterInfo info = orchestrator.getClusterInfo(env, clusterId);
        requireTenantOwnedCluster(info, tenantId, clusterId);
        log.info("GET /api/v1/clusters/{}/{}/storage/usage tenant={}", environment, clusterId, tenantId);

        Map<String, long[]> byClass = new LinkedHashMap<>(); // [requestedBytes, allocatedBytes]
        for (PersistentVolumeClaim pvc : k8sClientService.listPersistentVolumeClaims()) {
            String sc = pvc.getSpec() != null && pvc.getSpec().getStorageClassName() != null
                    ? pvc.getSpec().getStorageClassName() : "(default)";
            long requested = quantityBytes(pvc.getSpec() != null && pvc.getSpec().getResources() != null
                    && pvc.getSpec().getResources().getRequests() != null
                    ? pvc.getSpec().getResources().getRequests().get("storage") : null);
            long allocated = quantityBytes(pvc.getStatus() != null && pvc.getStatus().getCapacity() != null
                    ? pvc.getStatus().getCapacity().get("storage") : null);
            byClass.computeIfAbsent(sc, k -> new long[2])[0] += requested;
            byClass.computeIfAbsent(sc, k -> new long[2])[1] += allocated;
        }
        long totalRequested = byClass.values().stream().mapToLong(v -> v[0]).sum();
        long totalAllocated = byClass.values().stream().mapToLong(v -> v[1]).sum();
        List<Map<String, Object>> byStorageClass = byClass.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("capacity", e.getValue()[0]); // requested bytes
                    m.put("used", e.getValue()[1]);      // allocated bytes
                    return m;
                })
                .toList();
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("totalCapacityBytes", totalRequested);
        usage.put("usedCapacityBytes", totalAllocated);
        usage.put("byStorageClass", byStorageClass);
        usage.put("units", "bytes");
        usage.put("source", "totalCapacityBytes=Σ PVC spec.resources.requests.storage; "
                + "usedCapacityBytes=Σ PVC status.capacity.storage（未 Bound 的 PVC 计 0；"
                + "集群无存储 metrics API，故以 K8s spec/status 请求量为口径，未编造实时用量）");
        return ResponseEntity.ok(usage);
    }

    /* ============================ K8s 资源 → 视图 ============================ */

    /** NetworkPolicy → 前端视图。 */
    private Map<String, Object> networkPolicyToView(NetworkPolicy np) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", np.getMetadata().getName());
        m.put("namespace", np.getMetadata().getNamespace());
        List<String> types = np.getSpec() != null && np.getSpec().getPolicyTypes() != null
                ? np.getSpec().getPolicyTypes() : List.of();
        // type：ingress / egress / both（与 infra.ts NetworkPolicyType 对齐）。
        // policyTypes 缺省时（旧策略/K8s 缺省推断）按实际存在的规则推断，不默认 ingress
        Set<String> upper = new java.util.LinkedHashSet<>(types);
        String type;
        if (upper.containsAll(List.of("Ingress", "Egress"))) {
            type = "both";
        } else if (upper.contains("Egress")) {
            type = "egress";
        } else if (upper.contains("Ingress")) {
            type = "ingress";
        } else {
            boolean hasIngress = np.getSpec() != null && np.getSpec().getIngress() != null
                    && !np.getSpec().getIngress().isEmpty();
            boolean hasEgress = np.getSpec() != null && np.getSpec().getEgress() != null
                    && !np.getSpec().getEgress().isEmpty();
            type = hasIngress && hasEgress ? "both" : hasEgress ? "egress" : "ingress";
        }
        m.put("type", type);
        m.put("policyTypes", types);
        // ports：从 ingress/egress 规则提取真实端口号（旧实现恒为空列表）
        List<Integer> ports = new ArrayList<>();
        if (np.getSpec() != null) {
            if (np.getSpec().getIngress() != null) {
                np.getSpec().getIngress().forEach(r -> collectPorts(r.getPorts(), ports));
            }
            if (np.getSpec().getEgress() != null) {
                np.getSpec().getEgress().forEach(r -> collectPorts(r.getPorts(), ports));
            }
        }
        m.put("ports", ports);
        // selector：回写成创建表单同款 k=v,k=v 形式（旧实现为 "{k=v}"，前端做 round-trip 会不一致）
        m.put("selector", np.getSpec() != null && np.getSpec().getPodSelector() != null
                && np.getSpec().getPodSelector().getMatchLabels() != null
                ? np.getSpec().getPodSelector().getMatchLabels().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .reduce((a, b) -> a + "," + b).orElse("")
                : "");
        return m;
    }

    /** 收集 NetworkPolicy 规则端口（IntOrString 数值型；命名端口无法映射 number，跳过）。 */
    private static void collectPorts(List<NetworkPolicyPort> rulePorts, List<Integer> out) {
        if (rulePorts == null) {
            return;
        }
        for (NetworkPolicyPort p : rulePorts) {
            if (p.getPort() != null && p.getPort().getIntVal() != null) {
                out.add(p.getPort().getIntVal());
            }
        }
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
        // requestedCapacity：spec.resources.requests.storage（创建请求量，K8s 真实值）
        String requested = pvc.getSpec() != null && pvc.getSpec().getResources() != null
                && pvc.getSpec().getResources().getRequests() != null
                ? String.valueOf(pvc.getSpec().getResources().getRequests().get("storage")) : "";
        m.put("requestedCapacity", requested);
        // capacity：优先 status.capacity（已分配容量）；未 Bound/新建 PVC 无 status.capacity 时
        // 回落到 spec 请求量（诚实口径：这是请求量，不是已分配量，故 requestedCapacity 一并返回）
        String allocated = pvc.getStatus() != null && pvc.getStatus().getCapacity() != null
                ? String.valueOf(pvc.getStatus().getCapacity().get("storage")) : null;
        m.put("capacity", allocated != null ? allocated : requested);
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
        // infra.ts HpaPolicy 契约字段：CPU/内存阈值从 spec.metrics（Utilization 型）取真实值
        if (hpa.getSpec() != null && hpa.getSpec().getMetrics() != null) {
            for (io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec spec : hpa.getSpec().getMetrics()) {
                if (spec.getResource() == null || spec.getResource().getTarget() == null) {
                    continue;
                }
                Integer pct = spec.getResource().getTarget().getAverageUtilization();
                if (pct == null) {
                    continue;
                }
                if ("cpu".equalsIgnoreCase(spec.getResource().getName())) {
                    m.put("cpuThreshold", pct);
                } else if ("memory".equalsIgnoreCase(spec.getResource().getName())) {
                    m.put("memoryThreshold", pct);
                }
            }
        }
        return m;
    }

    /* ===================== P0-1 新增资源 → 视图 ===================== */

    /**
     * Node → 前端 {@code ClusterNode} 视图。
     *
     * <p>cpuUsed/memUsed 仅在 metrics.k8s.io 可用（{@code metricsAvailable}）时填真实
     * 用量；否则置 null 并标注 {@code metricsAvailable=false}——不编造数字。</p>
     */
    private Map<String, Object> nodeToView(Node node,
                                           NodeMetrics metric,
                                           long podCount,
                                           boolean metricsAvailable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", node.getMetadata().getName());
        m.put("roles", nodeRoles(node));
        m.put("status", nodeStatus(node));
        m.put("cpuCapacity", quantityToCores(capacity(node, "cpu")));
        m.put("memCapacity", quantityToGi(capacity(node, "memory")));
        if (metricsAvailable && metric != null) {
            m.put("cpuUsed", quantityToCores(metric.getUsage().get("cpu")));
            m.put("memUsed", quantityToGi(metric.getUsage().get("memory")));
            m.put("metricsAvailable", Boolean.TRUE);
        } else {
            // 无 metrics.k8s.io（未部署 metrics-server）：如实返回 null，前端据此显示"—"，不填 0 假装空闲
            m.put("cpuUsed", null);
            m.put("memUsed", null);
            m.put("metricsAvailable", Boolean.FALSE);
        }
        m.put("podCount", podCount);
        m.put("podCapacity", orZero(quantityToInt(capacity(node, "pods"))));
        m.put("osImage", nodeInfo(node) != null ? nodeInfo(node).getKernelVersion() : "");
        m.put("containerRuntime", nodeInfo(node) != null ? nodeInfo(node).getContainerRuntimeVersion() : "");
        m.put("createdAt", node.getMetadata() != null && node.getMetadata().getCreationTimestamp() != null
                ? node.getMetadata().getCreationTimestamp() : "");
        return m;
    }

    /** 工作负载（Deployment/StatefulSet/DaemonSet）→ 前端 {@code ClusterComponent} 视图。 */
    private Map<String, Object> workloadToView(ObjectMeta meta,
                                               String type,
                                               int total,
                                               int readyCount,
                                               String image) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", meta != null ? meta.getName() : "");
        m.put("namespace", meta != null ? meta.getNamespace() : "");
        m.put("type", type);
        m.put("readyReplicas", readyCount);
        m.put("replicas", total);
        m.put("images", image == null ? List.of() : List.of(image));
        // status：healthy=全部就绪（含缩零待机）；warning=部分就绪；error=有副本但 0 就绪
        String status = "healthy";
        if (total > 0 && readyCount < total) {
            status = readyCount > 0 ? "warning" : "error";
        }
        m.put("status", status);
        m.put("meta", type + " · " + (meta != null ? meta.getNamespace() : "") + " · " + readyCount + "/" + total);
        return m;
    }

    /**
     * 从 kube-system 命名空间 DaemonSet 探测 CNI 插件（集群真实配置来源）。
     *
     * @return calico/flannel/cilium/kube-ovn 之一；未探测到返回 {@code null}（调用方沿用既有常量）
     */
    private static String detectCniPlugin(List<DaemonSet> daemonSets) {
        return daemonSets.stream()
                .filter(ds -> "kube-system".equals(ds.getMetadata().getNamespace()))
                .map(ds -> ds.getMetadata().getName().toLowerCase(Locale.ROOT))
                .sorted()
                .filter(name -> name.contains("flannel") || name.contains("calico")
                        || name.contains("cilium") || name.contains("ovn"))
                .map(name -> {
                    if (name.contains("cilium")) {
                        return "cilium";
                    }
                    if (name.contains("calico")) {
                        return "calico";
                    }
                    if (name.contains("ovn")) {
                        return "kube-ovn";
                    }
                    return "flannel";
                })
                .findFirst()
                .orElse(null);
    }

    /** K8s Event → 视图（保留原始事件字段，不编造扩缩容方向/耗时）。 */
    private Map<String, Object> eventToView(Event event) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", event.getType() != null ? event.getType() : "Normal");
        m.put("reason", event.getReason() != null ? event.getReason() : "");
        io.fabric8.kubernetes.api.model.ObjectReference ref = event.getRegarding();
        m.put("objectKind", ref != null && ref.getKind() != null ? ref.getKind() : "");
        m.put("objectName", ref != null && ref.getName() != null ? ref.getName() : "");
        m.put("objectNamespace", ref != null && ref.getNamespace() != null ? ref.getNamespace()
                : (event.getMetadata() != null ? event.getMetadata().getNamespace() : ""));
        m.put("message", event.getNote() != null ? event.getNote() : "");
        // MicroTime.toString() 是 Lombok 全字段输出，取原始 ISO 字符串需用 getTime()
        String timestamp = event.getEventTime() != null ? event.getEventTime().getTime() : null;
        if (timestamp == null) {
            timestamp = event.getDeprecatedLastTimestamp();
        }
        if (timestamp == null) {
            timestamp = event.getDeprecatedFirstTimestamp();
        }
        m.put("timestamp", timestamp != null ? timestamp : "");
        return m;
    }

    /**
     * 解析 Pod 选择器字符串（{@code k=v,k=v}）为 LabelSelector.matchLabels。
     *
     * @param selector 选择器字符串（空 = 全选）
     * @throws IllegalArgumentException 片段不含 '=' 或 key 为空时
     */
    private static LabelSelector parseSelector(String selector) {
        LabelSelector ls = new LabelSelector();
        if (selector == null || selector.isBlank()) {
            return ls;
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (String part : selector.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int eq = token.indexOf('=');
            if (eq <= 0 || eq == token.length() - 1) {
                throw new IllegalArgumentException("invalid pod selector: " + token
                        + ", expected key=value");
            }
            labels.put(token.substring(0, eq).trim(), token.substring(eq + 1).trim());
        }
        ls.setMatchLabels(labels);
        return ls;
    }

    /** HPA 创建/更新请求 → autoscaling/v2 HorizontalPodAutoscaler。 */
    private static HorizontalPodAutoscaler buildHpa(HpaCreateRequest req) {
        if (req.getMinReplicas() != null && req.getMaxReplicas() != null
                && req.getMinReplicas() > req.getMaxReplicas()) {
            throw new IllegalArgumentException(
                    "minReplicas must not exceed maxReplicas: " + req.getMinReplicas() + ">" + req.getMaxReplicas());
        }
        HorizontalPodAutoscalerBuilder builder = new HorizontalPodAutoscalerBuilder()
                .withNewMetadata()
                    .withName(req.getName())
                    .withNamespace(req.getNamespace())
                .endMetadata()
                .withNewSpec()
                    .withMinReplicas(req.getMinReplicas())
                    .withMaxReplicas(req.getMaxReplicas())
                    .withNewScaleTargetRef()
                        .withApiVersion("apps/v1")
                        .withKind("Deployment")
                        .withName(req.getTargetDeployment())
                    .endScaleTargetRef()
                    .addToMetrics(cpuMetricSpec(req.getCpuThreshold()))
                .endSpec();
        if (req.getMemoryThreshold() != null && req.getMemoryThreshold() > 0) {
            builder = builder.editSpec()
                    .addToMetrics(cpuMetricSpec(req.getMemoryThreshold(), "memory"))
                    .endSpec();
        }
        return builder.build();
    }

    /** 构造 Utilization 型 ResourceMetricSpec（cpu/memory）。 */
    private static io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec cpuMetricSpec(
            Integer pct, String resourceName) {
        return new MetricSpecBuilder()
                .withType("Resource")
                .withNewResource()
                    .withName(resourceName)
                    .withNewTarget()
                        .withType("Utilization")
                        .withAverageUtilization(pct)
                    .endTarget()
                .endResource()
                .build();
    }

    /** @see #cpuMetricSpec(Integer, String)（默认 cpu）。 */
    private static io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec cpuMetricSpec(Integer pct) {
        return cpuMetricSpec(pct, "cpu");
    }

    /** 工作负载镜像（首个容器的 image；无 template 时返回 null，不编造）。 */
    private static String firstImage(PodTemplateSpec template) {
        if (template == null || template.getSpec() == null
                || template.getSpec().getContainers() == null
                || template.getSpec().getContainers().isEmpty()) {
            return null;
        }
        Container c = template.getSpec().getContainers().get(0);
        return c.getImage();
    }

    /** spec.replicas 缺省按 K8s 语义取 1。 */
    private static int replicas(Integer specReplicas) {
        return specReplicas == null ? 1 : specReplicas;
    }

    /** status 计数缺省取 0（未知/未上报 = 0）。 */
    private static int ready(Integer statusCount) {
        return statusCount == null ? 0 : statusCount;
    }

    /** null 安全的计数（DaemonSet desired/numberReady）。 */
    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }

    /** Node 状态：Ready 条件 → Ready / NotReady / Unknown。 */
    private static String nodeStatus(Node node) {
        if (node.getStatus() == null || node.getStatus().getConditions() == null) {
            return "Unknown";
        }
        for (io.fabric8.kubernetes.api.model.NodeCondition c : node.getStatus().getConditions()) {
            if ("Ready".equals(c.getType())) {
                return "True".equals(c.getStatus()) ? "Ready" : "NotReady";
            }
        }
        return "Unknown";
    }

    /** Node 角色：control-plane/master 标签 → master（前端据此渲染），否则 worker。 */
    private static List<String> nodeRoles(Node node) {
        if (node.getMetadata() == null || node.getMetadata().getLabels() == null) {
            return List.of("worker");
        }
        boolean master = node.getMetadata().getLabels()
                .containsKey("node-role.kubernetes.io/control-plane")
                || node.getMetadata().getLabels().containsKey("node-role.kubernetes.io/master");
        return List.of(master ? "master" : "worker");
    }

    /** Node 容量资源（缺失返回 null，不填 0 假装无容量）。 */
    private static Quantity capacity(Node node, String resource) {
        if (node.getStatus() == null || node.getStatus().getCapacity() == null) {
            return null;
        }
        return node.getStatus().getCapacity().get(resource);
    }

    /** NodeSystemInfo（缺失返回 null）。 */
    private static io.fabric8.kubernetes.api.model.NodeSystemInfo nodeInfo(Node node) {
        return node.getStatus() != null ? node.getStatus().getNodeInfo() : null;
    }

    /** Quantity → CPU 核数（如 "8" → 8.0，"500m" → 0.5）；null → 0.0。 */
    private static double quantityToCores(Quantity q) {
        if (q == null) {
            return 0d;
        }
        try {
            return round2(q.getNumericalAmount());
        } catch (ArithmeticException e) {
            log.warn("quantity cannot be parsed to cores: {}", q.getAmount());
            return 0d;
        }
    }

    /** Quantity（bytes）→ GiB；null → 0.0。 */
    private static double quantityToGi(Quantity q) {
        if (q == null) {
            return 0d;
        }
        try {
            return round2(Quantity.getAmountInBytes(q)
                    .divide(BigDecimal.valueOf(1L << 30), 2, RoundingMode.HALF_UP));
        } catch (ArithmeticException e) {
            log.warn("quantity cannot be parsed to bytes: {}", q.getAmount());
            return 0d;
        }
    }

    /** Quantity → 字节数（不可解析时返回 0，调用方按口径注明）。 */
    private static long quantityBytes(Quantity q) {
        if (q == null) {
            return 0L;
        }
        try {
            return Quantity.getAmountInBytes(q).longValue();
        } catch (ArithmeticException e) {
            log.warn("quantity cannot be parsed to bytes: {}", q.getAmount());
            return 0L;
        }
    }

    /** Quantity → 整数（用于 pods 计数类容量）。 */
    private static Integer quantityToInt(Quantity q) {
        if (q == null) {
            return null;
        }
        try {
            return q.getNumericalAmount().intValue();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** BigDecimal 两位小数四舍五入。 */
    private static double round2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}