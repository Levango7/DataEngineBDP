package com.shuqing.bigdata.governance.realtime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 质量违规告警。
 *
 * <p>当 {@link QualityRuleResult} 评估结果为 FAIL 时生成，目标告警延迟 ≤ 5s
 * （从违规数据产生到告警发出）。
 *
 * <p>告警渠道（与平台 X3 统一运维观测对齐）：
 * <ul>
 *   <li>Webhook：POST 到配置的告警 URL</li>
 *   <li>Kafka：发送到 {@code governance.alerts} topic</li>
 *   <li>日志：WARN 级别输出，由日志采集链路收集</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityAlert implements Serializable {

    /** 告警唯一 ID（UUID） */
    private String alertId;

    /** 触发告警的规则 ID */
    private String ruleId;

    /** 规则类型 */
    private String ruleType;

    /** 告警级别：INFO / WARN / ERROR / CRITICAL */
    private String severity;

    /** 目标表标识符 */
    private String tableIdentifier;

    /** 目标字段名 */
    private String fieldName;

    /** 违规值 */
    private Object violationValue;

    /** 违规记录 ID */
    private String recordId;

    /** 告警消息（人类可读） */
    private String message;

    /** 违规数据产生时间戳（来自源记录） */
    private Instant violationTimestamp;

    /** 告警发出时间戳 */
    private Instant alertTimestamp;

    /** 告警延迟（毫秒）= alertTimestamp - violationTimestamp */
    private long alertLatencyMs;

    /** 治理闭环耗时（毫秒）= alertTimestamp - catalogCommitTimestamp */
    private long pipelineLatencyMs;
}