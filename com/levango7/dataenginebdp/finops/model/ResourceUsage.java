package com.shuqing.bigdata.finops.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 资源用量数据模型。
 *
 * <p>表示某租户在某 namespace 下某时间窗口内的某维度资源用量。
 * 采集器从 Prometheus TSDB 查询并填充本对象，作为成本计算的输入。</p>
 *
 * <p>标签隔离：通过 {@code tenant} 与 {@code namespace} 字段实现租户间数据不可见。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceUsage {

    /** 租户 ID（来自 Prometheus 标签 tenant） */
    @NotBlank(message = "租户 ID 不能为空")
    private String tenant;

    /** Kubernetes namespace（来自 Prometheus 标签 namespace） */
    @NotBlank(message = "namespace 不能为空")
    private String namespace;

    /** 资源维度 */
    @NotNull(message = "资源维度不能为空")
    private ResourceDimension dimension;

    /** 用量数值（单位由维度决定：CPU 核时、内存/存储 GB·时、GPU 卡时、网络 GB） */
    @PositiveOrZero(message = "用量必须非负")
    private double amount;

    /** GPU 型号（仅当 dimension=GPU 时有意义，如 A100/V100/Ascend910） */
    private String gpuModel;

    /** 采集窗口起始时间（UTC） */
    @NotNull
    private Instant start;

    /** 采集窗口结束时间（UTC） */
    @NotNull
    private Instant end;
}