package com.levango7.dataenginebdp.infra.cloud.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 云集群信息（查询/创建后返回）。
 *
 * <p>统一描述三朵云的集群状态，由各 Provider 从云原生 API 响应翻译而来。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudClusterInfo {

    /** 集群 ID（平台内部 ID，对应 CloudClusterEntity.id） */
    private String clusterId;

    /** 集群名称 */
    private String clusterName;

    /** 云 provider 标识（huawei / ali / tencent） */
    private String provider;

    /** 工作空间 ID */
    private String workspaceId;

    /** 集群状态：CREATING / RUNNING / STOPPED / DELETING / DELETED / ERROR */
    private String status;

    /** 节点 VM 信息列表 */
    private List<VMInfo> nodes;

    /** K8s 控制面 API Server 端点（公网 IP:6443） */
    private String k8sApiServerEndpoint;

    /** K8s 引导状态：PENDING / BOOTSTRAPPING / READY / FAILED */
    private String k8sBootstrapStatus;

    /** 创建时间 */
    private Instant createdAt;

    /** 更新时间 */
    private Instant updatedAt;

    /** 错误信息（status=ERROR 时填充） */
    private String errorMessage;

    /**
     * 单台 VM 信息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VMInfo {
        /** VM 实例 ID（云原生 ID） */
        private String instanceId;
        /** 实例名称 */
        private String instanceName;
        /** 内网 IP */
        private String privateIp;
        /** 公网 IP / EIP */
        private String publicIp;
        /** VM 状态：RUNNING / STOPPED / CREATING / DELETED / ERROR */
        private String status;
        /** 是否为控制面节点 */
        private boolean controlPlane;
        /** 可用区 */
        private String availabilityZone;
    }
}