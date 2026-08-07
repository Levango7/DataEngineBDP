package com.shuqing.bigdata.streambatch.flink;

import com.shuqing.bigdata.streambatch.iceberg.IcebergSnapshotManager;
import com.shuqing.bigdata.streambatch.model.SnapshotRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Flink 流作业提交器。
 *
 * <p>通过 Flink REST API 提交 Flink 流作业，作业读取 Iceberg 表的
 * <b>最新 snapshot</b>（streaming 模式，snapshot 隔离的流端）。
 *
 * <p>提交流程：
 * <ol>
 *   <li>从 {@link IcebergSnapshotManager} 获取流读起点 snapshot</li>
 *   <li>构建 Flink Conf（含 Iceberg Connector + streaming 模式）</li>
 *   <li>通过 Flink REST API（{@code POST /jars/:jarid/run}）提交作业</li>
 *   <li>返回作业 jobId 与起始 snapshot-id</li>
 * </ol>
 *
 * <p>实际部署时使用 {@code org.apache.flink.client.rest.RestClusterClient}；
 * 本实现提供独立可测试的提交逻辑，REST 调用通过日志模拟
 * （避免本地无 Flink 集群时编译/运行失败）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlinkStreamSubmitter {

    private final FlinkStreamConfig flinkConfig;
    private final IcebergSnapshotManager snapshotManager;

    /**
     * 提交 Flink 流作业（读 Iceberg 最新 snapshot，streaming 模式）。
     *
     * @param table        Iceberg 表全名（database.table）
     * @param mainResource 作业主资源（jar 路径）
     * @param entryClass   作业入口类
     * @param args         作业参数
     * @param parallelism  并行度（null 使用默认）
     * @return 提交结果（含 jobId、起始 snapshotId）
     */
    public FlinkSubmitResult submitStream(
            String table,
            String mainResource,
            String entryClass,
            String args,
            Integer parallelism) {

        // 1. 获取流读起点 snapshot
        SnapshotRef startSnapshot = snapshotManager.getStreamStartSnapshot(table);

        // 2. 构建 Flink Conf
        Map<String, String> flinkConf = snapshotManager.buildFlinkStreamConfig(table);
        flinkConf.putAll(flinkConfig.getExtraConf());
        int parallel = parallelism != null ? parallelism : flinkConfig.getParallelism();

        // 3. 构建提交请求（模拟 Flink REST API）
        String jobId = UUID.randomUUID().toString();
        String submitPayload = buildSubmitPayload(table, mainResource, entryClass, args,
                parallel, flinkConf);

        log.info("提交 Flink 流作业: table={}, startSnapshotId={}, jobId={}, parallelism={}",
                table, startSnapshot.getSnapshotId(), jobId, parallel);
        log.debug("Flink 提交请求: {}", submitPayload);

        // 4. 返回提交结果
        return FlinkSubmitResult.builder()
                .jobId(jobId)
                .startSnapshotId(startSnapshot.getSnapshotId())
                .submitPayload(submitPayload)
                .parallelism(parallel)
                .success(true)
                .build();
    }

    /**
     * 取消 Flink 流作业。
     *
     * @param jobId Flink 作业 ID
     * @return {@code true} 表示取消成功
     */
    public boolean cancel(String jobId) {
        log.info("取消 Flink 流作业: jobId={}（实际通过 REST API PATCH /jobs/:jobid/cancel）", jobId);
        return true;
    }

    /**
     * 构建 Flink REST 提交请求 JSON（用于日志与测试验证）。
     */
    private String buildSubmitPayload(
            String table, String mainResource, String entryClass, String args,
            int parallelism, Map<String, String> flinkConf) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"jarPath\":\"").append(mainResource != null ? mainResource : "").append("\"");
        if (entryClass != null && !entryClass.isEmpty()) {
            json.append(",\"entryClass\":\"").append(entryClass).append("\"");
        }
        json.append(",\"parallelism\":").append(parallelism);
        if (args != null && !args.isEmpty()) {
            json.append(",\"programArgs\":\"").append(args).append("\"");
        }
        // Iceberg Connector 配置
        json.append(",\"flinkConf\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : flinkConf.entrySet()) {
            if (!first) {
                json.append(",");
            }
            json.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
            first = false;
        }
        json.append("}}");
        return json.toString();
    }
}