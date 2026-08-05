package com.shuqing.bigdata.infra.xinchang.controller;

import com.shuqing.bigdata.infra.xinchang.model.ClusterCreateRequest;
import com.shuqing.bigdata.infra.xinchang.model.ClusterInfo;
import com.shuqing.bigdata.infra.xinchang.model.ClusterScaleRequest;
import com.shuqing.bigdata.infra.xinchang.security.TenantContext;
import com.shuqing.bigdata.infra.xinchang.service.XinchangProviderService;
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

import java.util.List;
import java.util.Map;

/**
 * 信创集群供应 REST API。
 *
 * <p>对应 L0.1 信创资源供应 Provider 的对外契约：</p>
 * <ul>
 *   <li>{@code POST   /api/v1/clusters/xinchang}                     - 创建集群</li>
 *   <li>{@code DELETE /api/v1/clusters/xinchang/{clusterId}}         - 销毁集群</li>
 *   <li>{@code GET    /api/v1/clusters/xinchang/{clusterId}}         - 查询集群状态</li>
 *   <li>{@code POST   /api/v1/clusters/xinchang/{clusterId}/scale}  - 扩缩容</li>
 *   <li>{@code GET    /api/v1/clusters/xinchang}                     - 列出租户全部集群</li>
 *   <li>{@code GET    /api/v1/health}                                - 健康检查</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/clusters/xinchang")
public class XinchangClusterController {

    private static final Logger log = LoggerFactory.getLogger(XinchangClusterController.class);

    private final XinchangProviderService providerService;

    /**
     * 构造 Controller。
     *
     * @param providerService 信创供应服务
     */
    public XinchangClusterController(XinchangProviderService providerService) {
        this.providerService = providerService;
    }

    /**
     * 创建信创集群。
     *
     * @param request 创建请求（含节点规格、K8s 版本、网段等）
     * @return 集群信息（201 Created）
     */
    @PostMapping
    public ResponseEntity<ClusterInfo> createCluster(@Valid @RequestBody ClusterCreateRequest request) {
        // JWT 注入的 tenantId 覆盖请求体中的 tenantId，防止越权
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            request.setTenantId(tenantId);
        }
        log.info("POST /api/v1/clusters/xinchang - createCluster name={}", request.getClusterName());
        ClusterInfo info = providerService.createCluster(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(info);
    }

    /**
     * 销毁信创集群。
     *
     * @param clusterId 集群 ID
     * @return 集群信息（DESTROYED）
     */
    @DeleteMapping("/{clusterId}")
    public ResponseEntity<ClusterInfo> destroyCluster(@PathVariable String clusterId) {
        String tenantId = TenantContext.getTenantId();
        log.info("DELETE /api/v1/clusters/xinchang/{} - destroyCluster", clusterId);
        ClusterInfo info = providerService.destroyCluster(clusterId, tenantId);
        return ResponseEntity.ok(info);
    }

    /**
     * 查询信创集群状态。
     *
     * @param clusterId 集群 ID
     * @return 集群信息
     */
    @GetMapping("/{clusterId}")
    public ResponseEntity<ClusterInfo> getClusterInfo(@PathVariable String clusterId) {
        String tenantId = TenantContext.getTenantId();
        log.info("GET /api/v1/clusters/xinchang/{} - getClusterInfo", clusterId);
        ClusterInfo info = providerService.getClusterInfo(clusterId, tenantId);
        return ResponseEntity.ok(info);
    }

    /**
     * 扩缩容信创集群。
     *
     * @param clusterId 集群 ID
     * @param request   扩缩容请求
     * @return 集群信息
     */
    @PostMapping("/{clusterId}/scale")
    public ResponseEntity<ClusterInfo> scaleCluster(@PathVariable String clusterId,
                                                    @Valid @RequestBody ClusterScaleRequest request) {
        String tenantId = TenantContext.getTenantId();
        log.info("POST /api/v1/clusters/xinchang/{}/scale - scaleCluster", clusterId);
        ClusterInfo info = providerService.scaleCluster(clusterId, tenantId, request);
        return ResponseEntity.ok(info);
    }

    /**
     * 列出当前租户的全部信创集群。
     *
     * @return 集群列表
     */
    @GetMapping
    public ResponseEntity<List<ClusterInfo>> listClusters() {
        String tenantId = TenantContext.getTenantId();
        log.info("GET /api/v1/clusters/xinchang - listClusters tenant={}", tenantId);
        List<ClusterInfo> clusters = providerService.listClusters(tenantId);
        return ResponseEntity.ok(clusters);
    }

    /**
     * 健康检查（无需鉴权）。
     *
     * @return 健康状态
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "provider", "xinchang",
                "supportedCpuArch", List.of("KUNPENG", "HYGON", "PHYTIUM", "ZHAOXIN"),
                "supportedOs", List.of("KYLIN_V10", "UOS")));
    }
}