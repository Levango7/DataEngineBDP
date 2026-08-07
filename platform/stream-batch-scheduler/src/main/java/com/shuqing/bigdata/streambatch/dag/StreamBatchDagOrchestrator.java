package com.shuqing.bigdata.streambatch.dag;

import com.shuqing.bigdata.streambatch.iceberg.IcebergSnapshotManager;
import com.shuqing.bigdata.streambatch.iceberg.SnapshotIsolationConfig;
import com.shuqing.bigdata.streambatch.iceberg.SnapshotIsolationResult;
import com.shuqing.bigdata.streambatch.model.DagExecutionResult;
import com.shuqing.bigdata.streambatch.model.DagNode;
import com.shuqing.bigdata.streambatch.model.ExecutionStatus;
import com.shuqing.bigdata.streambatch.model.StreamBatchDag;
import com.shuqing.bigdata.streambatch.model.TaskExecutionResult;
import com.shuqing.bigdata.streambatch.model.TaskType;
import com.shuqing.bigdata.streambatch.plugin.SparkBatchTaskChannel;
import com.shuqing.bigdata.streambatch.plugin.FlinkStreamTaskChannel;
import com.shuqing.bigdata.streambatch.plugin.TaskChannel;
import com.shuqing.bigdata.streambatch.plugin.TaskExecutionException;
import com.shuqing.bigdata.streambatch.spark.SparkBatchSubmitter;
import com.shuqing.bigdata.streambatch.flink.FlinkStreamSubmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流批统一 DAG 编排器（核心组件）。
 *
 * <p>扩展 DolphinScheduler DAG 调度引擎，支持同一 DAG 内同时编排
 * Spark 批节点与 Flink 流节点，通过 Iceberg snapshot 隔离保证批流数据一致。
 *
 * <p>编排流程：
 * <ol>
 *   <li><b>校验 DAG</b> — 节点完整性、无环、依赖合法</li>
 *   <li><b>拓扑排序</b> — 计算节点执行顺序（批节点先于依赖它的流节点）</li>
 *   <li><b>按序执行</b> — 根据节点类型选择 TaskChannel（Spark 批 / Flink 流）</li>
 *   <li><b>snapshot 隔离</b> — 批节点锁定固定 snapshot，流节点读最新 snapshot</li>
 *   <li><b>隔离验证</b> — DAG 执行完成后验证批流 snapshot 隔离语义</li>
 * </ol>
 *
 * <p><b>与 DolphinScheduler 集成</b>：本编排器作为 DolphinScheduler 自定义 DAG 调度器
 * 部署，通过 {@code TaskChannelFactory} SPI 注册 Spark 批与 Flink 流任务类型。
 * DolphinScheduler Master 调度 DAG 时，对每个节点调用对应 TaskChannel 执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamBatchDagOrchestrator {

    private final SparkBatchSubmitter sparkSubmitter;
    private final FlinkStreamSubmitter flinkSubmitter;
    private final IcebergSnapshotManager snapshotManager;
    private final SnapshotIsolationConfig icebergConfig;

    /**
     * 编排执行流批统一 DAG。
     *
     * @param dag 流批 DAG
     * @return DAG 执行结果（含各节点结果与 snapshot 隔离验证）
     */
    public DagExecutionResult orchestrate(StreamBatchDag dag) {
        Instant startTime = Instant.now();
        log.info("开始编排流批 DAG: dagId={}, name={}, nodes={}", dag.getDagId(), dag.getName(),
                dag.getNodes().size());

        DagExecutionResult.DagExecutionResultBuilder resultBuilder = DagExecutionResult.builder()
                .dagId(dag.getDagId())
                .startTime(startTime)
                .nodeResults(new ArrayList<>());

        try {
            // 1. 校验 DAG
            DagTopologicalSorter.validateDag(dag);

            // 2. 拓扑排序
            List<String> executionOrder = DagTopologicalSorter.topologicalSort(dag);
            log.info("DAG 拓扑执行顺序: {}", executionOrder);

            // 3. 按序执行节点
            Map<String, TaskExecutionResult> resultMap = new HashMap<>();
            boolean hasFailure = false;

            for (String nodeId : executionOrder) {
                DagNode node = dag.findNode(nodeId);

                // 上游失败则跳过
                if (hasUpstreamFailure(nodeId, dag, resultMap)) {
                    log.warn("节点 {} 因上游失败而跳过", nodeId);
                    TaskExecutionResult skipResult = TaskExecutionResult.builder()
                            .nodeId(nodeId)
                            .taskType(node.getTaskType())
                            .status(ExecutionStatus.SKIPPED)
                            .startTime(Instant.now())
                            .endTime(Instant.now())
                            .build();
                    resultMap.put(nodeId, skipResult);
                    resultBuilder.nodeResult(skipResult);
                    hasFailure = true;
                    continue;
                }

                // 选择 TaskChannel 并执行
                TaskExecutionResult nodeResult = executeNode(node);
                resultMap.put(nodeId, nodeResult);
                resultBuilder.nodeResult(nodeResult);

                if (!nodeResult.isSuccess()) {
                    hasFailure = true;
                    log.warn("节点 {} 执行失败，下游节点将跳过", nodeId);
                }
            }

            // 4. snapshot 隔离验证
            SnapshotIsolationVerification verification = verifySnapshotIsolation(dag, resultMap);
            resultBuilder.snapshotIsolationValid(verification.isValid());
            resultBuilder.snapshotIsolationDetail(verification.getDetail());

            // 5. 设置整体状态
            resultBuilder.status(hasFailure ? ExecutionStatus.FAILED : ExecutionStatus.SUCCESS);

        } catch (Exception e) {
            log.error("DAG 编排失败: dagId={}", dag.getDagId(), e);
            resultBuilder.status(ExecutionStatus.FAILED);
            resultBuilder.snapshotIsolationValid(false);
            resultBuilder.snapshotIsolationDetail("DAG 编排异常: " + e.getMessage());
        }

        Instant endTime = Instant.now();
        resultBuilder.endTime(endTime);
        resultBuilder.totalDurationMs(endTime.toEpochMilli() - startTime.toEpochMilli());

        DagExecutionResult result = resultBuilder.build();
        log.info("DAG 编排完成: dagId={}, status={}, snapshotIsolationValid={}, durationMs={}",
                dag.getDagId(), result.getStatus(), result.isSnapshotIsolationValid(),
                result.getTotalDurationMs());
        return result;
    }

    /**
     * 执行单个 DAG 节点（根据类型选择 TaskChannel）。
     */
    private TaskExecutionResult executeNode(DagNode node) {
        log.info("执行 DAG 节点: nodeId={}, type={}, table={}", node.getNodeId(),
                node.getTaskType(), node.getIcebergTable());

        TaskChannel channel = selectChannel(node);
        try {
            return channel.execute(node);
        } catch (TaskExecutionException e) {
            return TaskExecutionResult.builder()
                    .nodeId(node.getNodeId())
                    .taskType(node.getTaskType())
                    .status(ExecutionStatus.FAILED)
                    .errorMessage(e.getMessage())
                    .startTime(Instant.now())
                    .endTime(Instant.now())
                    .build();
        }
    }

    /**
     * 根据节点类型选择 TaskChannel。
     *
     * <p>统一节点（{@link TaskType#UNIFIED_STREAM_BATCH}）按批通道执行
     * （统一节点的批流两部分由 DAG 拆分为两个子节点，或由批通道统一处理）。
     */
    private TaskChannel selectChannel(DagNode node) {
        return switch (node.getTaskType()) {
            case SPARK_BATCH, UNIFIED_STREAM_BATCH -> new SparkBatchTaskChannel(sparkSubmitter);
            case FLINK_STREAM -> new FlinkStreamTaskChannel(flinkSubmitter);
        };
    }

    /**
     * 判断节点上游是否有失败。
     */
    private boolean hasUpstreamFailure(
            String nodeId, StreamBatchDag dag, Map<String, TaskExecutionResult> resultMap) {
        for (String upstreamId : dag.upstreamOf(nodeId)) {
            TaskExecutionResult upstreamResult = resultMap.get(upstreamId);
            if (upstreamResult != null && !upstreamResult.isSuccess()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 验证 DAG 内所有 Iceberg 表的 snapshot 隔离。
     *
     * <p>对每个 Iceberg 表，收集批节点使用的 snapshot-id 与流节点使用的 snapshot-id，
     * 调用 {@link IcebergSnapshotManager#verifySnapshotIsolation} 验证。
     */
    private SnapshotIsolationVerification verifySnapshotIsolation(
            StreamBatchDag dag, Map<String, TaskExecutionResult> resultMap) {

        if (!icebergConfig.isIsolationValidationEnabled()) {
            return new SnapshotIsolationVerification(true, "snapshot 隔离验证已禁用");
        }

        // 按表分组收集批/流 snapshot
        Map<String, Long> batchSnapshots = new HashMap<>();
        Map<String, Long> streamSnapshots = new HashMap<>();
        for (TaskExecutionResult r : resultMap.values()) {
            if (r.getUsedSnapshotId() == null) {
                continue;
            }
            DagNode node = dag.findNode(r.getNodeId());
            if (node == null) {
                continue;
            }
            String table = node.getIcebergTable();
            if (node.isBatchNode()) {
                batchSnapshots.put(table, r.getUsedSnapshotId());
            }
            if (node.isStreamNode()) {
                streamSnapshots.put(table, r.getUsedSnapshotId());
            }
        }

        // 对每个同时有批和流的表验证隔离
        List<String> details = new ArrayList<>();
        boolean allValid = true;
        for (String table : batchSnapshots.keySet()) {
            if (streamSnapshots.containsKey(table)) {
                SnapshotIsolationResult vir = snapshotManager.verifySnapshotIsolation(
                        table, batchSnapshots.get(table), streamSnapshots.get(table));
                details.add(vir.getDetail());
                if (!vir.isValid()) {
                    allValid = false;
                }
            }
        }

        if (details.isEmpty()) {
            return new SnapshotIsolationVerification(true,
                    "无同时包含批与流节点的 Iceberg 表，snapshot 隔离验证跳过");
        }
        return new SnapshotIsolationVerification(allValid, String.join("; ", details));
    }

    /**
     * snapshot 隔离验证内部结果。
     */
    private record SnapshotIsolationVerification(boolean isValid, String detail) {
        public boolean isValid() {
            return isValid;
        }

        public String getDetail() {
            return detail;
        }
    }
}