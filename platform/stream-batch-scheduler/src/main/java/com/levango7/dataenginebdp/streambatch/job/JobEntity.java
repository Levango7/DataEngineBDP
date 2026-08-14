package com.levango7.dataenginebdp.streambatch.job;

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
 * 作业元数据实体（ROADMAP 前后端接线：/jobs）。
 *
 * <p>前端提交的作业（workspaceId/name/type/config）持久化于此，
 * 运行/取消通过 {@link JobSubmitService} 转换为 DAG 提交到 scheduler。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sb_job")
public class JobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 作业名称。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 工作空间 ID。 */
    @Column(nullable = false, length = 64)
    private String workspaceId;

    /** 作业类型：spark/flink/sql/dag。 */
    @Column(nullable = false, length = 32)
    private String type;

    /** 作业配置（JSON 字符串，前端 SubmitJobParams.config）。 */
    @Column(length = 4096)
    private String config;

    /** 调度表达式（cron）。 */
    @Column(length = 64)
    private String schedule;

    /** 负责人。 */
    @Column(length = 128)
    private String owner;

    /** 状态：draft/active/paused。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 最近一次运行状态（从 DagRun 回写）。 */
    @Column(length = 16)
    private String lastRunStatus;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
