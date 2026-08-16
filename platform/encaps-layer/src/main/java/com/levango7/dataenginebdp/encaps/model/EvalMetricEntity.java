package com.levango7.dataenginebdp.encaps.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 大模型评估指标实体（ROADMAP 前后端接线：/llmops/eval-metrics）。
 *
 * <p>每个模型每次评估即一条记录，记录准确率、幻觉率等关键指标。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "llm_eval_metric")
public class EvalMetricEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模型名。 */
    @Column(nullable = false, length = 128)
    private String modelName;

    /** 模型版本。 */
    @Column(length = 64)
    private String modelVersion;

    /** 评估类型：auto/human。 */
    @Column(nullable = false, length = 16)
    private String evalType;

    /** 准确率（0-1）。 */
    private Double accuracy;

    /** 幻觉率（0-1）。 */
    private Double hallucinationRate;

    /** 对比基座提升（百分点）。 */
    private Double baseLiftPt;

    /** 评估数据集。 */
    @Column(length = 255)
    private String dataset;

    /** 评估详情 JSON。 */
    @Column(length = 4096)
    private String detailJson;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}