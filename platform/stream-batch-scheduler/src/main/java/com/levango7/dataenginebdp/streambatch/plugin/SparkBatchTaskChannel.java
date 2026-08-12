package com.levango7.dataenginebdp.streambatch.plugin;

import com.levango7.dataenginebdp.streambatch.model.DagNode;
import com.levango7.dataenginebdp.streambatch.model.ExecutionStatus;
import com.levango7.dataenginebdp.streambatch.model.TaskExecutionResult;
import com.levango7.dataenginebdp.streambatch.model.TaskType;
import com.levango7.dataenginebdp.streambatch.spark.SparkBatchSubmitter;
import com.levango7.dataenginebdp.streambatch.spark.SparkSubmitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * Spark 批任务通道（DolphinScheduler TaskChannel 扩展）。
 *
 * <p>处理 {@link TaskType#SPARK_BATCH} 与 {@link TaskType#UNIFIED_STREAM_BATCH}
 * 节点的批部分：通过 {@link SparkBatchSubmitter} 提交 Spark 批作业，
 * 读取 Iceberg 表固定 snapshot。
 */
@Slf4j
@RequiredArgsConstructor
public class SparkBatchTaskChannel implements TaskChannel {

    private final SparkBatchSubmitter submitter;

    @Override
    public String getChannelType() {
        return TaskType.SPARK_BATCH.getCode();
    }

    @Override
    public TaskExecutionResult execute(DagNode node) throws TaskExecutionException {
        Instant start = Instant.now();
        log.info("Spark 批通道执行节点: nodeId={}, table={}", node.getNodeId(), node.getIcebergTable());

        try {
            SparkSubmitResult result = submitter.submitBatch(
                    node.getIcebergTable(),
                    node.getMainResource(),
                    node.getMainClass(),
                    node.getTaskArgs(),
                    node.getSnapshotId());

            Instant end = Instant.now();
            return TaskExecutionResult.builder()
                    .nodeId(node.getNodeId())
                    .taskType(node.getTaskType())
                    .status(result.isSuccess() ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED)
                    .usedSnapshotId(result.getSnapshotId())
                    .jobId(result.getAppId())
                    .errorMessage(result.getErrorMessage())
                    .startTime(start)
                    .endTime(end)
                    .durationMs(end.toEpochMilli() - start.toEpochMilli())
                    .metadata(java.util.Map.of(
                            "submitCommand", result.getSubmitCommand() != null ? result.getSubmitCommand() : ""))
                    .build();
        } catch (Exception e) {
            Instant end = Instant.now();
            log.error("Spark 批通道执行失败: nodeId={}", node.getNodeId(), e);
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