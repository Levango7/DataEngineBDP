package com.levango7.dataenginebdp.sqlgateway.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 跨源 SQL 执行计划响应 POJO。
 *
 * @author shuqing-bigdata
 */
@Data
@Builder
public class CrossSourceExplainResponse {

    /**
     * 原始 SQL。
     */
    private String sql;

    /**
     * 语句类型。
     */
    private String statementType;

    /**
     * 涉及的表。
     */
    private List<String> tables;

    /**
     * 表→源映射。
     */
    private Map<String, String> tableToSource;

    /**
     * 涉及的源列表。
     */
    private List<String> sources;

    /**
     * 是否跨源。
     */
    private boolean crossSource;

    /**
     * 执行策略：SINGLE_SOURCE_PROXY / PARALLEL_AND_MERGE。
     */
    private String strategy;

    /**
     * 解析耗时（毫秒）。
     */
    private Long durationMs;

    /**
     * 错误信息（失败时填充）。
     */
    private String error;
}