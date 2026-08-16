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
 * 数据集成同步任务实体（ROADMAP 前后端接线：/integrate）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sync_task")
public class SyncTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务名。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 源类型：mysql/kafka/doris/trino 等。 */
    @Column(nullable = false, length = 32)
    private String sourceType;

    /** 目标类型：iceberg/hudi/kafka 等。 */
    @Column(nullable = false, length = 32)
    private String targetType;

    /** 源表/主题。 */
    @Column(length = 255)
    private String sourceTable;

    /** 目标表。 */
    @Column(length = 255)
    private String targetTable;

    /** 调度表达式（cron）。 */
    @Column(length = 64)
    private String schedule;

    /** 状态：running/success/failed/pending。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** SeaTunnel 作业 ID（运行时由 SeaTunnelClient 写入，停止时使用）。 */
    @Column(length = 128)
    private String seatunnelJobId;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
