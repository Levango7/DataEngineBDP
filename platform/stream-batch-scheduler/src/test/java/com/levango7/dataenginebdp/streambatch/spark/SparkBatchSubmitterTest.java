package com.levango7.dataenginebdp.streambatch.spark;

import com.levango7.dataenginebdp.streambatch.iceberg.IcebergSnapshotManager;
import com.levango7.dataenginebdp.streambatch.iceberg.SnapshotIsolationConfig;
import com.levango7.dataenginebdp.streambatch.model.SnapshotRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * SparkBatchSubmitter 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class SparkBatchSubmitterTest {

    @Mock
    private IcebergSnapshotManager snapshotManager;

    private SparkBatchConfig sparkConfig;
    private SnapshotIsolationConfig icebergConfig;

    @BeforeEach
    void setUp() {
        sparkConfig = new SparkBatchConfig();
        sparkConfig.setMaster("spark://localhost:7077");
        sparkConfig.setDeployMode("cluster");
        icebergConfig = new SnapshotIsolationConfig();
        icebergConfig.setBatchSnapshotLockMode("AT_JOB_START");
    }

    private SparkBatchSubmitter newSubmitter() {
        return new SparkBatchSubmitter(sparkConfig, snapshotManager, icebergConfig);
    }

    @Test
    void mockMode_returnsSyntheticAppId() {
        // 无 explicitSnapshotId → 走单参数 lockBatchSnapshot(table)
        when(snapshotManager.lockBatchSnapshot(any()))
                .thenReturn(SnapshotRef.builder().snapshotId(123L).build());
        Map<String, String> conf = new HashMap<>();
        conf.put("__iceberg_batch_snapshot_id__", "123");
        when(snapshotManager.buildSparkBatchConfig(any(), anyLong())).thenReturn(conf);

        SparkSubmitResult result = newSubmitter().submitBatch(
                "db.tbl", "s3://jobs/etl.jar", "com.example.Main", null, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAppId()).startsWith("spark-");
        assertThat(result.getSnapshotId()).isEqualTo(123L);
        assertThat(result.getSubmitCommand()).contains("--master spark://localhost:7077");
    }

    @Test
    void realSubmit_missingResourceFails() {
        sparkConfig.setRealSubmitEnabled(true);
        when(snapshotManager.lockBatchSnapshot(any()))
                .thenReturn(SnapshotRef.builder().snapshotId(123L).build());
        Map<String, String> conf = new HashMap<>();
        conf.put("__iceberg_batch_snapshot_id__", "123");
        when(snapshotManager.buildSparkBatchConfig(any(), anyLong())).thenReturn(conf);

        // mainResource 为空 → 真实路径抛 IllegalArgumentException → 失败结果
        SparkSubmitResult result = newSubmitter().submitBatch(
                "db.tbl", null, null, null, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getAppId()).isNull();
        assertThat(result.getErrorMessage()).contains("mainResource 不能为空");
    }
}
