package com.shuqing.bigdata.tagengine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 人群圈选结果。
 *
 * <p>对应详细设计 §6 接口 {@code POST /api/tag/v1/segment}。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AudienceResult {

    /** 圈选人群总数 */
    private long count;

    /** 命中的 user_id 列表（仅当请求 returnIds=true 且未超 limit 时返回） */
    private List<String> userIds;

    /** 是否因 limit 截断 */
    private boolean truncated;

    /** 异步导出作业 ID（仅当请求 exportFormat 非空时返回） */
    private String jobId;

    /** 圈选耗时（毫秒） */
    private long costMs;
}