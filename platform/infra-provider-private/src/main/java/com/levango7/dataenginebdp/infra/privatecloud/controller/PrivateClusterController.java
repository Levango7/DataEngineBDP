package com.levango7.dataenginebdp.infra.privatecloud.controller;

import com.levango7.dataenginebdp.infra.privatecloud.model.PrivateClusterInfo;
import com.levango7.dataenginebdp.infra.privatecloud.model.PrivateClusterRequest;
import com.levango7.dataenginebdp.infra.privatecloud.model.PrivateClusterRepository;
import com.levango7.dataenginebdp.infra.privatecloud.model.ScaleRequest;
import com.levango7.dataenginebdp.infra.privatecloud.provider.PrivateCloudProvider;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.infra.privatecloud.service.K8sBootstrapService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 私有云集群 REST 控制器。
 *
 * <p>提供统一的私有云 K8s 集群管理 API，通过路径变量 {@code provider} 路由到
 * 对应的 {@link PrivateCloudProvider} 实现（vsphere / openstack）。</p>
 *
 * <p>端点：</p>
 * <ul>
 *   <li>{@code POST   /api/v1/clusters/private/{provider}} - 创建集群；</li>
 *   <li>{@code DELETE /api/v1/clusters/private/{provider}/{id}} - 销毁集群；</li>
 *   <li>{@code GET    /api/v1/clusters/private/{provider}/{id}} - 查询集群；</li>
 *   <li>{@code POST   /api/v1/clusters/private/{provider}/{id}/scale} - 扩缩容；</li>
 *   <li>{@code GET    /api/v1/clusters/private/{provider}} - 列出集群。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@RestController
@Tag(name = "基础设施供应-私有云集群", description = "vSphere/OpenStack集群供应")
@RequestMapping("/api/v1/clusters/private")
public class PrivateClusterController {

    private static final Logger log = LoggerFactory.getLogger(PrivateClusterController.class);

    private final Map<String, PrivateCloudProvider> providers;
    private final PrivateClusterRepository repository;
    private final K8sBootstrapService k8sBootstrapService;

    /**
     * 构造控制器。
     *
     * <p>Spring 自动将所有 {@link PrivateCloudProvider} Bean 注入 Map，
     * key 为 Bean 名称（{@code vsphereProvider} / {@code openstackProvider}）。
     * 此处按 {@link PrivateCloudProvider#getType()} 重新构建 key，
     * 使其与 REST 路径变量 {@code provider} 对齐（{@code vsphere} / {@code openstack}）。</p>
     *
     * @param providerList       所有 Provider 实现
     * @param repository          集群信息 Repository
     * @param k8sBootstrapService K8s 引导服务
     */
    public PrivateClusterController(List<PrivateCloudProvider> providerList,
                                    PrivateClusterRepository repository,
                                    K8sBootstrapService k8sBootstrapService) {
        this.repository = repository;
        this.k8sBootstrapService = k8sBootstrapService;
        java.util.Map<String, PrivateCloudProvider> map = new java.util.HashMap<>();
        for (PrivateCloudProvider p : providerList) {
            map.put(p.getType(), p);
        }
        this.providers = map;
        log.info("PrivateClusterController 初始化: providers={}", providers.keySet());
    }

    /**
     * 创建私有云 K8s 集群。
     *
     * @param provider 云平台类型（vsphere / openstack）
     * @param request  集群创建请求
     * @return 创建的集群信息
     */
    @PostMapping("/{provider}")
    public ResponseEntity<PrivateClusterInfo> createCluster(@PathVariable String provider,
                                                            @Valid @RequestBody PrivateClusterRequest request) {
        PrivateCloudProvider p = resolveProvider(provider);

        log.info("创建私有云集群: provider={} clusterName={}", provider, request.getClusterName());
        PrivateClusterInfo cluster = new PrivateClusterInfo();
        cluster.setClusterName(request.getClusterName());
        cluster.setProvider(provider);
        cluster.setTenantId(TenantContext.getTenantId());
        cluster.setStatus("CREATING");
        cluster.setK8sVersion(request.getK8sVersion());
        cluster.setPodCidr(request.getPodCidr());
        cluster.setServiceCidr(request.getServiceCidr());
        cluster.setControlPlaneCount(1);
        cluster.setWorkerCount(request.getWorkers() == null ? 0 : request.getWorkers().size());
        cluster.setCreatedAt(LocalDateTime.now());
        cluster.setUpdatedAt(LocalDateTime.now());
        cluster = repository.save(cluster);

        try {
            // 1. 创建 VM
            List<PrivateClusterInfo.VMInfo> vms = p.createVMs(request);
            cluster.setVms(vms);
            cluster.setVmJson(k8sBootstrapService.serializeVms(vms));

            // 2. K8s 引导
            boolean bootstrapOk = k8sBootstrapService.bootstrap(cluster);
            cluster.setStatus(bootstrapOk ? "RUNNING" : "FAILED");
            if (!bootstrapOk) {
                cluster.setErrorMessage("K8s 引导失败");
            }
        } catch (Exception e) {
            log.error("集群创建失败: provider={} clusterName={} err={}",
                    provider, request.getClusterName(), e.getMessage(), e);
            cluster.setStatus("FAILED");
            cluster.setErrorMessage(e.getMessage());
        }

        cluster.setUpdatedAt(LocalDateTime.now());
        cluster = repository.save(cluster);
        fillVms(cluster);

        HttpStatus httpStatus = "FAILED".equals(cluster.getStatus())
                ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.CREATED;
        return ResponseEntity.status(httpStatus).body(cluster);
    }

