package com.levango7.dataenginebdp.streambatch.batchpipeline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * batch-pipeline 批次状态快照（GET /batches/{batchId} 的归一化结果）。
 *
 * <p>status 取值：queued / running / success / failed（服务端批次生命周期）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchStatusSnapshot {

    /** 批次 id。 */
    private String batchId;

    /** 批次状态（queued / running / success / failed）。 */
    private String status;

    /** 错误信息（failed 时填充）。 */
    private String errorMessage;

    /** 是否终态（success / failed）。 */
    public boolean isTerminal() {
        return "success".equals(status) || "failed".equals(status);
    }
}
