package com.shuqing.bigdata.streambatch.flink;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Flink 流作业提交结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlinkSubmitResult {

    /** Flink 作业 ID。 */
    private String jobId;

    /** 流作业起始 Iceberg snapshot-id（流读起点）。 */
    private long startSnapshotId;

    /** 提交请求 JSON（用于日志与测试验证）。 */
    private String submitPayload;

    /** 并行度。 */
    private int parallelism;

    /** 提交是否成功。 */
    private boolean success;

    /** 错误信息（失败时填充）。 */
    private String errorMessage;
}