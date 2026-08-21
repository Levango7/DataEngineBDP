package com.shuqing.bigdata.ruleengine.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 规则执行结果 POJO。
 */
@Data
@Builder
public class RuleExecutionResult {

    /** 规则 ID */
    private Long ruleId;

    /** 执行状态：PASS / FAIL / ERROR */
    private String status;

    /** 执行消息 */
    private String message;

    /** 执行详情 */
    private Map<String, Object> details;

    /** 执行耗时（毫秒） */
    private Long durationMs;

    /** 执行时间 */
    private LocalDateTime executedAt;
}