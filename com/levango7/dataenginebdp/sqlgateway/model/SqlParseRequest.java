package com.shuqing.bigdata.sqlgateway.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * SQL 解析请求 POJO。
 *
 * <p>由客户端 POST 至 {@code /api/v1/sql/parse} 时提交。</p>
 *
 * @author shuqing-bigdata
 */
@Data
public class SqlParseRequest {

    /**
     * 待解析的 SQL 语句（必填）。
     */
    @NotBlank(message = "sql 不能为空")
    private String sql;

    /**
     * SQL 方言：{@code ANSI}/{@code HIVE}/{@code DORIS}/{@code TRINO}。
     * <p>若为空，则自动检测方言。</p>
     */
    private String dialect;
}