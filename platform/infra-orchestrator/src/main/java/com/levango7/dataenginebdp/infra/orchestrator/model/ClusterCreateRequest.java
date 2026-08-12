package com.levango7.dataenginebdp.infra.orchestrator.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 跨环境统一集群创建请求。
 *
 * <p>L0.5 编排层对上暴露的唯一集群创建请求模型，{@code environment} 字段决定路由到
 * 哪个下游 Provider。请求体保持环境无关，环境特定参数通过 {@code providerParams}
 * 透传给下游 Provider。</p>
 *
 * <p>对应 REST API：{@code POST /api/v1/clusters}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterCreateRequest {

    /**
     * 目标环境类型，决定路由到哪个下游 Provider。必填。
     */
    @NotNull
    private EnvironmentType environment;

    /**
     * 集群名称，全局唯一，将作为 K8s Cluster 名称与资源标签。
     */
    @NotBlank
    @Size(min = 3, max = 63)
    private String clusterName;

    /**
     * 租户 ID（由 JWT 注入，用于多租户隔离）。
     */
    @NotBlank
    private String tenantId;

    /**
     * K8s 版本，默认 v1.28.9。
     */
    @NotBlank
    @Builder.Default
    private String k8sVersion = "v1.28.9";

    /**
     * Pod CIDR，默认 10.244.0.0/16。
     */
    @NotBlank
    @Builder.Default
    private String podCidr = "10.244.0.0/16";

    /**
     * Service CIDR，默认 10.96.0.0/12。
     */
    @NotBlank
    @Builder.Default
    private String serviceCidr = "10.96.0.0/12";

    /**
     * 节点规格列表，至少 1 个节点。
     * 每个节点规格对齐各 Provider 的 NodeSpec 模型。
     */
    @NotEmpty
    @Valid
    private List<NodeSpec> nodes;

    /**
     * 是否启用 SKE 定制配置（仅信创环境生效，默认 false）。
     */
    @Builder.Default
    private boolean skeEnabled = false;

    /**
     * 备注/描述。
     */
    private String description;

    /**
     * Provider 特定参数透传字段，key/value 均为字符串。
     * 例如公有云的 {@code region} / {@code zone} / {@code instanceType}，
     * 私有云的 {@code datacenter} / {@code cluster}，信创的 {@code cpuArch} / {@code osType}。
     */
    private Map<String, String> providerParams;

    /**
     * 统一节点规格。
     *
     * <p>对齐四环境 Provider 的节点模型，编排层透传给下游。</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeSpec {

        /** 节点角色：{@code control-plane} / {@code worker}。 */
        @NotBlank
        private String role;

        /** 节点数量。 */
        @Positive
        @Builder.Default
        private int count = 1;

        /** CPU 核数。 */
        @Positive
        @Builder.Default
        private int cpuCores = 8;

        /** 内存（GB）。 */
        @Positive
        @Builder.Default
        private int memoryGb = 32;

        /** 系统盘（GB）。 */
        @Positive
        @Builder.Default
        private int diskGb = 200;

        /** CPU 架构：{@code amd64} / {@code arm64}，仅物理机环境生效。 */
        @Builder.Default
        private String cpuArch = "amd64";

        /** OS 类型，仅物理机环境生效。 */
        private String osType;

        /** BMC IP，仅物理机环境生效。 */
        private String bmcIp;

        /** PXE MAC 地址，仅物理机环境生效。 */
        private String pxeMac;

        /** 云主机规格 ID，仅公有云/私有云生效。如 {@code cce.s2.large}。 */
        private String instanceType;

        /** 可用区，仅公有云生效。 */
        private String zone;
    }
}