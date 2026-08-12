package com.levango7.dataenginebdp.governance.realtime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 表元数据采集结果。
 *
 * <p>由 {@code MetadataCollector} 在收到 {@link CatalogCommitEvent} 后异步采集，
 * 包含 Iceberg 表的完整元信息：schema、分区、属性、快照统计等。
 * 采集延迟（commit → 采集完成）目标 ≤ 5s。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableMetadata implements Serializable {

    /** 完整表标识符 */
    private String tableIdentifier;

    /** 命名空间 */
    private String namespace;

    /** 表名 */
    private String tableName;

    /** Iceberg 表格式版本（1 或 2） */
    private int formatVersion;

    /** 字段 schema 列表（字段名、类型、可选性） */
    private List<FieldSchema> schema;

    /** 分区字段列表 */
    private List<String> partitionFields;

    /** 分区策略（identity、bucket、truncate、days、hours、months） */
    private String partitionStrategy;

    /** 表属性（write.format.default、commit.retry.num-retries 等） */
    private Map<String, String> properties;

    /** 当前 snapshot-id */
    private Long currentSnapshotId;

    /** 快照统计（total-data-files、total-records、total-data-files-bytes 等） */
    private Map<String, String> snapshotSummary;

    /** 表创建时间 */
    private Instant createdAt;

    /** 最后更新时间 */
    private Instant lastUpdatedAt;

    /** 元数据采集时间戳（本服务采集完成时刻） */
    private Instant collectedAt;

    /** 采集耗时（毫秒） */
    private long collectDurationMs;

    /** 字段 schema 内嵌类 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldSchema implements Serializable {
        /** 字段 ID（Iceberg 内部 int id） */
        private int fieldId;
        /** 字段名 */
        private String name;
        /** 字段类型（string、int、long、double、decimal(10,2)、list<string> 等） */
        private String type;
        /** 是否可选 */
        private boolean optional;
        /** 字段文档/注释 */
        private String doc;
    }
}