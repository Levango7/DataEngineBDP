package com.shuqing.bigdata.tagengine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 人群圈选请求。
 *
 * <p>业务人员在圈选 UI 拖拽标签条件，封装层翻译为 Doris SQL，
 * 毫秒级返回 user_id 列表或统计计数。对应详细设计 §5、§6。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AudienceRequest {

    /** 租户 ID */
    private String tenantId;

    /** 圈选名称（用于落表/导出命名） */
    private String name;

    /** 包含条件（命中即纳入人群） */
    private TagQuery include;

    /** 排除条件（命中即从结果中剔除） */
    private TagQuery exclude;

    /** 是否返回 user_id 列表；false 时只返回 count */
    private boolean returnIds;

    /** 最大返回用户数，防止 OOM；默认由配置 app.audience.max-result-size 兜底 */
    private Integer limit;

    /** 采样偏移量，用于分页 */
    private Integer offset;

    /**
     * 导出格式：csv / json / null（不导出）。
     * <p>非空时引擎返回 jobId，异步导出。</p>
     */
    private String exportFormat;

    /** 圈选目标标签列表（仅圈选这些标签非空的用户；为空表示全标签） */
    private List<String> targetColumns;
}