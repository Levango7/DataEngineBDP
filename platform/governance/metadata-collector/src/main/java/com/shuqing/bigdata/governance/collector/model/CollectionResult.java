package com.shuqing.bigdata.governance.collector.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 元数据采集结果。
 *
 * <p>由 {@code MetadataCollector#collect} 返回，承载一次采集的统计信息与产物列表。
 * 采集失败的 {@link #success} 为 {@code false}，错误信息写入 {@link #errorMessage}。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CollectionResult {

    /** 采集是否成功 */
    private boolean success;

    /** 数据源 ID */
    private Long sourceId;

    /** 数据源名称 */
    private String sourceName;

    /** 数据源类型 */
    private String sourceType;

    /** 采集开始时间 */
    private LocalDateTime startedAt;

    /** 采集结束时间 */
    private LocalDateTime finishedAt;

    /** 采集耗时（毫秒） */
    private long durationMs;

    /** 采集到的数据库数 */
    private int databaseCount;

    /** 采集到的表数 */
    private int tableCount;

    /** 采集到的列数 */
    private int columnCount;

    /** 采集到的表元数据列表 */
    private List<TableMetadata> tables = new ArrayList<>();

    /** 错误信息（采集失败时填充） */
    private String errorMessage;

    /**
     * 构造一个成功的的结果骨架。
     *
     * @param sourceId   数据源 ID
     * @param sourceName 数据源名称
     * @param sourceType 数据源类型
     * @return 成功结果，调用方继续填充统计字段
     */
    public static CollectionResult success(Long sourceId, String sourceName, String sourceType) {
        CollectionResult r = new CollectionResult();
        r.setSuccess(true);
        r.setSourceId(sourceId);
        r.setSourceName(sourceName);
        r.setSourceType(sourceType);
        r.setStartedAt(LocalDateTime.now());
        return r;
    }

    /**
     * 构造一个失败的结果。
     *
     * @param sourceId    数据源 ID
     * @param sourceName  数据源名称
     * @param sourceType  数据源类型
     * @param errorMessage 错误信息
     * @return 失败结果
     */
    public static CollectionResult failure(Long sourceId, String sourceName, String sourceType,
                                           String errorMessage) {
        CollectionResult r = new CollectionResult();
        r.setSuccess(false);
        r.setSourceId(sourceId);
        r.setSourceName(sourceName);
        r.setSourceType(sourceType);
        r.setStartedAt(LocalDateTime.now());
        r.setErrorMessage(errorMessage);
        return r;
    }

    /**
     * 标记采集结束，计算耗时。
     */
    public void markFinished() {
        this.finishedAt = LocalDateTime.now();
        if (this.startedAt != null) {
            this.durationMs = java.time.Duration.between(startedAt, finishedAt).toMillis();
        }
        // 自动汇总表/列计数
        if (tables != null) {
            this.tableCount = tables.size();
            this.columnCount = tables.stream()
                    .mapToInt(t -> t.getColumns() == null ? 0 : t.getColumns().size())
                    .sum();
        }
    }
}