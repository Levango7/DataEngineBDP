package com.levango7.dataenginebdp.sqlgateway.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 跨源 SQL 执行请求 POJO。
 *
 * <p>由客户端 POST 至 {@code /api/v1/sql/cross-source} 时提交。</p>
 *
 * @author shuqing-bigdata
 */
@Data
public class CrossSourceRequest {

    /**
     * 待执行的 SQL 语句（必填）。
     */
    @NotBlank(message = "sql 不能为空")
    private String sql;

    /**
     * SQL 方言：ANSI/HIVE/DORIS/TRINO，默认 ANSI。
     */
    private String dialect;

    /**
     * 租户 ID。
     */
    private String tenantId;

    /**
     * 超时秒数，{@code null} 表示使用默认值 30s。
     */
    private Long timeoutSeconds;
}