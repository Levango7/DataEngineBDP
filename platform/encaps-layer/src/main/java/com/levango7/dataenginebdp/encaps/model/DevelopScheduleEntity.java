package com.levango7.dataenginebdp.encaps.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 数据开发调度任务实体（Web IDE 提交的 cron 调度）。
 *
 * <p>对应前端 {@code develop.ts} 的 {@code submitSchedule}，
 * 持久化于 {@code develop_schedule} 表，按租户隔离。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "develop_schedule", indexes = {
        @Index(name = "idx_develop_schedule_tenant", columnList = "tenantId"),
        @Index(name = "idx_develop_schedule_file", columnList = "filePath")
})
public class DevelopScheduleEntity {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 作业文件路径（工作空间相对路径）。 */
    @Column(nullable = false, length = 512)
    private String filePath;

    /** 调度表达式（cron）。 */
    @Column(nullable = false, length = 64)
    private String schedule;

    /** 执行引擎：spark/flink/trino/doris。 */
    @Column(nullable = false, length = 32)
    private String engine;

    /** 状态：active/paused。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    /** 上游依赖文件路径列表（JSON 数组字符串，用于 DAG 解析）。 */
    @Column(length = 2048)
    private String dependencies;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}