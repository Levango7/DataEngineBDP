package com.levango7.dataenginebdp.sqlgateway.optimizer;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 优化结果模型。
 *
 * <p>封装从原始 SQL 到优化执行计划的完整信息，包括：
 * 原始 SQL、优化后 SQL（如有改写）、执行计划文本、应用的优化规则列表、
 * 估算代价、表访问顺序、优化建议。</p>
 *
 * @author shuqing-bigdata
 */
@Data
@Builder
public class OptimizationResult {

    /** 原始 SQL */
    private final String originalSql;

    /** 优化后 SQL（如有改写，否则与 originalSql 相同） */
    private final String optimizedSql;

    /** 执行计划文本（类 EXPLAIN 输出） */
    private final String executionPlan;

    /** 应用的优化规则列表（如 FilterPushDown、ColumnPruning） */
    @Builder.Default
    private final List<String> rulesApplied = new ArrayList<>();

    /** 估算代价（无量纲，越大越昂贵） */
    private final double estimatedCost;

    /** 估算结果行数 */
    private final double estimatedRows;

    /** 表访问顺序（优化后） */
    @Builder.Default
    private final List<String> tableAccesses = new ArrayList<>();

    /** 优化建议（启发式提示，如"建议添加索引"） */
    @Builder.Default
    private final List<String> suggestions = new ArrayList<>();

    /** 优化是否成功 */
    private final boolean success;

    /** 错误信息（success=false 时） */
    private final String error;

    /** SQL 方言 */
    private final String dialect;

    /**
     * 构造一个失败结果。
     *
     * @param sql    原始 SQL
     * @param error  错误信息
     * @return 失败结果
     */
    public static OptimizationResult failure(String sql, String error) {
        return OptimizationResult.builder()
                .originalSql(sql)
                .optimizedSql(sql)
                .executionPlan("")
                .estimatedCost(0)
                .estimatedRows(0)
                .rulesApplied(Collections.emptyList())
                .tableAccesses(Collections.emptyList())
                .suggestions(Collections.emptyList())
                .success(false)
                .error(error)
                .build();
    }
}