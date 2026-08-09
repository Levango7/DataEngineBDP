package com.levango7.dataenginebdp.streambatch.iceberg;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Iceberg snapshot 隔离配置。
 *
 * <p>配置 Iceberg Catalog 连接信息与 snapshot 隔离策略，由
 * {@link IcebergSnapshotManager} 读取以管理 snapshot。
 *
 * <p>核心策略：
 * <ul>
 *   <li><b>批读固定 snapshot</b> — Spark 批作业在启动时锁定当前 snapshot-id，
 *       整个批作业期间读该固定 snapshot，不受后续 Flink 流写入影响</li>
 *   <li><b>流读最新 snapshot</b> — Flink 流作业持续读 Iceberg 最新 snapshot
 *       （通过 Flink Iceberg Source 的 streaming 模式），实时消费增量数据</li>
 * </ul>
 */
@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "shuqing.stream-batch.iceberg")
public class SnapshotIsolationConfig {

    /** Iceberg Catalog 类型（hive / rest / jdbc / hadoop）。 */
    private String catalogType = "hive";

    /** Iceberg Catalog 名称。 */
    private String catalogName = "shuqing_catalog";

    /** Iceberg Catalog URI（Hive Metastore Thrift URI 或 REST Catalog URI）。 */
    private String catalogUri = "thrift://localhost:9083";

    /** Iceberg Warehouse 路径。 */
    private String warehouse = "s3://shuqing-warehouse/iceberg";

    /**
     * 批读 snapshot 锁定模式：
     * <ul>
     *   <li>{@code AT_JOB_START} — 批作业启动时锁定当前 snapshot（默认）</li>
     *   <li>{@code EXPLICIT} — 由 DAG 节点显式指定 snapshot-id</li>
     * </ul>
     */
    private String batchSnapshotLockMode = "AT_JOB_START";

    /**
     * 流读 snapshot 模式：
     * <ul>
     *   <li>{@code LATEST} — 读最新 snapshot（streaming 模式，默认）</li>
     *   <li>{@code FROM_TIMESTAMP} — 从指定时间戳开始流读</li>
     * </ul>
     */
    private String streamSnapshotMode = "LATEST";

    /** 流读起始时间戳（FROM_TIMESTAMP 模式生效，毫秒）。 */
    private Long streamFromTimestampMs;

    /** S3 访问密钥（访问 Iceberg Warehouse）。 */
    private String s3AccessKey;

    /** S3 秘密密钥。 */
    private String s3SecretKey;

    /** S3 endpoint。 */
    private String s3Endpoint = "http://localhost:9000";

    /** 是否启用 snapshot 隔离验证（DAG 执行后自动验证批流一致）。 */
    private boolean isolationValidationEnabled = true;

    /**
     * snapshot 隔离验证容忍度（批与流 snapshot 之间的最大允许时间差毫秒）。
     * <p>批流完全一致要求 0；实际场景允许小范围延迟。
     */
    private long isolationToleranceMs = 0;

    /** Iceberg 表属性覆盖（table → properties）。 */
    private Map<String, Map<String, String>> tableProperties = new HashMap<>();

    /**
     * 构建 Iceberg Catalog 配置 Map（传给 Spark/Flink 作业）。
     *
     * @return Catalog 配置键值对
     */
    public Map<String, String> buildCatalogProperties() {
        Map<String, String> props = new HashMap<>();
        props.put("type", catalogType);
        props.put("uri", catalogUri);
        props.put("warehouse", warehouse);
        if (s3AccessKey != null) {
            props.put("s3.access-key-id", s3AccessKey);
            props.put("s3.secret-access-key", s3SecretKey);
            props.put("s3.endpoint", s3Endpoint);
        }
        log.debug("构建 Iceberg Catalog 配置: type={}, uri={}, warehouse={}",
                catalogType, catalogUri, warehouse);
        return props;
    }
}