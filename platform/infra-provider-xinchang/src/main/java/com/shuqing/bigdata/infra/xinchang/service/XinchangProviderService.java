package com.shuqing.bigdata.infra.xinchang.service;

import com.shuqing.bigdata.infra.xinchang.model.ClusterCreateRequest;
import com.shuqing.bigdata.infra.xinchang.model.ClusterInfo;
import com.shuqing.bigdata.infra.xinchang.model.ClusterScaleRequest;
import com.shuqing.bigdata.infra.xinchang.provider.ClusterProvider;
import com.shuqing.bigdata.infra.xinchang.provider.XinchangProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 信创资源供应核心服务。
 *
 * <p>封装 {@link ClusterProvider} SPI 调用，提供：</p>
 * <ul>
 *   <li>Provider 路由：当前固定使用 {@link XinchangProvider}，未来支持多 provider 动态选择</li>
 *   <li>租户隔离校验：确保查询/销毁/扩缩容只能操作本租户的集群</li>
 *   <li>异常包装：将底层异常统一转换为业务异常 {@link ClusterOperationException}</li>
 * </ul>
 */
@Service
public class XinchangProviderService {

    private static final Logger log = LoggerFactory.getLogger(XinchangProviderService.class);

    private final XinchangProvider xinchangProvider;

    /**
     * 构造服务。
     *
     * @param xinchangProvider 信创 Provider 实现
     */
    public XinchangProviderService(XinchangProvider xinchangProvider) {
        this.xinchangProvider = xinchangProvider;
    }

    /**
     * 创建集群。
     *
     * @param request 创建请求
     * @return 集群信息
     * @throws ClusterOperationException 创建失败
     */
    public ClusterInfo createCluster(ClusterCreateRequest request) {
        try {
            log.info("Service: createCluster name={} tenant={}", request.getClusterName(), request.getTenantId());
            return xinchangProvider.createCluster(request);
        } catch (Exception e) {
            throw new ClusterOperationException("Failed to create cluster: " + e.getMessage(), e);
        }
    }

    /**
     * 销毁集群。
     *
     * @param clusterId 集群 ID
     * @param tenantId  调用方租户 ID（用于隔离校验）
     * @return 集群信息
     * @throws ClusterOperationException   销毁失败
     * @throws ClusterNotFoundException    集群不存在
     * @throws ClusterAccessDeniedException 跨租户访问
     */
    public ClusterInfo destroyCluster(String clusterId, String tenantId) {
        ClusterInfo info = xinchangProvider.getClusterInfo(clusterId);
        if (info == null) {
            throw new ClusterNotFoundException("Cluster not found: " + clusterId);
        }
        ensureTenantMatch(info, tenantId);
        try {
            return xinchangProvider.destroyCluster(clusterId);
        } catch (Exception e) {
            throw new ClusterOperationException("Failed to destroy cluster: " + e.getMessage(), e);
        }
    }

    /**
     * 查询集群状态。
     *
     * @param clusterId 集群 ID
     * @param tenantId  调用方租户 ID
     * @return 集群信息
     * @throws ClusterNotFoundException    集群不存在
     * @throws ClusterAccessDeniedException 跨租户访问
     */
    public ClusterInfo getClusterInfo(String clusterId, String tenantId) {
        ClusterInfo info = xinchangProvider.getClusterInfo(clusterId);
        if (info == null) {
            throw new ClusterNotFoundException("Cluster not found: " + clusterId);
        }
        ensureTenantMatch(info, tenantId);
        return info;
    }

    /**
     * 扩缩容集群。
     *
     * @param clusterId 集群 ID
     * @param tenantId  调用方租户 ID
     * @param request   扩缩容请求
     * @return 集群信息
     */
    public ClusterInfo scaleCluster(String clusterId, String tenantId, ClusterScaleRequest request) {
        ClusterInfo info = xinchangProvider.getClusterInfo(clusterId);
        if (info == null) {
            throw new ClusterNotFoundException("Cluster not found: " + clusterId);
        }
        ensureTenantMatch(info, tenantId);
        try {
            return xinchangProvider.scaleCluster(clusterId, request);
        } catch (Exception e) {
            throw new ClusterOperationException("Failed to scale cluster: " + e.getMessage(), e);
        }
    }

    /**
     * 列出租户的全部集群。
     *
     * @param tenantId 租户 ID
     * @return 集群列表
     */
    public List<ClusterInfo> listClusters(String tenantId) {
        return xinchangProvider.listClusters(tenantId);
    }

    private void ensureTenantMatch(ClusterInfo info, String tenantId) {
        if (!info.getTenantId().equals(tenantId)) {
            throw new ClusterAccessDeniedException(
                    "Tenant " + tenantId + " has no access to cluster " + info.getClusterId());
        }
    }

    /** 集群操作业务异常基类 */
    public static class ClusterOperationException extends RuntimeException {
        public ClusterOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 集群不存在 */
    public static class ClusterNotFoundException extends RuntimeException {
        public ClusterNotFoundException(String message) {
            super(message);
        }
    }

    /** 跨租户访问 */
    public static class ClusterAccessDeniedException extends RuntimeException {
        public ClusterAccessDeniedException(String message) {
            super(message);
        }
    }
}