    /**
     * 销毁私有云 K8s 集群。
     *
     * @param provider 云平台类型
     * @param id       集群 ID
     * @return 204 成功 / 404 不存在
     */
    @DeleteMapping("/{provider}/{id}")
    public ResponseEntity<Void> destroyCluster(@PathVariable String provider,
                                               @PathVariable Long id) {
        PrivateCloudProvider p = resolveProvider(provider);
        PrivateClusterInfo cluster = repository.findById(id).orElse(null);
        if (cluster == null || !provider.equals(cluster.getProvider())) {
            return ResponseEntity.notFound().build();
        }

        log.info("销毁私有云集群: provider={} clusterId={}", provider, id);
        cluster.setStatus("DELETING");
        cluster.setUpdatedAt(LocalDateTime.now());
        repository.save(cluster);

        fillVms(cluster);
        boolean destroyed = p.destroyVMs(cluster);
        cluster.setStatus(destroyed ? "DELETED" : "FAILED");
        if (!destroyed) {
            cluster.setErrorMessage("部分 VM 销毁失败");
        }
        cluster.setUpdatedAt(LocalDateTime.now());
        repository.save(cluster);

        return destroyed ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    /**
     * 查询私有云 K8s 集群。
     *
     * @param provider 云平台类型
     * @param id       集群 ID
     * @return 集群信息（含 VM 实时状态）
     */
    @GetMapping("/{provider}/{id}")
    public ResponseEntity<PrivateClusterInfo> getCluster(@PathVariable String provider,
                                                        @PathVariable Long id) {
        PrivateCloudProvider p = resolveProvider(provider);
        PrivateClusterInfo cluster = repository.findById(id).orElse(null);
        if (cluster == null || !provider.equals(cluster.getProvider())) {
            return ResponseEntity.notFound().build();
        }

        fillVms(cluster);
        // 实时刷新 VM 状态
        try {
            List<PrivateClusterInfo.VMInfo> refreshed = p.getVMInfo(cluster);
            cluster.setVms(refreshed);
        } catch (Exception e) {
            log.warn("刷新 VM 状态失败: clusterId={} err={}", id, e.getMessage());
        }
        return ResponseEntity.ok(cluster);
    }

    /**
     * 列出指定 provider 的所有集群。
     *
     * @param provider 云平台类型
     * @return 集群列表
     */
    @GetMapping("/{provider}")
    public ResponseEntity<List<PrivateClusterInfo>> listClusters(@PathVariable String provider) {
        resolveProvider(provider);
        String tenantId = TenantContext.getTenantId();
        List<PrivateClusterInfo> clusters;
        if (tenantId != null) {
            clusters = repository.findByTenantIdAndProvider(tenantId, provider);
        } else {
            clusters = repository.findAll().stream()
                    .filter(c -> provider.equals(c.getProvider()))
                    .toList();
        }
        clusters.forEach(this::fillVms);
        return ResponseEntity.ok(clusters);
    }

    /**
     * 扩缩容集群工作节点。
     *
     * @param provider 云平台类型
     * @param id       集群 ID
     * @param request  扩缩容请求
     * @return 变更后的集群信息
     */
    @PostMapping("/{provider}/{id}/scale")
    public ResponseEntity<PrivateClusterInfo> scaleCluster(@PathVariable String provider,
                                                           @PathVariable Long id,
                                                           @Valid @RequestBody ScaleRequest request) {
        PrivateCloudProvider p = resolveProvider(provider);
        PrivateClusterInfo cluster = repository.findById(id).orElse(null);
        if (cluster == null || !provider.equals(cluster.getProvider())) {
            return ResponseEntity.notFound().build();
        }

        log.info("扩缩容集群: provider={} clusterId={} target={}", provider, id, request.getTargetWorkerCount());
        cluster.setStatus("SCALING");
        cluster.setUpdatedAt(LocalDateTime.now());
        repository.save(cluster);

        fillVms(cluster);
        try {
            List<PrivateClusterInfo.VMInfo> vms = p.scaleVMs(cluster,
                    request.getTargetWorkerCount(), request.getWorkerSpec());
            cluster.setVms(vms);
            cluster.setVmJson(k8sBootstrapService.serializeVms(vms));
            cluster.setWorkerCount((int) vms.stream()
                    .filter(vm -> "worker".equals(vm.getRole()))
                    .count());
            cluster.setStatus("RUNNING");
        } catch (Exception e) {
            log.error("扩缩容失败: clusterId={} err={}", id, e.getMessage(), e);
            cluster.setStatus("FAILED");
            cluster.setErrorMessage(e.getMessage());
        }
        cluster.setUpdatedAt(LocalDateTime.now());
        cluster = repository.save(cluster);
        fillVms(cluster);
        return ResponseEntity.ok(cluster);
    }

    /**
     * 根据路径变量解析 Provider，不存在则抛 400。
     *
     * @param provider 路径变量
     * @return Provider 实现
     */
    private PrivateCloudProvider resolveProvider(String provider) {
        PrivateCloudProvider p = providers.get(provider);
        if (p == null) {
            throw new IllegalArgumentException("不支持的 provider: " + provider
                    + "，当前支持: " + providers.keySet());
        }
        return p;
    }

    /**
     * 从 vmJson 反序列化填充 vms 字段。
     *
     * @param cluster 集群信息
     */
    private void fillVms(PrivateClusterInfo cluster) {
        if (cluster.getVms() == null) {
            cluster.setVms(k8sBootstrapService.deserializeVms(cluster.getVmJson()));
        }
    }
}