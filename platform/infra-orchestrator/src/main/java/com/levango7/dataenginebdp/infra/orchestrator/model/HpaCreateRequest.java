package com.levango7.dataenginebdp.infra.orchestrator.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HPA 创建/更新请求。
 *
 * <p>对应 REST API：</p>
 * <ul>
 *   <li>{@code POST /api/v1/clusters/{environment}/{clusterId}/hpa}      - 创建</li>
 *   <li>{@code PUT  /api/v1/clusters/{environment}/{clusterId}/hpa/{name}} - 更新（min/max 等）</li>
 * </ul>
 *
 * <p>字段契约对齐前端 {@code frontend/src/api/infra.ts} 的 {@code HpaPolicy} 接口
 * （InfraSched.vue {@code buildHpaPolicy()} 提交的 JSON 形状）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HpaCreateRequest {

    /**
     * HPA 名称（K8s RFC1123 名称）。
     */
    @NotBlank
    private String name;

    /**
     * 目标命名空间，缺省由前端传入 "default"。
     */
    @NotBlank
    private String namespace;

    /**
     * 目标 Deployment 名称（scaleTargetRef）。
     */
    @NotBlank
    private String targetDeployment;

    /**
     * 最小副本数。
     */
    @NotNull
    @Min(1)
    private Integer minReplicas;

    /**
     * 最大副本数。
     */
    @NotNull
    @Positive
    private Integer maxReplicas;

    /**
     * CPU 利用率阈值（百分比，1-100），映射 autoscaling/v2 ResourceMetricSource。
     */
    @NotNull
    @Min(1)
    private Integer cpuThreshold;

    /**
     * 内存利用率阈值（百分比，可选；不传或 0 表示不配置内存指标）。
     */
    @Positive
    private Integer memoryThreshold;
}
