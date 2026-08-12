package com.levango7.dataenginebdp.streambatch.spark;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Spark 批作业提交结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SparkSubmitResult {

    /** Spark 应用 ID。 */
    private String appId;

    /** 批作业使用的 Iceberg snapshot-id（固定）。 */
    private long snapshotId;

    /** 提交命令（用于日志与测试验证）。 */
    private String submitCommand;

    /** 提交是否成功。 */
    private boolean success;

    /** 错误信息（失败时填充）。 */
    private String errorMessage;
}