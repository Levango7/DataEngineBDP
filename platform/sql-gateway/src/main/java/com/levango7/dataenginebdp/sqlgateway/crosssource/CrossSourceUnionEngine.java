package com.levango7.dataenginebdp.sqlgateway.crosssource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 跨源 UNION 引擎。
 *
 * <p>在内存中对多个 {@link MergeResult}（分别来自不同数据源）执行集合操作：</p>
 * <ul>
 *   <li>{@code UNION_ALL}     — 直接拼接，保留重复行；</li>
 *   <li>{@code UNION_DISTINCT} — 拼接后去重（等价于 SQL UNION）；</li>
 *   <li>{@code INTERSECT}     — 取交集；</li>
 *   <li>{@code EXCEPT}        — 取差集（第一个结果集减去后续所有结果集）。</li>
 * </ul>
 *
 * <p>要求所有输入结果集的列数相同（列名可不同，按位置对齐）。
 * 去重使用 {@link LinkedHashSet}，基于行的字符串指纹判断重复。</p>
 *
 * <p>结果集行数受 {@code maxRows} 限制（默认 {@link MergeResult#DEFAULT_MAX_ROWS}），
 * 超限时抛 {@link CrossSourceException}（错误码 {@code RESULT_TOO_LARGE}）。</p>
 *
 * @author shuqing-bigdata
 */
public class CrossSourceUnionEngine {

    /** 默认结果集行数上限 */
    private static final int DEFAULT_MAX_ROWS = MergeResult.DEFAULT_MAX_ROWS;

    private final int maxRows;

    /**
     * 默认构造（使用默认行数上限）。
     */
    public CrossSourceUnionEngine() {
        this(DEFAULT_MAX_ROWS);
    }

    /**
     * 指定结果集行数上限。
     *
     * @param maxRows 最大行数
     */
    public CrossSourceUnionEngine(int maxRows) {
        this.maxRows = maxRows > 0 ? maxRows : DEFAULT_MAX_ROWS;
    }

    /**
     * 对多个结果集执行 UNION 操作。
     *
     * @param results   多个结果集
     * @param unionType 集合操作类型
     * @return 归并结果
     * @throws CrossSourceException 归并失败
     */
    public MergeResult union(List<MergeResult> results, UnionType unionType) {
        if (results == null || results.isEmpty()) {
            throw new CrossSourceException(CrossSourceException.MERGE_ERROR,
                    "UNION 输入结果列表不能为空");
        }
        if (unionType == null) {
            throw new CrossSourceException(CrossSourceException.MERGE_ERROR,
                    "UNION 类型不能为 null");
        }
        long start = System.currentTimeMillis();

        // 校验列数一致
        validateColumnCounts(results);

        // 取第一个结果的列定义作为输出列
        List<String> outColumns = new ArrayList<>(results.get(0).getColumns());

        switch (unionType) {
            case UNION_ALL:
                return unionAll(results, outColumns, start);
            case UNION_DISTINCT:
                return unionDistinct(results, outColumns, start);
            case INTERSECT:
                return intersect(results, outColumns, start);
            case EXCEPT:
                return except(results, outColumns, start);
            default:
                throw new CrossSourceException(CrossSourceException.UNSUPPORTED,
                        "不支持的 UNION 类型: " + unionType);
        }
    }

    /**
     * UNION ALL：直接拼接。
     */
    private MergeResult unionAll(List<MergeResult> results, List<String> outColumns, long start) {
        List<List<Object>> merged = new ArrayList<>();
        for (MergeResult r : results) {
            for (List<Object> row : r.getRows()) {
                merged.add(new ArrayList<>(row));
                if (merged.size() > maxRows) {
                    throw new CrossSourceException(CrossSourceException.RESULT_TOO_LARGE,
                            "UNION ALL 结果超过上限 " + maxRows + " 行");
                }
            }
        }
        long duration = System.currentTimeMillis() - start;
        return new MergeResult(outColumns, merged, "merged", duration);
    }

    /**
     * UNION DISTINCT：拼接后去重。
     */
    private MergeResult unionDistinct(List<MergeResult> results, List<String> outColumns, long start) {
        Set<String> seen = new LinkedHashSet<>();
        List<List<Object>> merged = new ArrayList<>();
        for (MergeResult r : results) {
            for (List<Object> row : r.getRows()) {
                String finger = fingerprint(row);
                if (seen.add(finger)) {
                    merged.add(new ArrayList<>(row));
                    if (merged.size() > maxRows) {
                        throw new CrossSourceException(CrossSourceException.RESULT_TOO_LARGE,
                                "UNION DISTINCT 结果超过上限 " + maxRows + " 行");
                    }
                }
            }
        }
        long duration = System.currentTimeMillis() - start;
        return new MergeResult(outColumns, merged, "merged", duration);
    }

    /**
     * INTERSECT：取所有结果集的交集。
     */
    private MergeResult intersect(List<MergeResult> results, List<String> outColumns, long start) {
        // 第一个集合
        Set<String> first = new LinkedHashSet<>();
        for (List<Object> row : results.get(0).getRows()) {
            first.add(fingerprint(row));
        }
        // 与后续每个集合取交集
        for (int i = 1; i < results.size(); i++) {
            Set<String> next = new LinkedHashSet<>();
            for (List<Object> row : results.get(i).getRows()) {
                next.add(fingerprint(row));
            }
            first.retainAll(next);
        }
        // 重建行（从第一个结果集中按指纹匹配）
        List<List<Object>> merged = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (List<Object> row : results.get(0).getRows()) {
            String finger = fingerprint(row);
            if (first.contains(finger) && emitted.add(finger)) {
                merged.add(new ArrayList<>(row));
                if (merged.size() > maxRows) {
                    throw new CrossSourceException(CrossSourceException.RESULT_TOO_LARGE,
                            "INTERSECT 结果超过上限 " + maxRows + " 行");
                }
            }
        }
        long duration = System.currentTimeMillis() - start;
        return new MergeResult(outColumns, merged, "merged", duration);
    }

    /**
     * EXCEPT：第一个结果集减去后续所有结果集。
     */
    private MergeResult except(List<MergeResult> results, List<String> outColumns, long start) {
        // 收集后续所有结果集的指纹
        Set<String> others = new LinkedHashSet<>();
        for (int i = 1; i < results.size(); i++) {
            for (List<Object> row : results.get(i).getRows()) {
                others.add(fingerprint(row));
            }
        }
        // 从第一个结果集中过滤掉出现在 others 中的行（去重）
        List<List<Object>> merged = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (List<Object> row : results.get(0).getRows()) {
            String finger = fingerprint(row);
            if (!others.contains(finger) && emitted.add(finger)) {
                merged.add(new ArrayList<>(row));
                if (merged.size() > maxRows) {
                    throw new CrossSourceException(CrossSourceException.RESULT_TOO_LARGE,
                            "EXCEPT 结果超过上限 " + maxRows + " 行");
                }
            }
        }
        long duration = System.currentTimeMillis() - start;
        return new MergeResult(outColumns, merged, "merged", duration);
    }

    // ===================== 内部工具 =====================

    /**
     * 校验所有结果集列数一致。
     */
    private void validateColumnCounts(List<MergeResult> results) {
        if (results.size() == 1) {
            return;
        }
        int expected = results.get(0).getColumns().size();
        for (int i = 1; i < results.size(); i++) {
            int actual = results.get(i).getColumns().size();
            if (actual != expected) {
                throw new CrossSourceException(CrossSourceException.MERGE_ERROR,
                        "UNION 输入结果列数不一致: result[0]=" + expected
                                + ", result[" + i + "]=" + actual);
            }
        }
    }

    /**
     * 计算行的指纹（用于去重/集合比较）。
     * <p>将每列值转为字符串并用分隔符拼接，null 转为 {@code <NULL>}。</p>
     */
    private String fingerprint(List<Object> row) {
        if (row == null) {
            return "<NULL_ROW>";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('|');
        for (Object cell : row) {
            if (cell == null) {
                sb.append("<NULL>");
            } else if (cell instanceof Number n) {
                // 数值统一为 Double 字符串，避免 1 vs 1.0 误判
                sb.append(n.doubleValue());
            } else if (cell instanceof String s) {
                sb.append(s.trim().toUpperCase(Locale.ROOT));
            } else {
                sb.append(cell.toString());
            }
            sb.append('|');
        }
        return sb.toString();
    }

    /**
     * 获取结果集行数上限。
     *
     * @return 行数上限
     */
    public int getMaxRows() {
        return maxRows;
    }

    /**
     * UNION 操作类型枚举。
     */
    public enum UnionType {
        /** UNION ALL：保留重复行 */
        UNION_ALL,
        /** UNION DISTINCT：去重 */
        UNION_DISTINCT,
        /** INTERSECT：交集 */
        INTERSECT,
        /** EXCEPT：差集 */
        EXCEPT
    }

    /**
     * 从字符串解析 UnionType（大小写无关）。
     *
     * @param name 类型名称
     * @return UnionType；未识别返回 {@link UnionType#UNION_ALL}
     */
    public static UnionType parseUnionType(String name) {
        if (name == null || name.isBlank()) {
            return UnionType.UNION_ALL;
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "UNION_ALL", "UNION ALL" -> UnionType.UNION_ALL;
            case "UNION_DISTINCT", "UNION DISTINCT", "UNION" -> UnionType.UNION_DISTINCT;
            case "INTERSECT" -> UnionType.INTERSECT;
            case "EXCEPT" -> UnionType.EXCEPT;
            default -> UnionType.UNION_ALL;
        };
    }

    /**
     * 列出所有支持的 UNION 类型。
     *
     * @return 类型名称列表
     */
    public static List<String> supportedUnionTypes() {
        return Collections.unmodifiableList(List.of(
                "UNION_ALL", "UNION_DISTINCT", "INTERSECT", "EXCEPT"));
    }
}