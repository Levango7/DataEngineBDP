package com.levango7.dataenginebdp.federated.merge;

/**
 * 归并策略枚举。
 */
public enum MergeStrategy {

    /** 简单拼接：将各集群结果按顺序拼接（适用于分片表查询）。 */
    CONCAT,

    /** UNION 归并：去重合并（适用于 UNION ALL/UNION 语义）。 */
    UNION,

    /** JOIN 归并：跨集群 Join（复用 Phase 1 T013 跨源 Join 归并器）。 */
    JOIN,

    /** 聚合归并：对各集群的聚合结果做二次聚合（SUM/COUNT/MIN/MAX 可直接合并）。 */
    AGGREGATE;

    public static MergeStrategy fromString(String s) {
        if (s == null || s.isBlank()) {
            return CONCAT;
        }
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CONCAT;
        }
    }
}