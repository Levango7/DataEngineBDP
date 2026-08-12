package com.levango7.dataenginebdp.infra.privatecloud.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 私有云 K8s 集群创建请求。
 *
 * <p>由 REST API {@code POST /api/v1/clusters/private/{provider}} 接收，
 * 传给对应 {@code PrivateCloudProvider} 创建 VM 并引导 K8s 集群。</p>
 *
 * <p>字段说明：</p>
 * <ul>
 *   <li>{@code clusterName}：集群名，对应 K8s Cluster 名称与 VM 名称前缀；</li>
 *   <li>{@code controlPlane}：控制面 VM 规格；</li>
 *   <li>{@code workers}：工作节点 VM 规格列表，列表大小即工作节点数；</li>
 *   <li>{@code k8sVersion}：可选，覆盖默认 K8s 版本；</li>
 *   <li>{@code podCidr}/{@code serviceCidr}：可选，覆盖默认 CIDR。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrivateClusterRequest {

    /** 集群名称（也是 VM 名称前缀） */
    @NotBlank(message = "clusterName 不能为空")
    private String clusterName;

    /** 控制面 VM 规格 */
    @NotNull(message = "controlPlane 不能为空")
    private VMSpec controlPlane;

    /** 工作节点 VM 规格列表，列表大小即工作节点数 */
    @NotNull(message = "workers 不能为空")
    @Min(value = 1, message = "至少需要 1 个工作节点")
    private List<VMSpec> workers;

    /** K8s 版本，可选（为空使用默认 v1.30.0） */
    private String k8sVersion;

    /** Pod CIDR，可选 */
    private String podCidr;

    /** Service CIDR，可选 */
    private String serviceCidr;

    /** SSH 公钥（用于 cloud-init 注入，可选） */
    private String sshPublicKey;
}