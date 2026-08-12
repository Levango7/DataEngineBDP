package com.levango7.dataenginebdp.finops.dashboard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 闲置资源清单项。
 *
 * <p>表示被识别为闲置的资源，含闲置模式、利用率指标与优化建议。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdleResource {

    /** 资源 ID */
    private String resourceId;

    /** 资源类型（POD / VM / PVC / GPU / NODE） */
    private String resourceType;

    /** 租户 ID */
    private String tenant;

    /** namespace */
    private String namespace;

    /** 工作空间 */
    private String workspace;

    /** 闲置模式（5 类之一） */
    private IdlePattern pattern;

    /** 平均利用率（百分比，0-100） */
    private double avgUtilization;

    /** 持续时长（小时） */
    private double sustainedHours;

    /** 估算可节约成本（元/月） */
    private double estimatedSaving;

    /** 优化建议（人类可读） */
    private String suggestion;

    /** 检测窗口起始时间 */
    private Instant start;

    /** 检测窗口结束时间 */
    private Instant end;
}