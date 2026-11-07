package com.levango7.dataenginebdp.infra.orchestrator.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * NetworkPolicy 创建请求。
 *
 * <p>对应 REST API：{@code POST /api/v1/clusters/{environment}/{clusterId}/network/policies}。</p>
 *
 * <p>字段契约对齐前端 {@code frontend/src/api/infra.ts} 的 {@code NetworkPolicy} 接口
 * （InfraNet.vue 创建表单提交的 JSON 形状）：{@code name} / {@code namespace} /
 * {@code type}（ingress|egress|both）/ {@code ports}（端口号数组）/ {@code selector}
 * （Pod 选择器，格式 {@code key=value}，逗号分隔多组）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NetworkPolicyCreateRequest {

    /**
     * NetworkPolicy 名称（K8s RFC1123 名称）。
     */
    @NotBlank
    private String name;

    /**
     * 目标命名空间，缺省由前端传入 "default"。
     */
    @NotBlank
    private String namespace;

    /**
     * 策略类型：ingress / egress / both。
     *
     * <p>both 对应 K8s 原生 {@code policyTypes: [Ingress, Egress]}。
     * 非法值由 Controller 拒绝（400）。</p>
     */
    @NotBlank
    private String type;

    /**
     * 端口号列表（TCP，1-65535）。空列表表示不限制端口（规则缺省全部端口）。
     */
    private List<@NotNull @Positive Integer> ports;

    /**
     * Pod 选择器字符串，如 {@code app=web} 或 {@code app=web,tier=frontend}；
     * 解析为 K8s {@code LabelSelector.matchLabels}。
     */
    private String selector;
}
