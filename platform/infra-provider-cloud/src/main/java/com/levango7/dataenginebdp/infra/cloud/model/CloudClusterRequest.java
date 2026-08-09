package com.levango7.dataenginebdp.infra.cloud.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 创建云集群请求。
 *
 * <p>统一描述三朵云的集群创建参数，由各 Provider 翻译为云原生 SDK 调用。
 * 集群由 N 台 VM 组成，第 0 台作为 K8s 控制面，其余作为工作节点。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudClusterRequest {

    /** 集群名称（K8s cluster name，需符合 DNS 子域名规范） */
    @NotBlank(message = "clusterName 不能为空")
    private String clusterName;

    /** 工作空间 ID（对应 K8s Namespace，ws- 前缀） */
    @NotBlank(message = "workspaceId 不能为空")
    private String workspaceId;

    /** 集群节点数（含控制面，最小 1） */
    @NotNull(message = "nodeCount 不能为空")
    @Min(value = 1, message = "nodeCount 至少 1")
    private Integer nodeCount;

    /** VM 规格定义 */
    @NotNull(message = "vmSpec 不能为空")
    @Valid
    private VMSpec vmSpec;

    /** 可用区 ID（可选，未指定时由云厂商默认选择） */
    private String availabilityZone;

    /** VPC ID（可选，未指定时使用 application.yml 中的默认值） */
    private String vpcId;

    /** 子网 ID（可选） */
    private String subnetId;

    /** 安全组 ID 列表（可选，未指定时使用默认安全组） */
    private List<String> securityGroupIds;

    /** 是否在 VM 创建完成后自动执行 K8s 引导（kubeadm + SKE） */
    @Builder.Default
    private boolean autoBootstrapK8s = true;

    /** K8s Pod CIDR（可选，默认 10.244.0.0/16） */
    private String podCidr;

    /** K8s Service CIDR（可选，默认 10.96.0.0/12） */
    private String serviceCidr;

    /** 云平台标签（透传到 VM 实例的 tag，便于成本分摊与筛选） */
    private List<String> tags;
}