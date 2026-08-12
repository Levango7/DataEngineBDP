package com.levango7.dataenginebdp.sqlgateway.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * SQL 优化响应 POJO。
 *
 * <p>包含优化后执行计划、应用的规则、估算代价、表访问顺序与优化建议。</p>
 *
 * @author shuqing-bigdata
 */
@Data
@Builder
public class SqlOptimizeResponse {

    /** 原始 SQL */
    private String originalSql;

    /** 优化后 SQL（如有改写） */
    private String optimizedSql;

    /** 执行计划文本 */
    private String executionPlan;

    /** 应用的优化规则列表 */
    private List<String> rulesApplied;

    /** 估算代价 */
    private double estimatedCost;

    /** 估算行数 */
    private double estimatedRows;

    /** 表访问顺序 */
    private List<String> tableAccesses;

    /** 优化建议 */
    private List<String> suggestions;

    /** 是否成功 */
    private boolean success;

    /** 错误信息 */
    private String error;

    /** 方言 */
    private String dialect;
}