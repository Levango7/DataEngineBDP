package com.levango7.dataenginebdp.infra.xinchang.provider;

import com.levango7.dataenginebdp.infra.xinchang.model.ClusterCreateRequest;
import com.levango7.dataenginebdp.infra.xinchang.model.ClusterInfo;
import com.levango7.dataenginebdp.infra.xinchang.model.ClusterScaleRequest;

import java.util.List;

/**
 * 集群供应 Provider SPI 接口。
 *
 * <p>定义资源供应层的统一契约，不同基础设施（信创/云/边缘）实现该接口，
 * 由封装层（L0.11）按 providerType 路由调用。</p>
 *
 * <p>当前已知实现：</p>
 * <ul>
 *   <li>{@code xinchang} - {@link XinchangProvider}，国产 CPU + 国产 OS + IPMI/PXE</li>
 * </ul>
 *
 * <p>未来扩展：{@code cloud-aliyun}、{@code cloud-huawei}、{@code edge-k3s} 等。</p>
 */
public interface ClusterProvider {

    /**
     * Provider 类型标识，用于 SPI 路由。
     *
     * @return 类型字符串，例如 {@code "xinchang"}
     */
    String providerType();

    /**
     * 创建集群。
     *
     * <p>完整流程：IPMI 开机 → PXE 装机 → kubeadm init → join worker → 回填 ClusterInfo。</p>
     *
     * @param request 创建请求
     * @return 集群运行态信息（status=CREATING/RUNNING/FAILED）
     */
    ClusterInfo createCluster(ClusterCreateRequest request);

    /**
     * 销毁集群。
     *
     * <p>完整流程：drain 节点 → reset kubeadm → IPMI 关机 → 释放元数据。</p>
     *
     * @param clusterId 集群 ID
     * @return 销毁后的集群信息（status=DESTROYED）
     */
    ClusterInfo destroyCluster(String clusterId);

    /**
     * 扩缩容集群。
     *
     * @param clusterId 集群 ID
     * @param request   扩缩容请求
     * @return 扩缩容后的集群信息（status=SCALING/RUNNING/FAILED）
     */
    ClusterInfo scaleCluster(String clusterId, ClusterScaleRequest request);

    /**
     * 查询集群状态。
     *
     * @param clusterId 集群 ID
     * @return 集群运行态信息；不存在返回 {@code null}
     */
    ClusterInfo getClusterInfo(String clusterId);

    /**
     * 列出指定租户的全部集群。
     *
     * @param tenantId 租户 ID
     * @return 集群信息列表
     */
    List<ClusterInfo> listClusters(String tenantId);
}