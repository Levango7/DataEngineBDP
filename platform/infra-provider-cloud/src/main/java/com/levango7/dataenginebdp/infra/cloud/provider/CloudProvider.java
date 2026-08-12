package com.levango7.dataenginebdp.infra.cloud.provider;

import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterInfo;
import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterRequest;

import java.util.List;

/**
 * 多云 VM 供应 Provider SPI 接口。
 *
 * <p>统一抽象华为云 ECS / 阿里云 ECS / 腾讯云 CVM 的 VM 生命周期管理。
 * 每朵云提供一个实现，通过 {@link #name()} 标识自身，由
 * {@code CloudProviderService} 路由分发。</p>
 *
 * <p>核心方法：</p>
 * <ul>
 *   <li>{@link #createVMs}：批量创建 VM（含安全组、EIP 配置）</li>
 *   <li>{@link #destroyVMs}：批量销毁 VM</li>
 *   <li>{@link #startVMs}：批量启动 VM</li>
 *   <li>{@link #stopVMs}：批量停止 VM</li>
 *   <li>{@link #getVMInfo}：查询 VM 详情</li>
 * </ul>
 *
 * <p>实现需保证：</p>
 * <ol>
 *   <li>幂等性：重复调用同一创建请求应返回相同结果或抛出明确冲突异常</li>
 *   <li>异常隔离：单台 VM 失败不应阻塞其他 VM，但需在响应中标记 ERROR</li>
 *   <li>资源回收：销毁 VM 时同步释放 EIP、数据盘等附属资源</li>
 * </ol>
 */
public interface CloudProvider {

    /**
     * Provider 标识。
     *
     * @return provider 名称，与 REST 路径变量 {@code {provider}} 对齐：huawei / ali / tencent
     */
    String name();

    /**
     * 批量创建 VM。
     *
     * <p>典型流程：创建 ECS/CVM 实例 → 配置安全组 → 分配 EIP/公网 IP → 返回 VM 信息。
     * K8s 引导由 {@code K8sBootstrapService} 在 VM 创建完成后单独执行。</p>
     *
     * @param clusterId  平台内部集群 ID（用于 VM 名称/标签生成）
     * @param request    集群创建请求
     * @return 集群信息（含每台 VM 的实例 ID、IP、状态）
     * @throws CloudProviderException 云 API 调用失败
     */
    CloudClusterInfo createVMs(String clusterId, CloudClusterRequest request);

    /**
     * 批量销毁 VM。
     *
     * <p>同步释放 EIP、数据盘等附属资源，确保不产生残留计费。</p>
     *
     * @param clusterId 集群 ID
     * @return 销毁后的集群信息（status=DELETED）
     * @throws CloudProviderException 云 API 调用失败
     */
    CloudClusterInfo destroyVMs(String clusterId);

    /**
     * 批量启动 VM。
     *
     * @param clusterId 集群 ID
     * @return 集群信息（status=RUNNING）
     * @throws CloudProviderException 云 API 调用失败
     */
    CloudClusterInfo startVMs(String clusterId);

    /**
     * 批量停止 VM。
     *
     * @param clusterId 集群 ID
     * @return 集群信息（status=STOPPED）
     * @throws CloudProviderException 云 API 调用失败
     */
    CloudClusterInfo stopVMs(String clusterId);

    /**
     * 查询 VM 详情。
     *
     * @param clusterId 集群 ID
     * @return 集群信息；若集群不存在返回 {@code null}
     * @throws CloudProviderException 云 API 调用失败
     */
    CloudClusterInfo getVMInfo(String clusterId);

    /**
     * 扩缩容：调整集群节点数到目标值。
     *
     * @param clusterId       集群 ID
     * @param targetNodeCount 目标节点数
     * @return 集群信息
     * @throws CloudProviderException 云 API 调用失败
     */
    CloudClusterInfo scaleVMs(String clusterId, int targetNodeCount);

    /**
     * 云 Provider 异常。
     *
     * <p>封装三朵云 SDK 抛出的运行时异常，统一对外暴露。</p>
     */
    class CloudProviderException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public CloudProviderException(String message) {
            super(message);
        }

        public CloudProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}