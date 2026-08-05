package com.shuqing.bigdata.tagengine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户画像。
 *
 * <p>单个用户的全量标签值聚合视图，对应 Doris 标签宽表中的一行。</p>
 *
 * <p>对应详细设计 §6 接口 {@code GET /api/tag/v1/portrait/{userId}}。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfile {

    /** 用户 ID */
    private String userId;

    /** 租户 ID */
    private String tenantId;

    /**
     * 标签值 Map：key=标签列名，value=标签值。
     * <p>例如 {"user_level":"活跃","total_amount":5200.50,"reg_days":120}。</p>
     */
    private Map<String, Object> tags;

    /** 标签版本号 */
    private String tagVersion;

    /** 画像最近更新时间 */
    private LocalDateTime updateTs;
}