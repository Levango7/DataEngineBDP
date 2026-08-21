package com.levango7.dataenginebdp.streambatch.service;

import com.levango7.dataenginebdp.streambatch.doris.DorisOlapClient;
import com.levango7.dataenginebdp.streambatch.doris.DorisOlapException;
import com.levango7.dataenginebdp.streambatch.doris.DorisQueryResult;
import com.levango7.dataenginebdp.streambatch.iceberg.IcebergSnapshotManager;
import com.levango7.dataenginebdp.streambatch.iceberg.SnapshotIsolationConfig;
import com.levango7.dataenginebdp.streambatch.iceberg.SnapshotIsolationResult;
import com.levango7.dataenginebdp.streambatch.model.SnapshotRef;
import com.levango7.dataenginebdp.streambatch.router.ViewRouterConfig;
import com.levango7.dataenginebdp.streambatch.spark.SparkBatchConfig;
import com.levango7.dataenginebdp.streambatch.spark.SparkBatchSubmitter;
import com.levango7.dataenginebdp.streambatch.spark.SparkSubmitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * BatchPipelineOrchestrationService 单元测试。
 *
 * <p>验证批计算链路编排服务在以下场景的行为：
 * <ul>
 *   <li>全链路成功：Spark 提交成功 + Doris 查询成功 + snapshot 隔离通过</li>
 *   <li>Spark 提交失败：链路提前终止，标记为失败</li>
 *   <li>Doris 查询失败：链路提前终止，标记为失败</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BatchPipelineOrchestrationServiceTest {

    @Mock
    private SparkBatchSubmitter sparkSubmitter;
    @Mock
    private IcebergSnapshotManager snapshotManager;
    @Mock
    private DorisOlapClient dorisOlapClient;

    private SparkBatchConfig sparkConfig;
    private SnapshotIsolationConfig icebergConfig;
    private ViewRouterConfig viewRouterConfig;
    private BatchPipelineOrchestrationService service;

    @BeforeEach
    void setUp() {
        sparkConfig = new SparkBatchConfig();
        icebergConfig = new SnapshotIsolationConfig();
        viewRouterConfig = new ViewRouterConfig();
        service = new BatchPipelineOrchestrationService(
                sparkSubmitter, sparkConfig, snapshotManager, icebergConfig,
                dorisOlapClient, viewRouterConfig);
    }

    @Test
    void fullPipeline_success_allStagesPass() throws Exception {
        // 准备 mock
        SnapshotRef snap = SnapshotRef.builder().snapshotId(1001L).build();
        when(snapshotManager.lockBatchSnapshot(anyString())).thenReturn(snap);
        when(snapshotManager.getLatestSnapshot(anyString())).thenReturn(snap);
        SnapshotIsolationResult isolationResult = new SnapshotIsolationResult();
        isolationResult.setValid(true);
        isolationResult.setDetail("snapshot 隔离验证通过");
        when(snapshotManager.verifySnapshotIsolation(anyString(), anyLong(), anyLong()))
                .thenReturn(isolationResult);

        when(sparkSubmitter.submitBatch(anyString(), any(), any(), any(), any()))
                .thenReturn(SparkSubmitResult.builder()
                        .appId("spark-app-123").snapshotId(1001L).success(true).build());

        DorisQueryResult queryResult = DorisQueryResult.builder()
                .columnNames(List.of("order_date", "order_cnt"))
                .rows(List.of(Map.of("order_date", "2026-08-01", "order_cnt", "128")))
                .success(true).elapsedMs(50).build();
        when(dorisOlapClient.query(anyString(), anyString())).thenReturn(queryResult);

        // 执行
        var request = BatchPipelineOrchestrationService.BatchPipelineRequest.builder()
                .icebergTable("trade.ods_user_order")
                .sparkMainResource("s3://jobs/etl.jar")
                .sparkMainClass("com.example.Main")
                .dorisDatabase("dwd")
                .dorisTable("dws_user_order_1d")
                .build();
        var result = service.executePipeline(request);

        // 验证
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSnapshotId()).isEqualTo(1001L);
        assertThat(result.getSparkAppId()).isEqualTo("spark-app-123");
        assertThat(result.getQueryResult()).isNotNull();
        assertThat(result.getQueryResult().getRowCount()).isEqualTo(1);
        assertThat(result.getSuccessStageCount()).isGreaterThanOrEqualTo(4);
        assertThat(result.getFailedStageCount()).isEqualTo(0);
        assertThat(result.getTotalElapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void sparkSubmitFailure_pipelineFails() throws Exception {
        SnapshotRef snap = SnapshotRef.builder().snapshotId(1001L).build();
        when(snapshotManager.lockBatchSnapshot(anyString())).thenReturn(snap);
        when(sparkSubmitter.submitBatch(anyString(), any(), any(), any(), any()))
                .thenReturn(SparkSubmitResult.builder()
                        .success(false).errorMessage("Spark 集群不可达").build());

        var request = BatchPipelineOrchestrationService.BatchPipelineRequest.builder()
                .icebergTable("trade.ods_user_order")
                .sparkMainResource("s3://jobs/etl.jar")
                .dorisDatabase("dwd")
                .dorisTable("dws_user_order_1d")
                .build();
        var result = service.executePipeline(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getSummary()).contains("Spark 批作业提交失败");
    }

    @Test
    void dorisQueryFailure_pipelineFails() throws Exception {
        SnapshotRef snap = SnapshotRef.builder().snapshotId(1001L).build();
        when(snapshotManager.lockBatchSnapshot(anyString())).thenReturn(snap);
        when(sparkSubmitter.submitBatch(anyString(), any(), any(), any(), any()))
                .thenReturn(SparkSubmitResult.builder()
                        .appId("spark-app-123").snapshotId(1001L).success(true).build());
        when(dorisOlapClient.query(anyString(), anyString()))
                .thenThrow(new DorisOlapException("Doris FE 不可达"));

        var request = BatchPipelineOrchestrationService.BatchPipelineRequest.builder()
                .icebergTable("trade.ods_user_order")
                .sparkMainResource("s3://jobs/etl.jar")
                .dorisDatabase("dwd")
                .dorisTable("dws_user_order_1d")
                .build();
        var result = service.executePipeline(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getSummary()).contains("Doris OLAP 查询失败");
    }

    @Test
    void materializedViewRefreshFailure_continuesToQuery() throws Exception {
        SnapshotRef snap = SnapshotRef.builder().snapshotId(1001L).build();
        when(snapshotManager.lockBatchSnapshot(anyString())).thenReturn(snap);
        when(snapshotManager.getLatestSnapshot(anyString())).thenReturn(snap);
        SnapshotIsolationResult isolationResult = new SnapshotIsolationResult();
        isolationResult.setValid(true);
        when(snapshotManager.verifySnapshotIsolation(anyString(), anyLong(), anyLong()))
                .thenReturn(isolationResult);
        when(sparkSubmitter.submitBatch(anyString(), any(), any(), any(), any()))
                .thenReturn(SparkSubmitResult.builder()
                        .appId("spark-app-123").snapshotId(1001L).success(true).build());
        when(dorisOlapClient.refreshMaterializedView(anyString(), anyString()))
                .thenThrow(new DorisOlapException("刷新失败"));
        DorisQueryResult queryResult = DorisQueryResult.builder()
                .columnNames(List.of("c1")).rows(List.of()).success(true).elapsedMs(10).build();
        when(dorisOlapClient.query(anyString(), anyString())).thenReturn(queryResult);

        var request = BatchPipelineOrchestrationService.BatchPipelineRequest.builder()
                .icebergTable("trade.ods_user_order")
                .sparkMainResource("s3://jobs/etl.jar")
                .dorisDatabase("dwd")
                .dorisTable("dws_user_order_1d")
                .materializedViewName("mv_dws_user_order_1d")
                .build();
        var result = service.executePipeline(request);

        // 物化视图刷新失败但链路继续
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getQueryResult()).isNotNull();
    }
}