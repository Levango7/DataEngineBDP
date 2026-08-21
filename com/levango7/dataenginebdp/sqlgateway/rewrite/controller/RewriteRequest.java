package com.shuqing.bigdata.sqlgateway.rewrite.controller;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 查询改写请求 DTO。
 *
 * <p>封装用户提交的改写/路由请求，仅包含 SQL 文本与可选的租户 ID。</p>
 *
 * @author shuqing-bigdata
 */
@Data
public class RewriteRequest {

    /**
     * 待改写的 SQL 文本。
     */
    @NotBlank(message = "sql 不能为空")
    private String sql;

    /**
     * 租户 ID（可选，用于多租户隔离）。
     */
    private String tenantId;
}