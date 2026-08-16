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
 * 大模型微调任务实体（ROADMAP 前后端接线：/llmops/finetune）。
 *
 * <p>每次提交微调即一条记录，记录训练参数与运行状态。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "llm_finetune_task")
public class FinetuneTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务 ID（业务唯一，对齐前端 taskId）。 */
    @Column(nullable = false, length = 64, unique = true)
    private String taskId;

    /** 微调产出的模型名。 */
    @Column(nullable = false, length = 128)
    private String modelName;

    /** 基座模型名。 */
    @Column(nullable = false, length = 128)
    private String baseModel;

    /** 训练数据路径或标识。 */
    @Column(length = 512)
    private String trainingData;

    /** 显存/卡配置，如 2×GPU。 */
    @Column(length = 64)
    private String gpuConfig;

    /** 训练轮次。 */
    private Integer epochs;

    /** 状态：SUBMITTED/RUNNING/SUCCEEDED/FAILED。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 训练进度百分比（0-100）。 */
    private Integer progress;

    /** 训练日志路径。 */
    @Column(length = 512)
    private String logPath;

    /** 失败原因。 */
    @Column(length = 1024)
    private String errorMessage;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant submittedAt;

    private Instant startedAt;

    private Instant finishedAt;

    private Instant updatedAt;
}