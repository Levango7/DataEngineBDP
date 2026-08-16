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
 * 机器学习模型注册实体（ROADMAP 前后端接线：/ml/models）。
 *
 * <p>每次注册一个模型版本即一条记录；同一 {@link #name} 可对应多个版本。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ml_model")
public class MlModelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模型名（同一模型多版本共享）。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 算法：xgboost/lightgbm/tensorflow/pytorch/sklearn/sparkml/huggingface 等。 */
    @Column(nullable = false, length = 32)
    private String algorithm;

    /** 版本号。 */
    @Column(nullable = false, length = 64)
    private String version;

    /** 状态：DRAFT/REGISTERED/DEPLOYED/ARCHIVED/FAILED。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 评估指标 JSON（如 {"accuracy":0.95}）。 */
    @Column(length = 4096)
    private String metricsJson;

    /** 来源训练作业 ID。 */
    @Column(length = 64)
    private String trainJobId;

    /** 模型存储路径。 */
    @Column(length = 512)
    private String modelPath;

    /** 描述。 */
    @Column(length = 512)
    private String description;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant registeredAt;

    private Instant updatedAt;
}