package com.shuqing.bigdata.streambatch.spark;

import com.shuqing.bigdata.streambatch.iceberg.IcebergSnapshotManager;
import com.shuqing.bigdata.streambatch.iceberg.SnapshotIsolationConfig;
import com.shuqing.bigdata.streambatch.model.SnapshotRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Spark 批作业提交器。
 *
 * <p>通过 SparkLauncher 提交 Spark 批作业，作业读取 Iceberg 表的
 * <b>固定 snapshot</b>（snapshot 隔离的批端）。
 *
 * <p>提交流程：
 * <ol>
 *   <li>从 {@link IcebergSnapshotManager} 获取/锁定批读 snapshot-id</li>
 *   <li>构建 Spark Conf（含 Iceberg Catalog + 固定 snapshot 引用）</li>
 *   <li>通过 SparkLauncher 提交作业（{@code spark-submit} 等价）</li>
 *   <li>返回作业 appId 与使用的 snapshot-id</li>
 * </ol>
 *
 * <p>实际部署时使用 {@code org.apache.spark.launcher.SparkLauncher}；
 * 本实现提供独立可测试的提交逻辑，SparkLauncher 调用通过日志模拟
 * （避免本地无 Spark 集群时编译/运行失败）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SparkBatchSubmitter {

    private final SparkBatchConfig sparkConfig;
    private final IcebergSnapshotManager snapshotManager;
    private final SnapshotIsolationConfig icebergConfig;

    /**
     * 提交 Spark 批作业（读 Iceberg 固定 snapshot）。
     *
     * @param table           Iceberg 表全名（database.table）
     * @param mainResource    作业主资源（jar 路径或 SQL 文件）
     * @param mainClass       作业主类（jar 作业）
     * @param args            作业参数
     * @param explicitSnapshotId 显式指定的 snapshot-id（null 表示锁定当前最新）
     * @return 提交结果（含 appId、snapshotId）
     */
    public SparkSubmitResult submitBatch(
            String table,
            String mainResource,
            String mainClass,
            String args,
            Long explicitSnapshotId) {

        // 1. 锁定批读 snapshot
        SnapshotRef batchSnapshot;
        if (explicitSnapshotId != null) {
            batchSnapshot = snapshotManager.lockBatchSnapshot(table, explicitSnapshotId);
        } else if ("EXPLICIT".equalsIgnoreCase(icebergConfig.getBatchSnapshotLockMode())) {
            throw new IllegalArgumentException(
                    "batchSnapshotLockMode=EXPLICIT 但未提供 snapshotId，表: " + table);
        } else {
            batchSnapshot = snapshotManager.lockBatchSnapshot(table);
        }

        // 2. 构建 Spark Conf
        Map<String, String> sparkConf = snapshotManager.buildSparkBatchConfig(
                table, batchSnapshot.getSnapshotId());
        sparkConf.putAll(sparkConfig.getExtraConf());

        // 3. 构建提交命令（模拟 SparkLauncher）
        String appId = "spark-" + UUID.randomUUID().toString().substring(0, 8);
        String submitCmd = buildSubmitCommand(table, mainResource, mainClass, args, sparkConf);

        log.info("提交 Spark 批作业: table={}, snapshotId={}, appId={}", table,
                batchSnapshot.getSnapshotId(), appId);
        log.debug("Spark 提交命令: {}", submitCmd);

        // 4. 返回提交结果（实际场景等待 SparkLauncher.launch() 返回 appId）
        return SparkSubmitResult.builder()
                .appId(appId)
                .snapshotId(batchSnapshot.getSnapshotId())
                .submitCommand(submitCmd)
                .success(true)
                .build();
    }

    /**
     * 取消 Spark 批作业。
     *
     * @param appId Spark 应用 ID
     * @return {@code true} 表示取消成功
     */
    public boolean cancel(String appId) {
        log.info("取消 Spark 批作业: appId={}（实际通过 spark-submit --kill 或 REST API）", appId);
        return true;
    }

    /**
     * 构建 spark-submit 命令（用于日志与测试验证）。
     */
    private String buildSubmitCommand(
            String table, String mainResource, String mainClass, String args,
            Map<String, String> sparkConf) {
        StringBuilder cmd = new StringBuilder("spark-submit");
        cmd.append(" --master ").append(sparkConfig.getMaster());
        cmd.append(" --deploy-mode ").append(sparkConfig.getDeployMode());
        cmd.append(" --driver-memory ").append(sparkConfig.getDriverMemory());
        cmd.append(" --executor-memory ").append(sparkConfig.getExecutorMemory());
        cmd.append(" --executor-cores ").append(sparkConfig.getExecutorCores());
        cmd.append(" --num-executors ").append(sparkConfig.getExecutorInstances());
        if (mainClass != null && !mainClass.isEmpty()) {
            cmd.append(" --class ").append(mainClass);
        }
        // Iceberg Catalog 配置
        sparkConf.forEach((k, v) -> {
            if (!k.startsWith("__")) {
                cmd.append(" --conf ").append(k).append("=").append(v);
            }
        });
        // 固定 snapshot（通过 SQL history(snapshot_id => ...) 引用）
        cmd.append(" --conf spark.iceberg.batch.snapshot_id=")
                .append(sparkConf.get("__iceberg_batch_snapshot_id__"));
        if (mainResource != null) {
            cmd.append(" ").append(mainResource);
        }
        if (args != null && !args.isEmpty()) {
            cmd.append(" ").append(args);
        }
        return cmd.toString();
    }
}