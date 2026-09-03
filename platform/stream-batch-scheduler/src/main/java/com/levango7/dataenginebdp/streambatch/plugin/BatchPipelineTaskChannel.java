package com.levango7.dataenginebdp.streambatch.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.streambatch.batchpipeline.BatchPipelineClient;
import com.levango7.dataenginebdp.streambatch.batchpipeline.BatchPipelineConfig;
import com.levango7.dataenginebdp.streambatch.batchpipeline.BatchStatusSnapshot;
import com.levango7.dataenginebdp.streambatch.batchpipeline.BatchSubmitResult;
import com.levango7.dataenginebdp.streambatch.model.DagNode;
import com.levango7.dataenginebdp.streambatch.model.ExecutionStatus;
import com.levango7.dataenginebdp.streambatch.model.TaskExecutionResult;
import com.levango7.dataenginebdp.streambatch.model.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * batch-pipeline 批处理任务通道（DolphinScheduler TaskChannel 扩展）。
 *
 * <p>处理 {@link TaskType#BATCH_PIPELINE} 节点：把节点提交给
 * batch-pipeline 服务（data-quality 实体，五阶段流水线
 * ingest→validate→clean→compute→output）并同步轮询至终态。
 *
 * <p>节点参数约定（DagNode.extraConfig）：
 * <ul>
 *   <li>{@code pipelineConfig} — 批次 config 业务字段覆盖（JSON 对象字符串，
 *       如 quality 规则 / engine backend）；tenant/storage/run_dir 等路径
 *       字段由服务端剔除并按租户强制分区，请求体不可逃逸</li>
 *   <li>{@code tenant} — 租户 id 覆盖（缺省用 BatchPipelineConfig.tenantId）</li>
 *   <li>{@code batchId} — 批次 id 覆盖（缺省 "dag-&lt;nodeId&gt;-&lt;UTC时间戳&gt;"）</li>
 * </ul>
 *
 * <p>通道为同步执行（提交 + 轮询至 success/failed），与 TaskChannel SPI
 * 契约一致；批次真正执行在 batch-pipeline 服务进程内串行，吞吐扩展靠
 * 服务横向扩容。
 */
@Slf4j
@RequiredArgsConstructor
public class BatchPipelineTaskChannel implements TaskChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter BATCH_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final BatchPipelineClient client;
    private final BatchPipelineConfig config;

    @Override
    public String getChannelType() {
        return TaskType.BATCH_PIPELINE.getCode();
    }

    @Override
    public TaskExecutionResult execute(DagNode node) throws TaskExecutionException {
        Instant start = Instant.now();
        log.info("batch-pipeline 通道执行节点: nodeId={}, tenant={}", node.getNodeId(),
                node.getExtraConfig().getOrDefault("tenant", config.getTenantId()));

        Map<String, String> metadata = new LinkedHashMap<>();
        try {
            Map<String, Object> configOverride = parsePipelineConfig(node);
            String tenantId = node.getExtraConfig().getOrDefault("tenant", config.getTenantId());
            String batchId = node.getExtraConfig().getOrDefault(
                    "batchId", "dag-" + node.getNodeId() + "-" + BATCH_TS.format(Instant.now()));
            metadata.put("batchId", batchId);
            metadata.put("tenantId", tenantId);

            BatchSubmitResult submitted = client.submitBatch(batchId, tenantId, configOverride);
            if (!submitted.isSuccess()) {
                return failed(node, start, metadata, submitted.getErrorMessage());
            }
            metadata.put("batchId", submitted.getBatchId());

            BatchStatusSnapshot finalState = pollUntilTerminal(submitted.getBatchId());
            metadata.put("pipelineStatus", finalState.getStatus());
            Instant end = Instant.now();
            return TaskExecutionResult.builder()
                    .nodeId(node.getNodeId())
                    .taskType(node.getTaskType())
                    .status("success".equals(finalState.getStatus())
                            ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED)
                    .jobId(submitted.getBatchId())
                    .errorMessage(finalState.getErrorMessage())
                    .startTime(start)
                    .endTime(end)
                    .durationMs(end.toEpochMilli() - start.toEpochMilli())
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            log.error("batch-pipeline 通道执行失败: nodeId={}", node.getNodeId(), e);
            return failed(node, start, metadata, e.getMessage());
        }
    }

    /**
     * 轮询批次状态直至终态或超时。
     */
    private BatchStatusSnapshot pollUntilTerminal(String batchId) throws Exception {
        long deadline = System.currentTimeMillis() + config.getPollTimeoutSeconds() * 1000;
        long interval = Math.max(config.getPollIntervalMs(), 200);
        while (System.currentTimeMillis() < deadline) {
            BatchStatusSnapshot snapshot = client.getBatch(batchId);
            if (snapshot.isTerminal()) {
                return snapshot;
            }
            Thread.sleep(interval);
        }
        return BatchStatusSnapshot.builder()
                .batchId(batchId)
                .status("failed")
                .errorMessage("批次状态轮询超时（" + config.getPollTimeoutSeconds() + "s）: " + batchId)
                .build();
    }

    /**
     * 解析节点 extraConfig.pipelineConfig（JSON 对象字符串）为 config 覆盖。
     *
     * @throws TaskExecutionException JSON 非法或不是对象（立即失败，不带病提交）
     */
    private Map<String, Object> parsePipelineConfig(DagNode node) throws TaskExecutionException {
        String raw = node.getExtraConfig().get("pipelineConfig");
        if (raw == null || raw.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw,
                    MAPPER.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class));
            if (parsed == null) {
                throw new TaskExecutionException("pipelineConfig 必须是 JSON 对象: " + raw);
            }
            return parsed;
        } catch (TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskExecutionException("pipelineConfig JSON 解析失败: " + e.getMessage());
        }
    }

    private TaskExecutionResult failed(
            DagNode node, Instant start, Map<String, String> metadata, String errorMessage) {
        Instant end = Instant.now();
        return TaskExecutionResult.builder()
                .nodeId(node.getNodeId())
                .taskType(node.getTaskType())
                .status(ExecutionStatus.FAILED)
                .errorMessage(errorMessage)
                .startTime(start)
                .endTime(end)
                .durationMs(end.toEpochMilli() - start.toEpochMilli())
                .metadata(metadata)
                .build();
    }

    @Override
    public boolean cancel(String jobId) {
        // batch-pipeline API 暂无批次取消端点；DAG 中断时批次将在服务端自行执行完成
        log.warn("batch-pipeline 批次取消不支持（API 无取消端点），batchId={} 将执行至终态", jobId);
        return false;
    }
}
