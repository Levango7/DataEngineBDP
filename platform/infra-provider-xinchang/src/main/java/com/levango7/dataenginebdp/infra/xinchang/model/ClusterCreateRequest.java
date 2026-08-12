package com.levango7.dataenginebdp.infra.xinchang.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 信创集群创建请求。
 *
 * <p>对应 REST API：{@code POST /api/v1/clusters/xinchang}。
 * 由封装层（L0.11）或运营后台调用，描述一次集群供应的完整输入。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterCreateRequest {

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
     * K8s 版本，默认 v1.28.9（与 SKE v0.1 对齐）。
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
     * 节点规格列表，至少 1 个 control-plane 节点。
     */
    @NotEmpty
    @Valid
    private List<XinchangNodeSpec> nodes;

    /**
     * 是否启用 SKE 定制配置（默认 true）。
     */
    @Builder.Default
    private boolean skeEnabled = true;

    /**
     * 备注/描述。
     */
    private String description;
}