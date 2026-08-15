package com.levango7.dataenginebdp.ruleengine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量规则执行请求（任务 F）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchRuleExecutionRequest {

    /** 规则 ID 列表。 */
    private List<Long> ruleIds;

    /** 执行上下文（透传给每条规则）。 */
    private java.util.Map<String, Object> context;

    /** 租户 ID（可选）。 */
    private String tenantId;
}
