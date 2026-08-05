package com.shuqing.bigdata.sqlgateway.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * SQL 方言转换请求 POJO。
 *
 * @author shuqing-bigdata
 */
@Data
public class SqlConvertRequest {

    /**
     * 待转换的 SQL 语句（必填）。
     */
    @NotBlank(message = "sql 不能为空")
    private String sql;

    /**
     * 源方言；为空则自动检测。
     */
    private String fromDialect;

    /**
     * 目标方言（必填）。
     */
    @NotBlank(message = "toDialect 不能为空")
    private String toDialect;
}