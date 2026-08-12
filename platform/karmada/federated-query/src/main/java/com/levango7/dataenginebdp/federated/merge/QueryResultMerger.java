package com.levango7.dataenginebdp.federated.merge;

import com.levango7.dataenginebdp.federated.model.ClusterQueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查询结果归并器（基于 Phase 1 T013 跨源 Join 归并器思想）。
 *
 * <p>支持四种归并策略：
 * <ul>
 *   <li>{@link MergeStrategy#CONCAT} - 简单拼接，保留各集群结果顺序</li>
 *   <li>{@link MergeStrategy#UNION} - 去重合并，按行内容去重</li>
 *   <li>{@link MergeStrategy#JOIN} - 跨集群 Join（本实现做嵌套循环归并，
 *       生产环境应委托给 Phase 1 CrossSourceJoinEngine）</li>
 *   <li>{@link MergeStrategy#AGGREGATE} - 聚合归并，对数值列求和</li>
 * </ul>
 *
 * <p>归并前提：各集群结果的列模式（schema）一致或可对齐。列对齐策略：
 * 取所有集群 schema 的并集，缺失列填 null。
 *
 * <p>性能：CONCAT/UNION 为 O(N) 线性扫描；JOIN 为 O(N*M) 嵌套循环
 * （仅适用于小结果集，大结果集应下推 Join 到集群侧）。
 */
@Slf4j
@Component
public class QueryResultMerger {

    /**
     * 归并多个集群的查询结果。
     *
     * @param results  各集群查询子结果
     * @param strategy 归并策略
     * @return 归并后的结果（rows + schema + totalRows）
     */
    public MergedResult merge(List<ClusterQueryResult> results, MergeStrategy strategy) {
        if (results == null || results.isEmpty()) {
            return new MergedResult(Collections.emptyList(), Collections.emptyMap(), 0);
        }

        // 仅归并成功的结果
        List<ClusterQueryResult> successResults = results.stream()
                .filter(ClusterQueryResult::isSuccess)
                .toList();
        if (successResults.isEmpty()) {
            log.warn("No successful cluster results to merge, returning empty");
            return new MergedResult(Collections.emptyList(), Collections.emptyMap(), 0);
        }

        // 对齐 schema：取并集
        Map<String, String> mergedSchema = mergeSchemas(successResults);

        List<Map<String, Object>> mergedRows = switch (strategy) {
            case CONCAT -> mergeConcat(successResults, mergedSchema);
            case UNION -> mergeUnion(successResults, mergedSchema);
            case JOIN -> mergeJoin(successResults, mergedSchema);
            case AGGREGATE -> mergeAggregate(successResults, mergedSchema);
        };

        log.debug("Merged {} cluster results with strategy {}: {} rows",
                successResults.size(), strategy, mergedRows.size());
        return new MergedResult(mergedRows, mergedSchema, mergedRows.size());
    }

    // ------------------------------------------------------------------
    // 各策略实现
    // ------------------------------------------------------------------

    /** CONCAT：按集群顺序拼接。 */
    private List<Map<String, Object>> mergeConcat(List<ClusterQueryResult> results, Map<String, String> schema) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (ClusterQueryResult r : results) {
            for (Map<String, Object> row : r.getRows()) {
                merged.add(alignRow(row, schema));
            }
        }
        return merged;
    }

    /** UNION：去重合并。 */
    private List<Map<String, Object>> mergeUnion(List<ClusterQueryResult> results, Map<String, String> schema) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> merged = new ArrayList<>();
        for (ClusterQueryResult r : results) {
            for (Map<String, Object> row : r.getRows()) {
                Map<String, Object> aligned = alignRow(row, schema);
                String key = rowKey(aligned);
                if (seen.add(key)) {
                    merged.add(aligned);
                }
            }
        }
        return merged;
    }

    /**
     * JOIN：跨集群嵌套循环归并。
     *
     * <p>简化实现：对相邻两个结果做笛卡尔积中按公共列等值连接。
     * 生产环境应委托给 Phase 1 CrossSourceJoinEngine（基于 Calcite 优化）。
     */
    private List<Map<String, Object>> mergeJoin(List<ClusterQueryResult> results, Map<String, String> schema) {
        if (results.size() == 1) {
            return mergeConcat(results, schema);
        }
        // 取前两个结果做 Join，结果再与后续结果依次 Join
        List<Map<String, Object>> acc = new ArrayList<>(results.get(0).getRows());
        for (int i = 1; i < results.size(); i++) {
            acc = nestedLoopJoin(acc, results.get(i).getRows());
        }
        // 对齐 schema
        List<Map<String, Object>> aligned = new ArrayList<>(acc.size());
        for (Map<String, Object> row : acc) {
            aligned.add(alignRow(row, schema));
        }
        return aligned;
    }

    /** AGGREGATE：对数值列求和，非数值列取首个非空值。 */
    private List<Map<String, Object>> mergeAggregate(List<ClusterQueryResult> results, Map<String, String> schema) {
        Map<String, Object> aggRow = new LinkedHashMap<>();
        // 初始化
        for (String col : schema.keySet()) {
            aggRow.put(col, null);
        }
        int count = 0;
        for (ClusterQueryResult r : results) {
            for (Map<String, Object> row : r.getRows()) {
                count++;
                for (String col : schema.keySet()) {
                    Object v = row.get(col);
                    if (v == null) continue;
                    Object cur = aggRow.get(col);
                    if (v instanceof Number n) {
                        double curNum = cur instanceof Number c ? c.doubleValue() : 0.0;
                        aggRow.put(col, curNum + n.doubleValue());
                    } else if (cur == null) {
                        aggRow.put(col, v);
                    }
                }
            }
        }
        // 若所有结果都为空，返回空列表
        if (count == 0) {
            return Collections.emptyList();
        }
        // 将求和后的 double 还原为整数（若为整数）
        Map<String, Object> finalRow = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : aggRow.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
                finalRow.put(e.getKey(), d.longValue());
            } else {
                finalRow.put(e.getKey(), v);
            }
        }
        return List.of(finalRow);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 合并各集群 schema：取并集，类型冲突时保留首个。 */
    private Map<String, String> mergeSchemas(List<ClusterQueryResult> results) {
        Map<String, String> schema = new LinkedHashMap<>();
        for (ClusterQueryResult r : results) {
            if (r.getSchema() != null) {
                for (Map.Entry<String, String> e : r.getSchema().entrySet()) {
                    schema.putIfAbsent(e.getKey(), e.getValue());
                }
            }
        }
        // 若 schema 为空，从行推断
        if (schema.isEmpty()) {
            for (ClusterQueryResult r : results) {
                if (r.getRows() != null && !r.getRows().isEmpty()) {
                    for (String col : r.getRows().get(0).keySet()) {
                        schema.putIfAbsent(col, "STRING");
                    }
                    break;
                }
            }
        }
        return schema;
    }

    /** 对齐行：补齐缺失列为 null，剔除 schema 外的多余列。 */
    private Map<String, Object> alignRow(Map<String, Object> row, Map<String, String> schema) {
        Map<String, Object> aligned = new LinkedHashMap<>();
        for (String col : schema.keySet()) {
            aligned.put(col, row != null ? row.get(col) : null);
        }
        return aligned;
    }

    /** 行唯一键（用于 UNION 去重）。 */
    private String rowKey(Map<String, Object> row) {
        return row.toString();
    }

    /** 嵌套循环等值 Join：按公共列等值连接。 */
    private List<Map<String, Object>> nestedLoopJoin(List<Map<String, Object>> left, List<Map<String, Object>> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return Collections.emptyList();
        }
        // 找公共列
        Set<String> leftCols = left.get(0).keySet();
        Set<String> rightCols = right.get(0).keySet();
        List<String> commonCols = new ArrayList<>();
        for (String c : leftCols) {
            if (rightCols.contains(c)) {
                commonCols.add(c);
            }
        }
        List<Map<String, Object>> joined = new ArrayList<>();
        for (Map<String, Object> l : left) {
            for (Map<String, Object> r : right) {
                if (commonCols.isEmpty() || equalsOnCols(l, r, commonCols)) {
                    Map<String, Object> row = new LinkedHashMap<>(l);
                    for (Map.Entry<String, Object> e : r.entrySet()) {
                        row.putIfAbsent(e.getKey(), e.getValue());
                    }
                    joined.add(row);
                }
            }
        }
        return joined;
    }

    private boolean equalsOnCols(Map<String, Object> l, Map<String, Object> r, List<String> cols) {
        for (String c : cols) {
            Object lv = l.get(c);
            Object rv = r.get(c);
            if (lv == null && rv == null) continue;
            if (lv == null || rv == null || !lv.equals(rv)) {
                return false;
            }
        }
        return true;
    }

    /** 归并结果。 */
    public record MergedResult(
            List<Map<String, Object>> rows,
            Map<String, String> schema,
            int totalRows) {}
}