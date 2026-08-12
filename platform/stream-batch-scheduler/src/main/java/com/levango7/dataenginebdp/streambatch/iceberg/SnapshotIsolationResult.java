package com.levango7.dataenginebdp.streambatch.iceberg;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * snapshot 隔离验证结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SnapshotIsolationResult {

    /** Iceberg 表全名。 */
    private String table;

    /** 批节点使用的 snapshot-id。 */
    private long batchSnapshotId;

    /** 流节点使用的 snapshot-id。 */
    private long streamSnapshotId;

    /** 批流 snapshot 时间差（毫秒）。 */
    private long timestampDiffMs;

    /** 隔离验证是否通过。 */
    private boolean valid;

    /** 验证详情描述。 */
    private String detail;
}