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
 * 推理服务实体（ROADMAP 前后端接线：/ml/inference-services）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ml_inference_service")
public class InferenceServiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 服务名。 */
    @Column(nullable = false, length = 128)
    private String serviceName;

    /** 模型名。 */
    @Column(nullable = false, length = 128)
    private String modelName;

    /** 模型版本。 */
    @Column(nullable = false, length = 64)
    private String modelVersion;

    /** 状态：DEPLOYING/RUNNING/STOPPED/FAILED/SCALING。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 实际副本数。 */
    @Column
    private Integer replicas;

    /** 期望副本数。 */
    @Column
    private Integer desiredReplicas;

    /** QPS。 */
    @Column
    private Double qps;

    /** 平均延迟（毫秒）。 */
    @Column
    private Double latencyMs;

    /** 端点 URL。 */
    @Column(length = 512)
    private String endpoint;

    /** 资源规格。 */
    @Column(length = 64)
    private String resourceSpec;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant deployedAt;

    private Instant updatedAt;
}