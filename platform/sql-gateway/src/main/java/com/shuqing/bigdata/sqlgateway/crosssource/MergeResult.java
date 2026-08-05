package com.shuqing.bigdata.sqlgateway.crosssource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 跨源归并结果。
 *
 * <p>跨源执行器在并行查询多个数据源后，将每个源的结果统一封装为 {@code MergeResult}，
 * 再由 JOIN/UNION 引擎在内存中归并，最终输出一个 {@code MergeResult} 给上层。</p>
 *
 * <p>结构说明：</p>
 * <ul>
 *   <li>{@code columns} — 列定义（列名列表，与每行数据顺序对齐）</li>
 *   <li>{@code rows}    — 数据行，每行为 Object 列表，元素类型由源决定</li>
 *   <li>{@code source}  — 结果来源标识（单源时为源名；归并后为 "merged"）</li>
 *   <li>{@code rowCount}— 行数（缓存，避免重复计算）</li>
 * </ul>
 *
 * <p>本类为可变 POJO，便于在归并过程中追加行；归并完成后可调用
 * {@link #toUnmodifiable()} 转为不可变视图返回给调用方。</p>
 *
 * @author shuqing-bigdata
 */
public class MergeResult {

    /** 默认结果集行数上限 */
    public static final int DEFAULT_MAX_ROWS = 10000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 列名列表 */
    private List<String> columns;
    /** 数据行 */
    private List<List<Object>> rows;
    /** 结果来源标识 */
    private String source;
    /** 行数（rows.size() 的缓存） */
    private int rowCount;
    /** 执行耗时（毫秒） */
    private long durationMs;

    /**
     * 默认构造（空结果）。
     */
    public MergeResult() {
        this.columns = new ArrayList<>();
        this.rows = new ArrayList<>();
        this.source = "unknown";
        this.rowCount = 0;
    }

    /**
     * 全参构造。
     *
     * @param columns    列名列表
     * @param rows       数据行
     * @param source     来源标识
     * @param durationMs 耗时
     */
    public MergeResult(List<String> columns, List<List<Object>> rows,
                       String source, long durationMs) {
        this.columns = columns == null ? new ArrayList<>() : new ArrayList<>(columns);
        this.rows = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
        this.source = source == null ? "unknown" : source;
        this.rowCount = this.rows.size();
        this.durationMs = durationMs;
    }

    /**
     * 创建空结果（仅含列定义）。
     *
     * @param columns 列名列表
     * @param source  来源标识
     * @return 空结果
     */
    public static MergeResult empty(List<String> columns, String source) {
        MergeResult r = new MergeResult();
        r.columns = columns == null ? new ArrayList<>() : new ArrayList<>(columns);
        r.source = source == null ? "unknown" : source;
        r.rows = new ArrayList<>();
        r.rowCount = 0;
        return r;
    }

    /**
     * 获取列名列表。
     *
     * @return 列名列表
     */
    public List<String> getColumns() {
        return columns;
    }

    /**
     * 设置列名列表。
     *
     * @param columns 列名列表
     */
    public void setColumns(List<String> columns) {
        this.columns = columns == null ? new ArrayList<>() : columns;
    }

    /**
     * 获取数据行。
     *
     * @return 数据行
     */
    public List<List<Object>> getRows() {
        return rows;
    }

    /**
     * 设置数据行并刷新 rowCount。
     *
     * @param rows 数据行
     */
    public void setRows(List<List<Object>> rows) {
        this.rows = rows == null ? new ArrayList<>() : rows;
        this.rowCount = this.rows.size();
    }

    /**
     * 获取来源标识。
     *
     * @return 来源标识
     */
    public String getSource() {
        return source;
    }

    /**
     * 设置来源标识。
     *
     * @param source 来源标识
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * 获取行数。
     *
     * @return 行数
     */
    public int getRowCount() {
        return rowCount;
    }

    /**
     * 获取执行耗时。
     *
     * @return 耗时（毫秒）
     */
    public long getDurationMs() {
        return durationMs;
    }

    /**
     * 设置执行耗时。
     *
     * @param durationMs 耗时（毫秒）
     */
    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    /**
     * 追加一行数据，自动刷新 rowCount。
     *
     * @param row 数据行
     */
    public void addRow(List<Object> row) {
        rows.add(row);
        rowCount = rows.size();
    }

    /**
     * 判断结果是否为空。
     *
     * @return {@code true} 表示无数据行
     */
    public boolean isEmpty() {
        return rows == null || rows.isEmpty();
    }

    /**
     * 获取指定列名对应的列索引。
     *
     * @param columnName 列名
     * @return 列索引；不存在返回 -1
     */
    public int indexOfColumn(String columnName) {
        if (columns == null || columnName == null) {
            return -1;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columnName.equalsIgnoreCase(columns.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 转为不可变视图（防止外部修改）。
     *
     * @return 不可变副本
     */
    public MergeResult toUnmodifiable() {
        MergeResult copy = new MergeResult();
        copy.columns = Collections.unmodifiableList(columns);
        copy.rows = Collections.unmodifiableList(rows);
        copy.source = source;
        copy.rowCount = rowCount;
        copy.durationMs = durationMs;
        return copy;
    }

    /**
     * 序列化为 JSON 字符串。
     *
     * <p>结构：{@code {"columns":[...], "rows":[[...]], "source":"...", "rowCount":N}}。</p>
     *
     * @return JSON 字符串
     * @throws CrossSourceException 序列化失败
     */
    public String toJson() {
        try {
            java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("columns", columns);
            map.put("rows", rows);
            map.put("source", source);
            map.put("rowCount", rowCount);
            map.put("durationMs", durationMs);
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new CrossSourceException(CrossSourceException.MERGE_ERROR,
                    "结果序列化为 JSON 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MergeResult other)) {
            return false;
        }
        return Objects.equals(columns, other.columns)
                && Objects.equals(rows, other.rows)
                && Objects.equals(source, other.source)
                && rowCount == other.rowCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(columns, rows, source, rowCount);
    }

    @Override
    public String toString() {
        return "MergeResult{source='" + source + "', columns=" + columns
                + ", rowCount=" + rowCount + ", durationMs=" + durationMs + '}';
    }
}