package com.levango7.dataenginebdp.streambatch.run;

import com.levango7.dataenginebdp.streambatch.model.ExecutionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DAG 单次运行实例（运行历史记录）。
 *
 * <p>由 {@code StreamBatchOrchestrationService} 在 DAG 执行完成时写入，
 * 支撑任务运维中心的"运行历史 / 失败重跑 / 补数据"能力。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "dag_run", indexes = {
        @Index(name = "idx_dag_run_dag_id", columnList = "dagId"),
        @Index(name = "idx_dag_run_status", columnList = "status"),
        @Index(name = "idx_dag_run_biz_time", columnList = "bizTime")
})
public class DagRunEntity {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属 DAG ID（对应 {@code StreamBatchDag.dagId}）。 */
    @Column(nullable = false, length = 128)
    private String dagId;

    /** DAG 快照（提交时的完整 DAG JSON，供重跑/补数据复原参数）。 */
    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String dagSnapshot;

    /** 运行类型。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DagRunType runType;

    /** 整体执行状态。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExecutionStatus status;

    /** 业务时间（补数据实例对应的数据日期；普通实例为空）。 */
    private Instant bizTime;

    /** 触发人 / 触发来源。 */
    @Column(length = 128)
    private String triggeredBy;

    /** 基于哪个 runId 重跑（runType=RERUN 时有值）。 */
    private Long sourceRunId;

    /** 开始时间。 */
    private Instant startTime;

    /** 结束时间。 */
    private Instant endTime;

    /** 总耗时毫秒。 */
    private Long durationMs;

    /** 各节点执行结果（JSON 序列化）。 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String nodeResultsJson;

    /** 失败原因（status=FAILED 时有值）。 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String errorMessage;

    /** 记录创建时间。 */
    @Column(nullable = false)
    private Instant createdAt;
}
