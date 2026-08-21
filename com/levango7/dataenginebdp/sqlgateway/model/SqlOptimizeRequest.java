package com.shuqing.bigdata.sqlgateway.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * SQL 优化请求 POJO。
 *
 * <p>由客户端 POST 至 {@code /api/v1/sql/optimize} 时提交。</p>
 *
 * @author shuqing-bigdata
 */
@Data
public class SqlOptimizeRequest {

    /** 待优化的 SQL 语句（必填） */
    @NotBlank(message = "sql 不能为空")
    private String sql;

    /** SQL 方言：{@code ANSI}/{@code HIVE}/{@code DORIS}/{@code TRINO}，空则自动检测 */
    private String dialect;

    /** 是否启用所有规则（覆盖默认配置） */
    private boolean enableAllRules;
}