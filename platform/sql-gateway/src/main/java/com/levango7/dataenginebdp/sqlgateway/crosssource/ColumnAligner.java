package com.levango7.dataenginebdp.sqlgateway.crosssource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 跨源归并列对齐器。
 *
 * <p>不同数据源返回的结果集在列顺序、列名大小写、列名缺失等方面可能不一致。
 * 直接按位置归并会导致数据错位，例如：</p>
 * <pre>
 *   源 A（Trino）: columns=[id, name, age],   rows=[[1, "alice", 30]]
 *   源 B（Doris）: columns=[age, id, name],   rows=[[25, 2, "bob"]]   // 列顺序不同
 *   源 C（ES）:    columns=[ID, NAME, AGE],   rows=[[3, "carol", 28]] // 列名大小写不同
 * </pre>
 * <p>若直接按位置 UNION，结果会变成 {@code [[1,"alice",30], [25,2,"bob"], [3,"carol",28]]}，
 * 第二行 age=25 被放到 id 列位置，导致数据错位。</p>
 *
 * <p>本类提供按列名对齐的能力：</p>
 * <ul>
 *   <li>{@link #alignRow} — 将源行数据按目标列顺序重新排列；</li>
 *   <li>{@link #alignResult} — 将整个 {@link MergeResult} 对齐到目标列定义；</li>
 *   <li>{@link #unionColumns} — 计算多个结果集的并集列定义（按首次出现顺序）。</li>
 * </ul>
 *
 * <p>列名匹配大小写无关，对齐时缺失列填充 null。本类为无状态工具类，所有方法线程安全。</p>
 *
 * @author shuqing-bigdata
 */
public final class ColumnAligner {

    /** 缺失列的填充值 */
    public static final Object MISSING_VALUE = null;

    private ColumnAligner() {
        // 工具类，禁止实例化
    }

    /**
     * 将源行数据按目标列顺序重新排列。
     *
     * <p>对齐规则：</p>
     * <ol>
     *   <li>建立 源列名 → 源列索引 的映射（大小写无关）；</li>
     *   <li>对每个目标列，在源列映射中查找对应索引；</li>
     *   <li>找到则取源行对应位置的值，找不到则填充 {@link #MISSING_VALUE}（null）。</li>
     * </ol>
     *
     * @param targetColumns 目标列名列表（定义输出顺序）
     * @param sourceColumns 源列名列表
     * @param sourceRow     源行数据
     * @return 对齐后的新行；输入 null 返回空列表
     */
    public static List<Object> alignRow(List<String> targetColumns,
                                        List<String> sourceColumns,
                                        List<Object> sourceRow) {
        if (targetColumns == null || targetColumns.isEmpty()) {
            return new ArrayList<>();
        }
        if (sourceRow == null) {
            return new ArrayList<>(Collections.nCopies(targetColumns.size(), MISSING_VALUE));
        }

        // 构建 源列名（小写） → 源列索引
        Map<String, Integer> sourceIndexMap = buildColumnIndexMap(sourceColumns);

        List<Object> out = new ArrayList<>(targetColumns.size());
        for (String targetCol : targetColumns) {
            String key = targetCol == null ? null : targetCol.trim().toLowerCase(Locale.ROOT);
            Integer idx = sourceIndexMap.get(key);
            if (idx != null && idx < sourceRow.size()) {
                out.add(sourceRow.get(idx));
            } else {
                out.add(MISSING_VALUE);
            }
        }
        return out;
    }

    /**
     * 将整个 {@link MergeResult} 对齐到目标列定义。
     *
     * <p>对每一行执行 {@link #alignRow}，返回新的 {@link MergeResult}（保留原 source 和 durationMs）。</p>
     *
     * @param targetColumns 目标列名列表
     * @param source        源结果
     * @return 对齐后的新结果；输入 null 返回空结果
     */
    public static MergeResult alignResult(List<String> targetColumns, MergeResult source) {
        if (source == null) {
            return new MergeResult(targetColumns, new ArrayList<>(), "unknown", 0);
        }
        List<String> sourceCols = source.getColumns();
        List<List<Object>> alignedRows = new ArrayList<>(source.getRowCount());
        for (List<Object> row : source.getRows()) {
            alignedRows.add(alignRow(targetColumns, sourceCols, row));
        }
        MergeResult result = new MergeResult(targetColumns, alignedRows,
                source.getSource(), source.getDurationMs());
        return result;
    }

    /**
     * 计算多个结果集的并集列定义（按首次出现顺序）。
     *
     * <p>用于 UNION 场景：当不同结果集的列名不同时，取所有列的并集作为输出列。
     * 列名大小写无关去重，保留首次出现的大小写形式。</p>
     *
     * @param results 多个结果集
     * @return 并集列名列表；输入 null 或空返回空列表
     */
    public static List<String> unionColumns(List<MergeResult> results) {
        if (results == null || results.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> unionCols = new ArrayList<>();
        Map<String, String> seenLowerToOriginal = new LinkedHashMap<>();
        for (MergeResult r : results) {
            if (r == null || r.getColumns() == null) {
                continue;
            }
            for (String col : r.getColumns()) {
                if (col == null) {
                    continue;
                }
                String lower = col.trim().toLowerCase(Locale.ROOT);
                if (!seenLowerToOriginal.containsKey(lower)) {
                    seenLowerToOriginal.put(lower, col);
                    unionCols.add(col);
                }
            }
        }
        return unionCols;
    }

    /**
     * 校验源列是否与目标列兼容（每个目标列都能在源列中找到匹配，或源列数 >= 目标列数）。
     *
     * @param targetColumns 目标列名列表
     * @param sourceColumns 源列名列表
     * @return true 表示可对齐
     */
    public static boolean isCompatible(List<String> targetColumns, List<String> sourceColumns) {
        if (targetColumns == null || targetColumns.isEmpty()) {
            return true;
        }
        Map<String, Integer> sourceIndexMap = buildColumnIndexMap(sourceColumns);
        for (String col : targetColumns) {
            String key = col == null ? null : col.trim().toLowerCase(Locale.ROOT);
            if (!sourceIndexMap.containsKey(key)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构建 列名（小写） → 列索引 的映射。
     *
     * <p>若存在重复列名（大小写无关），保留第一个出现的索引。</p>
     *
     * @param columns 列名列表
     * @return 列名 → 索引映射
     */
    private static Map<String, Integer> buildColumnIndexMap(List<String> columns) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (columns == null) {
            return map;
        }
        for (int i = 0; i < columns.size(); i++) {
            String col = columns.get(i);
            if (col == null) {
                continue;
            }
            String key = col.trim().toLowerCase(Locale.ROOT);
            map.putIfAbsent(key, i);
        }
        return map;
    }
}