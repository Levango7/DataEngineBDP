package com.shuqing.bigdata.infra.xinchang.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 信创集群运行态信息。
 *
 * <p>对应 REST API：{@code GET /api/v1/clusters/xinchang/{clusterId}}。
 * 描述集群当前状态、节点清单与元数据，由 Provider 持续刷新。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterInfo {

    /**
     * 集群状态枚举。
     */
    public enum Status {
        /** 创建中：IPMI 开机 / PXE 装机 / K8s 初始化进行中 */
        CREATING,
        /** 运行中：所有 control-plane / worker Ready */
        RUNNING,
        /** 扩缩容中 */
        SCALING,
        /** 销毁中： draining / 关机 / 释放 */
        DESTROYING,
        /** 已销毁：仅保留元数据 */
        DESTROYED,
        /** 失败：装机或初始化失败，需人工介入 */
        FAILED
    }

    /**
     * 集群 ID（UUID）。
     */
    private String clusterId;

    /**
     * 集群名称。
     */
    private String clusterName;

    /**
     * 租户 ID。
     */
    private String tenantId;

    /**
     * K8s 版本。
     */
    private String k8sVersion;

    /**
     * 集群当前状态。
     */
    private Status status;

    /**
     * control-plane 端点 VIP（kubeadm init 完成后填充）。
     */
    private String controlPlaneEndpoint;

    /**
     * 节点信息列表，每项格式：{@code hostname|role|cpuArch|osType|bmcIp|pxeMac|nodeStatus}。
     */
    private List<String> nodes;

    /**
     * 元数据：K8s kubeconfig / 证书指纹 / 创建耗时等。
     */
    private Map<String, String> metadata;

    /**
     * 创建时间。
     */
    private Instant createdAt;

    /**
     * 最近更新时间。
     */
    private Instant updatedAt;

    /**
     * 错误信息（status=FAILED 时填充）。
     */
    private String errorMessage;
}