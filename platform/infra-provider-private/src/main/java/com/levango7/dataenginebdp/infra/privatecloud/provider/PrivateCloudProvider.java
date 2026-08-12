package com.levango7.dataenginebdp.infra.privatecloud.provider;

import com.levango7.dataenginebdp.infra.privatecloud.model.PrivateClusterInfo;
import com.levango7.dataenginebdp.infra.privatecloud.model.PrivateClusterRequest;
import com.levango7.dataenginebdp.infra.privatecloud.model.VMSpec;

import java.util.List;

/**
 * 私有云 VM 供应 Provider SPI 接口。
 *
 * <p>统一抽象 vSphere、OpenStack 等私有云底座的 VM 供应能力，
 * 由各具体 Provider（{@code VSphereProvider}、{@code OpenStackProvider}）实现。
 * Controller 通过 {@code provider} 路径变量路由到对应实现。</p>
 *
 * <p>核心方法：</p>
 * <ul>
 *   <li>{@link #createVMs}：按 {@link PrivateClusterRequest} 创建一组 VM（控制面 + 工作节点）；</li>
 *   <li>{@link #destroyVMs}：销毁指定集群的所有 VM；</li>
 *   <li>{@link #getVMInfo}：查询指定集群的 VM 实时信息；</li>
 *   <li>{@link #scaleVMs}：扩缩容工作节点。</li>
 * </ul>
 *
 * <p>实现类需以 {@code @Component("vsphereProvider")} / {@code @Component("openstackProvider")}
 * 形式注册，Controller 通过 Bean 名称 + Map 注入按 {@code provider} 路径变量路由。</p>
 *
 * @author shuqing-bigdata
 */
public interface PrivateCloudProvider {

    /**
     * 返回该 Provider 的类型标识，与 REST 路径变量 {@code provider} 对齐。
     *
     * @return 类型标识，例如 {@code "vsphere"} / {@code "openstack"}
     */
    String getType();

    /**
     * 创建一组 VM。
     *
     * <p>按 {@link PrivateClusterRequest} 创建控制面 + 工作节点 VM，
     * 等待电源状态稳定并返回 VM 信息。该方法仅负责 VM 供应，
     * K8s 引导由 {@code K8sBootstrapService} 在 VM 就绪后单独执行。</p>
     *
     * @param request 集群创建请求
     * @return 已创建 VM 的信息列表（含 VM ID、名称、IP 等）
     */
    List<PrivateClusterInfo.VMInfo> createVMs(PrivateClusterRequest request);

    /**
     * 销毁一组 VM。
     *
     * <p>按 {@link PrivateClusterInfo#getVms()} 中的 VM ID 列表逐一销毁，
     * 失败的 VM 记入日志但继续销毁其余，确保最大程度回收资源。</p>
     *
     * @param cluster 待销毁集群信息（含 VM 列表）
     * @return 是否全部销毁成功
     */
    boolean destroyVMs(PrivateClusterInfo cluster);

    /**
     * 查询 VM 实时信息。
     *
     * <p>从云平台实时拉取 VM 电源状态、IP 地址等信息，
     * 用于 {@code GET /api/v1/clusters/private/{provider}/{id}} 端点。</p>
     *
     * @param cluster 集群信息（含 VM ID 列表）
     * @return VM 实时信息列表
     */
    List<PrivateClusterInfo.VMInfo> getVMInfo(PrivateClusterInfo cluster);

    /**
     * 扩缩容工作节点。
     *
     * <p>当 {@code targetWorkerCount > 当前 worker 数} 时按 {@code workerSpec} 新增 worker VM；
     * 当 {@code targetWorkerCount < 当前 worker 数} 时按 LRU 顺序销毁多余 worker VM。</p>
     *
     * @param cluster           集群信息（含现有 VM 列表）
     * @param targetWorkerCount 目标工作节点数
     * @param workerSpec        新增 worker 规格（扩容时使用；缩容可传 null）
     * @return 变更后的 VM 信息列表
     */
    List<PrivateClusterInfo.VMInfo> scaleVMs(PrivateClusterInfo cluster,
                                             int targetWorkerCount,
                                             VMSpec workerSpec);
}