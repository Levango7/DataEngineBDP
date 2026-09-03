package com.levango7.dataenginebdp.streambatch.plugin;

import com.levango7.dataenginebdp.streambatch.batchpipeline.BatchPipelineClient;
import com.levango7.dataenginebdp.streambatch.batchpipeline.BatchPipelineConfig;
import com.levango7.dataenginebdp.streambatch.batchpipeline.BatchStatusSnapshot;
import com.levango7.dataenginebdp.streambatch.batchpipeline.BatchSubmitResult;
import com.levango7.dataenginebdp.streambatch.model.DagNode;
import com.levango7.dataenginebdp.streambatch.model.ExecutionStatus;
import com.levango7.dataenginebdp.streambatch.model.TaskExecutionResult;
import com.levango7.dataenginebdp.streambatch.model.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BatchPipelineTaskChannel 单元测试（Mockito 模拟客户端）。
 *
 * <p>覆盖：提交成功→轮询至 success/failed、提交失败立即 FAILED、
 * pipelineConfig 非法 JSON 立即 FAILED、租户与 batchId 参数传递、
 * 轮询超时判失败、cancel 不支持语义。
 */
class BatchPipelineTaskChannelTest {

    private BatchPipelineClient client;
    private BatchPipelineConfig config;
    private BatchPipelineTaskChannel channel;

    @BeforeEach
    void setUp() {
        client = mock(BatchPipelineClient.class);
        config = new BatchPipelineConfig();
        config.setPollIntervalMs(10);
        config.setPollTimeoutSeconds(5);
        channel = new BatchPipelineTaskChannel(client, config);
    }

    private DagNode newNode(Map<String, String> extraConfig) {
        Map<String, String> extras = extraConfig != null ? extraConfig : new HashMap<>();
        return DagNode.builder()
                .nodeId("bp-node-1")
                .name("五阶段流水线批次")
                .taskType(TaskType.BATCH_PIPELINE)
                .icebergTable("lake.warehouse.orders")
                .extraConfig(extras)
                .build();
    }

    @Test
    void channelType_matchesTaskTypeCode() {
        assertThat(channel.getChannelType()).isEqualTo("BATCH_PIPELINE");
    }

    @Test
    void execute_successAfterRunning() throws Exception {
        when(client.submitBatch(anyString(), eq("default"), any()))
                .thenReturn(BatchSubmitResult.ok("b-001", "default"));
        when(client.getBatch("b-001"))
                .thenReturn(BatchStatusSnapshot.builder().batchId("b-001").status("running").build())
                .thenReturn(BatchStatusSnapshot.builder().batchId("b-001").status("success").build());

        TaskExecutionResult result = channel.execute(newNode(null));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getJobId()).isEqualTo("b-001");
        assertThat(result.getMetadata()).containsEntry("pipelineStatus", "success");
        assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    void execute_failedWithServerErrorMessage() throws Exception {
        when(client.submitBatch(anyString(), anyString(), any()))
                .thenReturn(BatchSubmitResult.ok("b-002", "acme"));
        when(client.getBatch("b-002"))
                .thenReturn(BatchStatusSnapshot.builder()
                        .batchId("b-002").status("failed").errorMessage("pipeline exited with code 1").build());

        TaskExecutionResult result = channel.execute(
                newNode(Map.of("tenant", "acme")));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("exited");
        assertThat(result.getMetadata()).containsEntry("tenantId", "acme");
    }

    @Test
    void execute_submitFailureFailsFast() throws Exception {
        when(client.submitBatch(anyString(), anyString(), any()))
                .thenReturn(BatchSubmitResult.fail("提交异常: connection refused"));

        TaskExecutionResult result = channel.execute(newNode(null));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("connection refused");
        assertThat(result.getJobId()).isNull();
    }

    @Test
    void execute_invalidPipelineConfigJsonFailsFast() throws Exception {
        TaskExecutionResult result = channel.execute(newNode(Map.of("pipelineConfig", "{not-json")));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("pipelineConfig");
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void execute_pollTimeoutFails() throws Exception {
        BatchPipelineConfig fastConfig = new BatchPipelineConfig();
        fastConfig.setPollIntervalMs(10);
        fastConfig.setPollTimeoutSeconds(0); // 立即超时
        BatchPipelineTaskChannel fastChannel = new BatchPipelineTaskChannel(client, fastConfig);

        when(client.submitBatch(anyString(), anyString(), any()))
                .thenReturn(BatchSubmitResult.ok("b-003", "default"));

        TaskExecutionResult result = fastChannel.execute(newNode(null));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("轮询超时");
    }

    @Test
    void execute_clientExceptionFailsGracefully() throws Exception {
        when(client.submitBatch(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("boom"));

        TaskExecutionResult result = channel.execute(newNode(null));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrorMessage()).contains("boom");
    }

    @Test
    void cancel_reportsUnsupported() {
        assertThat(channel.cancel("b-001")).isFalse();
    }
}
