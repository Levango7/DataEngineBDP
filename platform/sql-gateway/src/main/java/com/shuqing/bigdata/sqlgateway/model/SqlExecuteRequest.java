package com.shuqing.bigdata.sqlgateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * SQL 执行请求 POJO。
 *
 * <p>由客户端 POST 至 {@code /api/v1/sql/execute} 时提交。</p>
 *
 * @author shuqing-bigdata
 */
@Data
public class SqlExecuteRequest {

    /**
     * 待执行的 SQL 语句（必填）。
     */
    @NotBlank(message = "sql 不能为空")
    private String sql;

    /**
     * 目标引擎：{@code trino} 或 {@code doris}。
     * <p>若为空，则由路由规则或默认引擎决定。</p>
     */
    @Pattern(regexp = "trino|doris", message = "engine 只能为 trino 或 doris")
    private String engine;

    /**
     * 租户 ID，用于多租户隔离与审计。
     */
    private String tenantId;

    /**
     * 返回行数上限，{@code null} 表示不限制。
     */
    private Integer limit;
}