package com.levango7.dataenginebdp.streambatch.plugin;

import com.levango7.dataenginebdp.streambatch.model.DagNode;
import com.levango7.dataenginebdp.streambatch.model.ExecutionStatus;
import com.levango7.dataenginebdp.streambatch.model.TaskExecutionResult;
import com.levango7.dataenginebdp.streambatch.model.TaskType;
import com.levango7.dataenginebdp.streambatch.flink.FlinkStreamSubmitter;
import com.levango7.dataenginebdp.streambatch.flink.FlinkSubmitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * Flink 流任务通道（DolphinScheduler TaskChannel 扩展）。
 *
 * <p>处理 {@link TaskType#FLINK_STREAM} 与 {@link TaskType#UNIFIED_STREAM_BATCH}
 * 节点的流部分：通过 {@link FlinkStreamSubmitter} 提交 Flink 流作业，
 * 读取 Iceberg 表最新 snapshot（streaming 模式）。
 */
@Slf4j
@RequiredArgsConstructor
public class FlinkStreamTaskChannel implements TaskChannel {

    private final FlinkStreamSubmitter submitter;

    @Override
    public String getChannelType() {
        return TaskType.FLINK_STREAM.getCode();
    }

    @Override
    public TaskExecutionResult execute(DagNode node) throws TaskExecutionException {
        Instant start = Instant.now();
        log.info("Flink 流通道执行节点: nodeId={}, table={}", node.getNodeId(), node.getIcebergTable());

        try {
            FlinkSubmitResult result = submitter.submitStream(
                    node.getIcebergTable(),
                    node.getMainResource(),
                    node.getMainClass(),
                    node.getTaskArgs(),
                    node.getParallelism());

            Instant end = Instant.now();
            return TaskExecutionResult.builder()
                    .nodeId(node.getNodeId())
                    .taskType(node.getTaskType())
                    .status(result.isSuccess() ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED)
                    .usedSnapshotId(result.getStartSnapshotId())
                    .jobId(result.getJobId())
                    .errorMessage(result.getErrorMessage())
                    .startTime(start)
                    .endTime(end)
                    .durationMs(end.toEpochMilli() - start.toEpochMilli())
                    .metadata(java.util.Map.of(
                            "submitPayload", result.getSubmitPayload() != null ? result.getSubmitPayload() : "",
                            "parallelism", String.valueOf(result.getParallelism())))
                    .build();
        } catch (Exception e) {
            Instant end = Instant.now();
            log.error("Flink 流通道执行失败: nodeId={}", node.getNodeId(), e);
            return TaskExecutionResult.builder()
                    .nodeId(node.getNodeId())
                    .taskType(node.getTaskType())
                    .status(ExecutionStatus.FAILED)
                    .errorMessage(e.getMessage())
                    .startTime(start)
                    .endTime(end)
                    .durationMs(end.toEpochMilli() - start.toEpochMilli())
                    .build();
        }
    }

    @Override
    public boolean cancel(String jobId) {
        return submitter.cancel(jobId);
    }
}