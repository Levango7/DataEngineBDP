package com.shuqing.bigdata.governance.realtime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * 流式质量规则评估结果。
 *
 * <p>由 {@code QualityRuleEngine}（Flink CEP）评估每条流记录生成，
 * 违规时触发 {@link QualityAlert}。
 *
 * <p>支持规则类型：
 * <ul>
 *   <li>{@code NOT_NULL}：字段非空检查</li>
 *   <li>{@code UNIQUE}：字段唯一性检查（基于状态后端去重）</li>
 *   <li>{@code RANGE}：字段值范围检查（min ≤ value ≤ max）</li>
 *   <li>{@code FORMAT}：字段格式检查（正则匹配）</li>
 *   <li>{@code CUSTOM}：自定义表达式（Groovy/SQL）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityRuleResult implements Serializable {

    /** 规则 ID */
    private String ruleId;

    /** 规则类型：NOT_NULL / UNIQUE / RANGE / FORMAT / CUSTOM */
    private String ruleType;

    /** 规则名称 */
    private String ruleName;

    /** 目标表标识符 */
    private String tableIdentifier;

    /** 目标字段名 */
    private String fieldName;

    /** 评估结果：PASS / FAIL */
    private String result;

    /** 违规值（FAIL 时记录导致违规的实际值） */
    private Object violationValue;

    /** 评估时间戳 */
    private Instant evaluatedAt;

    /** 评估耗时（毫秒） */
    private long evaluateDurationMs;

    /** 规则参数（如 RANGE 的 min/max、FORMAT 的 pattern、CUSTOM 的 expression） */
    private Map<String, Object> ruleParams;

    /** 触发该评估的记录 ID（用于追溯） */
    private String recordId;

    /**
     * 判断是否违规。
     *
     * @return {@code true} 表示评估失败（违规）
     */
    public boolean isViolation() {
        return "FAIL".equals(result);
    }
}