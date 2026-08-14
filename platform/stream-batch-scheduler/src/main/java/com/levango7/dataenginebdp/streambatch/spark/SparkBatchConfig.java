package com.levango7.dataenginebdp.streambatch.spark;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spark 批作业提交配置。
 *
 * <p>配置 Spark Master / Deploy Mode / 资源等，由
 * {@link SparkBatchSubmitter} 使用 SparkLauncher 提交批作业。
 */
@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "shuqing.stream-batch.spark")
public class SparkBatchConfig {

    /** Spark Master（spark://host:7077 或 yarn 或 k8s://...）。 */
    private String master = "spark://localhost:7077";

    /** Deploy Mode（client / cluster）。 */
    private String deployMode = "cluster";

    /** Spark Driver 内存。 */
    private String driverMemory = "2g";

    /** Spark Executor 内存。 */
    private String executorMemory = "4g";

    /** Spark Executor 核数。 */
    private int executorCores = 3;

    /** Spark Executor 实例数（与 L2.2 批计算详细设计 cores=3 对齐）。 */
    private int executorInstances = 3;

    /** Spark 驱动类路径资源（Iceberg Spark Runtime jar）。 */
    private String sparkJars = "";

    /**
     * 真实提交开关：true 通过 SparkLauncher 真实提交（spark-submit 等价）并解析真实 appId；
     * false 使用日志模拟（本地无 Spark 集群时默认）。
     */
    private boolean realSubmitEnabled = false;

    /** Spark Launcher 可执行文件路径（realSubmitEnabled=true 时需要；默认走 PATH 的 spark-submit）。 */
    private String sparkHome = "";

    /** Spark Conf 额外配置。 */
    private java.util.Map<String, String> extraConf = new java.util.HashMap<>();
}