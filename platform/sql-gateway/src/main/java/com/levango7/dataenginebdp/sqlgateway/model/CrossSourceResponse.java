package com.levango7.dataenginebdp.sqlgateway.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 跨源 SQL 执行响应 POJO。
 *
 * @author shuqing-bigdata
 */
@Data
@Builder
public class CrossSourceResponse {

    /**
     * 查询唯一 ID。
     */
    private String queryId;

    /**
     * 查询状态：SUCCESS / FAILED / DEGRADED。
     */
    private String status;

    /**
     * 结果列名列表。
     */
    private List<String> columns;

    /**
     * 结果行集合。
     */
    private List<List<Object>> rows;

    /**
     * 行数。
     */
    private int rowCount;

    /**
     * 结果来源标识（单源为源名，跨源为 "merged"）。
     */
    private String source;

    /**
     * 是否跨源查询。
     */
    private boolean crossSource;

    /**
     * 涉及的源列表。
     */
    private List<String> sources;

    /**
     * 表→源映射（用于前端可视化）。
     */
    private Map<String, String> tableToSource;

    /**
     * 执行耗时（毫秒）。
     */
    private Long durationMs;

    /**
     * 错误信息（失败时填充）。
     */
    private String error;
}