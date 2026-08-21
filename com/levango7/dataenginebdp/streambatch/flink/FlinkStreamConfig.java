package com.shuqing.bigdata.streambatch.flink;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Flink 流作业提交配置。
 *
 * <p>配置 Flink JobManager REST 地址 / 资源等，由
 * {@link FlinkStreamSubmitter} 通过 Flink REST API 提交流作业。
 */
@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "shuqing.stream-batch.flink")
public class FlinkStreamConfig {

    /** Flink JobManager REST 地址。 */
    private String jobManagerRest = "http://localhost:8081";

    /** Flink 并行度（与 L2.3 流计算详细设计对齐）。 */
    private int parallelism = 4;

    /** Flink JobManager 内存。 */
    private String jobManagerMemory = "1024m";

    /** Flink TaskManager 内存。 */
    private String taskManagerMemory = "2048m";

    /** Flink TaskManager Slots。 */
    private int taskManagerSlots = 2;

    /** Flink Checkpoint 间隔（毫秒）。 */
    private long checkpointIntervalMs = 30000;

    /** Flink Checkpoint 存储路径。 */
    private String checkpointPath = "s3://shuqing-warehouse/flink-checkpoints";

    /** Iceberg Flink Connector jar 路径。 */
    private String flinkJars = "";

    /** Flink Conf 额外配置。 */
    private java.util.Map<String, String> extraConf = new java.util.HashMap<>();
}