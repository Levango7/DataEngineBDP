package com.levango7.dataenginebdp.streambatch.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 任务执行结果。
 *
 * <p>记录单个 DAG 节点（Spark 批 / Flink 流）的执行状态、
 * 实际使用的 snapshot-id、作业 ID 等信息，用于 snapshot 隔离验证。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskExecutionResult {

    /** 节点 ID。 */
    private String nodeId;

    /** 任务类型。 */
    private TaskType taskType;

    /** 执行状态。 */
    private ExecutionStatus status;

    /** 实际使用的 Iceberg snapshot-id（批节点固定值；流节点为启动时最新值）。 */
    private Long usedSnapshotId;

    /** 作业 ID（Spark appId / Flink jobId）。 */
    private String jobId;

    /** 错误信息（失败时填充）。 */
    private String errorMessage;

    /** 开始时间。 */
    private Instant startTime;

    /** 结束时间。 */
    private Instant endTime;

    /** 执行耗时毫秒。 */
    private long durationMs;

    /** 执行结果元数据（透传给上层）。 */
    @Builder.Default
    private java.util.Map<String, String> metadata = new java.util.HashMap<>();

    /**
     * 判断执行是否成功。
     *
     * @return {@code true} 表示成功
     */
    public boolean isSuccess() {
        return status == ExecutionStatus.SUCCESS;
    }
}