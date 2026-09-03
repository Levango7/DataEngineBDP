package com.levango7.dataenginebdp.streambatch.batchpipeline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * batch-pipeline 批次提交结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchSubmitResult {

    /** 是否提交成功（HTTP 202）。 */
    private boolean success;

    /** 批次 id（服务端接受后返回；用于后续状态轮询）。 */
    private String batchId;

    /** 提交的租户 id。 */
    private String tenantId;

    /** 错误信息（失败时填充）。 */
    private String errorMessage;

    public static BatchSubmitResult ok(String batchId, String tenantId) {
        return BatchSubmitResult.builder().success(true).batchId(batchId).tenantId(tenantId).build();
    }

    public static BatchSubmitResult fail(String errorMessage) {
        return BatchSubmitResult.builder().success(false).errorMessage(errorMessage).build();
    }
}
