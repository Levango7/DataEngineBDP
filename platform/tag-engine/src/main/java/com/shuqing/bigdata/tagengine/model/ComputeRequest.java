package com.shuqing.bigdata.tagengine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标签计算请求。
 *
 * <p>触发一次标签计算作业，对应详细设计 §4、§6 接口
 * {@code POST /api/tag/v1/compute}。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComputeRequest {

    /** 租户 ID */
    private String tenantId;

    /** 计算模式：full（全量重算）/ incr（增量） */
    private String mode;

    /** 计算数据时间窗口起始（ISO-8601），增量模式使用 */
    private String startTime;

    /** 计算数据时间窗口结束（ISO-8601），增量模式使用 */
    private String endTime;

    /** 是否同步等待计算完成；false 时立即返回 jobId */
    private boolean sync;
}