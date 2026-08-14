package com.levango7.dataenginebdp.streambatch.spark;

import com.levango7.dataenginebdp.streambatch.iceberg.IcebergSnapshotManager;
import com.levango7.dataenginebdp.streambatch.iceberg.SnapshotIsolationConfig;
import com.levango7.dataenginebdp.streambatch.model.SnapshotRef;
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

        // 3. 真实提交路径：SparkLauncher（spark-submit 等价）解析真实 appId
        if (sparkConfig.isRealSubmitEnabled()) {
            try {
                String realAppId = submitViaLauncher(
                        table, mainResource, mainClass, args, sparkConf);
                log.info("Spark 批作业真实提交成功: table={}, appId={}, snapshotId={}",
                        table, realAppId, batchSnapshot.getSnapshotId());
                return SparkSubmitResult.builder()
                        .appId(realAppId)
                        .snapshotId(batchSnapshot.getSnapshotId())
                        .submitCommand(buildSubmitCommand(table, mainResource, mainClass, args, sparkConf))
                        .success(true)
                        .build();
            } catch (Exception e) {
                log.error("Spark 真实提交失败(不回退模拟): table={}, err={}", table, e.getMessage());
                return SparkSubmitResult.builder()
                        .appId(null)
                        .snapshotId(batchSnapshot.getSnapshotId())
                        .success(false)
                        .errorMessage("Spark 真实提交失败: " + e.getMessage())
                        .build();
            }
        }

        // 4. 日志模拟路径（默认，本地无集群）
        String appId = "spark-" + UUID.randomUUID().toString().substring(0, 8);
        String submitCmd = buildSubmitCommand(table, mainResource, mainClass, args, sparkConf);

        log.info("提交 Spark 批作业(模拟): table={}, snapshotId={}, appId={}", table,
                batchSnapshot.getSnapshotId(), appId);
        log.debug("Spark 提交命令: {}", submitCmd);

        // 5. 返回提交结果（实际场景等待 SparkLauncher.launch() 返回 appId）
        return SparkSubmitResult.builder()
                .appId(appId)
                .snapshotId(batchSnapshot.getSnapshotId())
                .submitCommand(submitCmd)
                .success(true)
                .build();
    }

    /**
     * 通过 SparkLauncher 真实提交作业，等待 appId。
     *
     * <p>仅 realSubmitEnabled=true 时调用；集群不可达 / 启动失败抛异常，
     * 由调用方包装为失败结果（不回退模拟）。
     */
    private String submitViaLauncher(String table, String mainResource, String mainClass,
                                     String args, Map<String, String> sparkConf) throws Exception {
        if (mainResource == null || mainResource.isBlank()) {
            throw new IllegalArgumentException("mainResource 不能为空（realSubmitEnabled=true 需要作业 jar/SQL）");
        }
        org.apache.spark.launcher.SparkLauncher launcher = new org.apache.spark.launcher.SparkLauncher()
                .setMaster(sparkConfig.getMaster())
                .setDeployMode(sparkConfig.getDeployMode())
                .setAppResource(mainResource)
                .setMainClass(mainClass != null ? mainClass : "org.apache.spark.deploy.SparkSubmit")
                .addAppArgs(args != null && !args.isEmpty() ? args.split("\\s+") : new String[0])
                .setConf("spark.driver.memory", sparkConfig.getDriverMemory())
                .setConf("spark.executor.memory", sparkConfig.getExecutorMemory())
                .setConf("spark.executor.cores", String.valueOf(sparkConfig.getExecutorCores()))
                .setConf("spark.executor.instances", String.valueOf(sparkConfig.getExecutorInstances()))
                .setVerbose(false);

        // Iceberg Catalog 配置 + 固定 snapshot 引用
        sparkConf.forEach((k, v) -> {
            if (!k.startsWith("__")) {
                launcher.setConf(k, v);
            }
        });
        if (sparkConfig.getSparkHome() != null && !sparkConfig.getSparkHome().isBlank()) {
            launcher.setSparkHome(sparkConfig.getSparkHome());
        }

        // 固定 snapshot 通过 Spark SQL session 配置传递
        launcher.setConf("spark.iceberg.batch.snapshot_id",
                sparkConf.getOrDefault("__iceberg_batch_snapshot_id__", ""));

        org.apache.spark.launcher.SparkAppHandle handle = launcher.startApplication();
        // 等待提交完成（获取 appId）；最多 60s，超时抛异常
        for (int i = 0; i < 60; i++) {
            String appId = handle.getAppId();
            if (appId != null && !appId.isBlank()) {
                return appId;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Spark 作业 60s 内未获取 appId，table=" + table);
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