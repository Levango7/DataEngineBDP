package com.levango7.dataenginebdp.streambatch.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 任务类型枚举（扩展 DolphinScheduler DAG 节点类型）。
 *
 * <p>支持四种节点类型：
 * <ul>
 *   <li>{@link #SPARK_BATCH} — Spark 批任务，读取 Iceberg 固定 snapshot</li>
 *   <li>{@link #FLINK_STREAM} — Flink 流任务，读取 Iceberg 最新 snapshot（流读）</li>
 *   <li>{@link #UNIFIED_STREAM_BATCH} — 流批统一节点，同一 DAG 内同时编排批与流</li>
 *   <li>{@link #BATCH_PIPELINE} — batch-pipeline 五阶段批处理流水线
 *       （data-quality 实体，经其 FastAPI 提交/查询批次）</li>
 * </ul>
 */
public enum TaskType {
    /** Spark 批任务（批读固定 snapshot）。 */
    SPARK_BATCH("SPARK_BATCH"),
    /** Flink 流任务（流读最新 snapshot）。 */
    FLINK_STREAM("FLINK_STREAM"),
    /** 流批统一节点（同一 DAG 内同时编排批与流）。 */
    UNIFIED_STREAM_BATCH("UNIFIED_STREAM_BATCH"),
    /** batch-pipeline 批处理流水线节点（提交/轮询 data-quality API）。 */
    BATCH_PIPELINE("BATCH_PIPELINE");

    private final String code;

    TaskType(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static TaskType fromCode(String code) {
        for (TaskType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的任务类型: " + code);
    }
}