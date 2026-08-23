package com.levango7.dataenginebdp.infra.cloud.controller;

import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterInfo;
import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterRequest;
import com.levango7.dataenginebdp.infra.cloud.model.ClusterScaleRequest;
import com.levango7.dataenginebdp.infra.cloud.service.CloudProviderService;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * 云集群统一 REST 控制器。
 *
 * <p>对外暴露多云 VM 供应的统一 REST API，路径变量 {@code {provider}} 路由到
 * 华为云 / 阿里云 / 腾讯云的 {@link com.levango7.dataenginebdp.infra.cloud.provider.CloudProvider} 实现。</p>
 *
 * <p>API 端点：</p>
 * <ul>
 *   <li>{@code POST   /api/v1/clusters/cloud/{provider}} - 创建集群</li>
 *   <li>{@code DELETE /api/v1/clusters/cloud/{provider}/{id}} - 销毁集群</li>
 *   <li>{@code GET    /api/v1/clusters/cloud/{provider}/{id}} - 查询集群</li>
 *   <li>{@code GET    /api/v1/clusters/cloud/{provider}} - 列出集群</li>
 *   <li>{@code POST   /api/v1/clusters/cloud/{provider}/{id}/scale} - 扩缩容</li>
 *   <li>{@code POST   /api/v1/clusters/cloud/{provider}/{id}/start} - 启动</li>
 *   <li>{@code POST   /api/v1/clusters/cloud/{provider}/{id}/stop} - 停止</li>
 *   <li>{@code GET    /api/v1/clusters/cloud/providers} - 列出支持的 provider</li>
 * </ul>
 */
@RestController
@Tag(name = "基础设施供应-云集群", description = "多云VM集群供应(华为/阿里/腾讯)")
@RequestMapping("/api/v1/clusters/cloud")
public class CloudClusterController {

    private static final Logger log = LoggerFactory.getLogger(CloudClusterController.class);

    private final CloudProviderService cloudProviderService;

    public CloudClusterController(CloudProviderService cloudProviderService) {
        this.cloudProviderService = cloudProviderService;
    }

    /**
     * 创建云集群。
     *
     * @param provider 云 provider 标识：huawei / ali / tencent
     * @param request  集群创建请求
     * @return 集群信息（HTTP 201）
     */
    @Operation(summary = "创建云集群")
    @PostMapping("/{provider}")
    public ResponseEntity<CloudClusterInfo> createCluster(
            @PathVariable String provider,
            @Valid @RequestBody CloudClusterRequest request) {
        log.info("REST createCluster: provider={}, clusterName={}, nodeCount={}",
                provider, request.getClusterName(), request.getNodeCount());
        CloudClusterInfo info = cloudProviderService.createCluster(provider, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(info);
    }

    /**
     * 销毁云集群。
     *
     * @param provider 云 provider 标识
     * @param id       集群 ID
     * @return 销毁后的集群信息（HTTP 200）；集群不存在返回 HTTP 404
     */
    @Operation(summary = "销毁云集群")
    @DeleteMapping("/{provider}/{id}")
    public ResponseEntity<CloudClusterInfo> destroyCluster(
            @PathVariable String provider,
            @PathVariable String id) {
        log.info("REST destroyCluster: provider={}, clusterId={}", provider, id);
        try {
            CloudClusterInfo info = cloudProviderService.destroyCluster(provider, id);
            return ResponseEntity.ok(info);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 查询云集群。
     *
     * @param provider 云 provider 标识
     * @param id       集群 ID
     * @return 集群信息（HTTP 200）；集群不存在返回 HTTP 404
     */
    @Operation(summary = "查询云集群")
    @GetMapping("/{provider}/{id}")
    public ResponseEntity<CloudClusterInfo> getCluster(
            @PathVariable String provider,
            @PathVariable String id) {
        CloudClusterInfo info = cloudProviderService.getCluster(provider, id);
        if (info == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(info);
    }

    /**
     * 列出指定 provider 的所有集群。
     *
     * @param provider 云 provider 标识
     * @return 集群列表
     */
    @Operation(summary = "列出指定 provider 的所有集群")
    @GetMapping("/{provider}")
    public ResponseEntity<List<CloudClusterInfo>> listClusters(@PathVariable String provider) {
        return ResponseEntity.ok(cloudProviderService.listClusters(provider));
    }

    /**
     * 扩缩容云集群。
     *
     * @param provider 云 provider 标识
     * @param id       集群 ID
     * @param request  扩缩容请求（含目标节点数）
     * @return 集群信息
     */
    @Operation(summary = "扩缩容云集群")
    @PostMapping("/{provider}/{id}/scale")
    public ResponseEntity<CloudClusterInfo> scaleCluster(
            @PathVariable String provider,
            @PathVariable String id,
            @Valid @RequestBody ClusterScaleRequest request) {
        log.info("REST scaleCluster: provider={}, clusterId={}, target={}",
                provider, id, request.getTargetNodeCount());
        CloudClusterInfo info = cloudProviderService.scaleCluster(provider, id, request.getTargetNodeCount());
        return ResponseEntity.ok(info);
    }

    /**
     * 启动云集群。
     *
     * @param provider 云 provider 标识
     * @param id       集群 ID
     * @return 集群信息
     */
    @Operation(summary = "启动云集群")
    @PostMapping("/{provider}/{id}/start")
    public ResponseEntity<CloudClusterInfo> startCluster(
            @PathVariable String provider,
            @PathVariable String id) {
        log.info("REST startCluster: provider={}, clusterId={}", provider, id);
        return ResponseEntity.ok(cloudProviderService.startCluster(provider, id));
    }

    /**
     * 停止云集群。
     *
     * @param provider 云 provider 标识
     * @param id       集群 ID
     * @return 集群信息
     */
    @Operation(summary = "停止云集群")
    @PostMapping("/{provider}/{id}/stop")
    public ResponseEntity<CloudClusterInfo> stopCluster(
            @PathVariable String provider,
            @PathVariable String id) {
        log.info("REST stopCluster: provider={}, clusterId={}", provider, id);
        return ResponseEntity.ok(cloudProviderService.stopCluster(provider, id));
    }

    /**
     * 列出所有支持的云 provider。
     *
     * @return provider 列表（如 ["huawei","ali","tencent"]）
     */
    @Operation(summary = "列出所有支持的云 provider")
    @GetMapping("/providers")
    public ResponseEntity<Map<String, List<String>>> listProviders() {
        return ResponseEntity.ok(Map.of("providers", cloudProviderService.listSupportedProviders()));
    }
}