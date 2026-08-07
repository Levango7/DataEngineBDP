package com.shuqing.bigdata.streambatch.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Iceberg snapshot 引用。
 *
 * <p>封装 Iceberg 表的 snapshot-id 与 snapshot 时间戳，
 * 用于 snapshot 隔离配置与验证。批节点引用固定 snapshot，
 * 流节点引用最新 snapshot。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SnapshotRef {

    /** Iceberg 表全名（database.table）。 */
    private String table;

    /** snapshot-id。 */
    private long snapshotId;

    /** snapshot 时间戳（毫秒）。 */
    private long timestampMs;

    /** 是否为最新 snapshot。 */
    private boolean latest;

    /** snapshot 摘要（含记录数等元数据，来自 Iceberg snapshot.summary）。 */
    private java.util.Map<String, String> summary;
}