package com.levango7.dataenginebdp.storage.replication;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个对象的复制结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplicationItemResult {

    /** 相对对象键。 */
    private String key;

    /** 决策 / 结果动作。 */
    private ReplicationAction action;

    /** 源对象字节数。 */
    private long sourceSize;

    /** 目标对象字节数（COPY/OVERWRITE 为写入长度；SKIP 为复制前目标大小）。 */
    private long targetSize;

    /** 源内容 sha256（流式读取时计算；SKIP / FAILED 可能为 null）。 */
    private String sourceSha256;

    /** 目标读回内容 sha256（仅 COPY/OVERWRITE 计算）。 */
    private String targetSha256;

    /**
     * 目标读回内容是否与源逐字节一致。仅对 COPY / OVERWRITE 有意义：这两类动作会传输并独立读回校验。
     * SKIP 未传输字节，恒为 {@code false}，语义是“不适用”，而非“校验失败”。
     */
    private boolean verified;

    /** 单对象耗时（毫秒）。 */
    private long elapsedMs;

    /** 失败原因（仅 FAILED）。 */
    private String error;
}
