package com.shuqing.bigdata.streambatch.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;

/**
 * DAG 节点（扩展 DolphinScheduler DAG 节点模型）。
 *
 * <p>每个节点代表一个流批任务（Spark 批 / Flink 流 / 统一节点），
 * 携带任务类型、Iceberg 表引用、snapshot 隔离参数等信息。
 *
 * <p>关键字段：
 * <ul>
 *   <li>{@code taskType} — 任务类型，决定节点走批通道还是流通道</li>
 *   <li>{@code icebergTable} — 节点读写的 Iceberg 表全名（database.table）</li>
 *   <li>{@code snapshotId} — 批节点固定的 snapshot-id；流节点为 null（读最新）</li>
 *   <li>{@code snapshotIsolationEnabled} — 是否启用 snapshot 隔离</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DagNode {

    /** 节点 ID（DAG 内唯一）。 */
    @NotBlank
    private String nodeId;

    /** 节点名称（展示用）。 */
    @NotBlank
    private String name;

    /** 任务类型。 */
    @NotNull
    private TaskType taskType;

    /** Iceberg 表全名（database.table），节点读写目标。 */
    @NotBlank
    private String icebergTable;

    /**
     * 批节点固定的 snapshot-id。
     * <p>仅当 {@link #taskType} 为 {@link TaskType#SPARK_BATCH} 时有效；
     * 流节点（{@link TaskType#FLINK_STREAM}）为 null，表示读最新 snapshot。
     */
    private Long snapshotId;

    /** 是否启用 snapshot 隔离（默认 true）。 */
    @Builder.Default
    private boolean snapshotIsolationEnabled = true;

    /** 任务主资源（Spark/Flink 作业 jar 或 SQL 文件路径）。 */
    private String mainResource;

    /** 主类（Spark 作业 mainClass；Flink 作业 entryClass）。 */
    private String mainClass;

    /** 任务参数（命令行 args）。 */
    private String taskArgs;

    /** 并行度（Flink 流任务 parallelism；Spark 批任务 spark.executor.instances）。 */
    private Integer parallelism;

    /** 节点自定义配置（透传给 TaskChannel）。 */
    @Builder.Default
    private Map<String, String> extraConfig = new HashMap<>();

    /**
     * 判断本节点是否为批节点。
     *
     * @return {@code true} 表示批节点（Spark 批或统一节点中的批部分）
     */
    public boolean isBatchNode() {
        return taskType == TaskType.SPARK_BATCH || taskType == TaskType.UNIFIED_STREAM_BATCH;
    }

    /**
     * 判断本节点是否为流节点。
     *
     * @return {@code true} 表示流节点（Flink 流或统一节点中的流部分）
     */
    public boolean isStreamNode() {
        return taskType == TaskType.FLINK_STREAM || taskType == TaskType.UNIFIED_STREAM_BATCH;
    }

    /**
     * 判断本节点是否需要固定 snapshot（批节点且启用隔离）。
     *
     * @return {@code true} 表示批读固定 snapshot
     */
    public boolean requireFixedSnapshot() {
        return isBatchNode() && snapshotIsolationEnabled;
    }
}