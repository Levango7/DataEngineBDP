package com.shuqing.bigdata.finops.dashboard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 资源成本明细。
 *
 * <p>表示单个资源（Pod/VM/PVC/GPU 卡）在某时间窗口内的成本与用量明细，
 * 用于 Top10 看板、成本明细看板与账单导出。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceCostDetail {

    /** 资源 ID（如 pod-name / vm-id / pvc-name / gpu-id） */
    private String resourceId;

    /** 资源类型（POD / VM / PVC / GPU / NODE） */
    private String resourceType;

    /** 租户 ID */
    private String tenant;

    /** Kubernetes namespace */
    private String namespace;

    /** 工作空间标签（来自 K8s 标签 workspace） */
    private String workspace;

    /** 资源维度用量：dimension → 用量数值 */
    private Map<String, Double> dimensionUsages;

    /** 资源维度成本：dimension → 成本（元） */
    private Map<String, BigDecimal> dimensionCosts;

    /** 总成本（元，精度 0.0001） */
    private BigDecimal totalCost;

    /** GPU 型号（仅 GPU 资源有值，如 A100/V100/Ascend910） */
    private String gpuModel;

    /** 窗口起始时间（UTC） */
    private Instant start;

    /** 窗口结束时间（UTC） */
    private Instant end;
}