package com.shuqing.bigdata.infra.orchestrator.controller;

import com.shuqing.bigdata.infra.orchestrator.model.ClusterCreateRequest;
import com.shuqing.bigdata.infra.orchestrator.model.ClusterInfo;
import com.shuqing.bigdata.infra.orchestrator.model.ClusterScaleRequest;
import com.shuqing.bigdata.infra.orchestrator.model.EnvironmentType;
import com.shuqing.bigdata.infra.orchestrator.model.SupplyResult;
import com.shuqing.bigdata.infra.orchestrator.registry.EnvironmentProfile;
import com.shuqing.bigdata.infra.orchestrator.registry.ProviderDescriptor;
import com.shuqing.bigdata.infra.orchestrator.security.TenantContext;
import com.shuqing.bigdata.infra.orchestrator.service.ProviderRegistryService;
import com.shuqing.bigdata.infra.orchestrator.service.SupplyOrchestrator;
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
@RequestMapping("/api/v1/clusters")
public class ClusterController {

    private static final Logger log = LoggerFactory.getLogger(ClusterController.class);

    private final SupplyOrchestrator orchestrator;
    private final ProviderRegistryService providerRegistryService;

    /**
     * 构造 Controller。
     *
     * @param orchestrator            供应编排器
     * @param providerRegistryService Provider 注册服务
     */
    public ClusterController(SupplyOrchestrator orchestrator,
                            ProviderRegistryService providerRegistryService) {
        this.orchestrator = orchestrator;
        this.providerRegistryService = providerRegistryService;
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
}