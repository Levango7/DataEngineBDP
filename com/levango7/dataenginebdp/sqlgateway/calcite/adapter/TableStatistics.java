package com.shuqing.bigdata.sqlgateway.calcite.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 表统计信息——Cost 估算的基础数据，描述一张表的物理特征。
 *
 * <p>本类对应 Calcite {@code RelMetadataQuery} 中 {@code getRowCount}/
 * {@code getDistinctRowCount}/{@code getAverageRowSize} 等元数据的统一封装。
 * 各数据源适配器在 {@link BaseAdapter#costEstimate} 中基于本类估算
 * CPU/IO/Network 三维 Cost。</p>
 *
 * <p>统计字段：</p>
 * <ul>
 *   <li>{@link #rowCount}：表总行数（来自数据源统计信息或采样估算）</li>
 *   <li>{@link #columnCardinalities}：各列基数（distinct 值数量），用于估算选择率</li>
 *   <li>{@link #averageRowSizeBytes}：平均行大小（字节），用于估算 IO/Network Cost</li>
 *   <li>{@link #partitionCount}：分区数（Iceberg/Doris 分区表），用于分区裁剪收益估算</li>
 * </ul>
 *
 * <p>典型来源：</p>
 * <ul>
 *   <li>Iceberg：表元数据中的 snapshot-summary 与 manifest 文件统计</li>
 *   <li>Doris：FE 统计信息表 {@code __internal_schema.column_statistics}</li>
 *   <li>Trino：Connector 提供的 {@code TableStatistics}</li>
 *   <li>IoTDB：设备数 × 测点数 × 时间范围采样</li>
 *   <li>ES：{@code _cat/indices} 的 docs.count 与 store.size</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public class TableStatistics {

    /** 默认行数（当无统计信息时的保守估算） */
    public static final long DEFAULT_ROW_COUNT = 10_000L;
    /** 默认平均行大小（字节） */
    public static final int DEFAULT_ROW_SIZE_BYTES = 100;
    /** 默认列基数（distinct 值数量） */
    public static final long DEFAULT_COLUMN_CARDINALITY = 100L;

    /** 表总行数 */
    private final long rowCount;
    /** 各列基数（列名 → distinct 值数量） */
    private final Map<String, Long> columnCardinalities;
    /** 平均行大小（字节） */
    private final int averageRowSizeBytes;
    /** 分区数（非分区表为 1） */
    private final int partitionCount;

    /**
     * 构造表统计信息。
     *
     * @param rowCount             表总行数（&gt; 0）
     * @param columnCardinalities  各列基数映射（null 视为空映射）
     * @param averageRowSizeBytes  平均行大小（字节，&gt; 0）
     * @param partitionCount       分区数（&lt; 1 视为 1）
     */
    public TableStatistics(long rowCount, Map<String, Long> columnCardinalities,
                           int averageRowSizeBytes, int partitionCount) {
        this.rowCount = rowCount > 0 ? rowCount : DEFAULT_ROW_COUNT;
        this.columnCardinalities = columnCardinalities == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(columnCardinalities));
        this.averageRowSizeBytes = averageRowSizeBytes > 0
                ? averageRowSizeBytes : DEFAULT_ROW_SIZE_BYTES;
        this.partitionCount = partitionCount > 0 ? partitionCount : 1;
    }

    /**
     * 创建默认统计信息（保守估算）。
     *
     * @return 默认统计信息实例
     */
    public static TableStatistics defaultStats() {
        return new TableStatistics(DEFAULT_ROW_COUNT, null, DEFAULT_ROW_SIZE_BYTES, 1);
    }

    /**
     * 创建指定行数的统计信息（其他字段默认）。
     *
     * @param rowCount 行数
     * @return 统计信息实例
     */
    public static TableStatistics ofRows(long rowCount) {
        return new TableStatistics(rowCount, null, DEFAULT_ROW_SIZE_BYTES, 1);
    }

    /**
     * 创建带列基数的统计信息。
     *
     * @param rowCount            行数
     * @param columnCardinalities 列基数映射
     * @return 统计信息实例
     */
    public static TableStatistics of(long rowCount, Map<String, Long> columnCardinalities) {
        return new TableStatistics(rowCount, columnCardinalities, DEFAULT_ROW_SIZE_BYTES, 1);
    }

    public long getRowCount() {
        return rowCount;
    }

    public Map<String, Long> getColumnCardinalities() {
        return columnCardinalities;
    }

    public int getAverageRowSizeBytes() {
        return averageRowSizeBytes;
    }

    public int getPartitionCount() {
        return partitionCount;
    }

    /**
     * 获取指定列的基数，未配置时返回 {@link #DEFAULT_COLUMN_CARDINALITY}。
     *
     * @param column 列名
     * @return 列基数
     */
    public long getColumnCardinality(String column) {
        if (column == null) {
            return DEFAULT_COLUMN_CARDINALITY;
        }
        return columnCardinalities.getOrDefault(column, DEFAULT_COLUMN_CARDINALITY);
    }

    /**
     * 估算等值谓词选择率（= 1 / 列基数）。
     *
     * @param column 列名
     * @return 选择率（0, 1]
     */
    public double equalitySelectivity(String column) {
        long card = getColumnCardinality(column);
        return card > 0 ? 1.0 / card : 1.0;
    }

    /**
     * 估算范围谓词选择率（默认 0.1，即过滤后保留 10% 行）。
     *
     * @return 范围选择率
     */
    public double rangeSelectivity() {
        return 0.1;
    }

    /**
     * 估算总数据大小（字节）。
     *
     * @return 总字节数
     */
    public long totalSizeBytes() {
        return rowCount * averageRowSizeBytes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TableStatistics that)) {
            return false;
        }
        return rowCount == that.rowCount
                && averageRowSizeBytes == that.averageRowSizeBytes
                && partitionCount == that.partitionCount
                && Objects.equals(columnCardinalities, that.columnCardinalities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rowCount, columnCardinalities, averageRowSizeBytes, partitionCount);
    }

    @Override
    public String toString() {
        return "TableStatistics{rows=" + rowCount
                + ", rowSize=" + averageRowSizeBytes + "B"
                + ", partitions=" + partitionCount
                + ", columnCards=" + columnCardinalities + '}';
    }
}