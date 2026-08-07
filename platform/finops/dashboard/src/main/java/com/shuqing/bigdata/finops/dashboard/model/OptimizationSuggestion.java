package com.shuqing.bigdata.finops.dashboard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 优化建议。
 *
 * <p>由优化建议引擎生成，针对一个或多个闲置资源给出可操作的优化动作。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptimizationSuggestion {

    /** 建议 ID */
    private String id;

    /** 建议标题 */
    private String title;

    /** 闲置模式 */
    private IdlePattern pattern;

    /** 优化动作类型（SCALE_DOWN / RELEASE / SHARE / MERGE / KEEP） */
    private String actionType;

    /** 涉及资源 ID 列表 */
    private List<String> resourceIds;

    /** 涉及资源数量 */
    int resourceCount;

    /** 估算可节约成本（元/月） */
    private double estimatedMonthlySaving;

    /** 优化建议详细描述 */
    private String description;

    /** 风险等级（LOW / MEDIUM / HIGH） */
    private String riskLevel;

    /** 涉及租户 */
    private String tenant;

    /** 涉及 namespace */
    private String namespace;

    /** 生成时间 */
    private Instant generatedAt;
}