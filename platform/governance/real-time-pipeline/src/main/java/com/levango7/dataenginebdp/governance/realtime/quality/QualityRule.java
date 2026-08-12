package com.levango7.dataenginebdp.governance.realtime.quality;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 流式质量规则定义。
 *
 * <p>由 Flink CEP 引擎评估，支持五种规则类型：
 * <ul>
 *   <li>{@code NOT_NULL}：字段非空检查，违规条件：value == null</li>
 *   <li>{@code UNIQUE}：字段唯一性检查，违规条件：value 重复出现（基于 KeyedState 去重）</li>
 *   <li>{@code RANGE}：字段值范围检查，违规条件：value < min 或 value > max</li>
 *   <li>{@code FORMAT}：字段格式检查，违规条件：value 不匹配正则 pattern</li>
 *   <li>{@code CUSTOM}：自定义表达式检查，违规条件：expression 求值为 true</li>
 * </ul>
 *
 * <p>规则参数通过 {@link #params} 传递：
 * <ul>
 *   <li>RANGE：{@code min}, {@code max}</li>
 *   <li>FORMAT：{@code pattern}（正则表达式）</li>
 *   <li>CUSTOM：{@code expression}（Groovy/SQL 表达式）</li>
 *   <li>UNIQUE：{@code windowMs}（去重窗口，超出窗口后允许重复）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityRule implements Serializable {

    /** 规则 ID */
    private String ruleId;

    /** 规则类型：NOT_NULL / UNIQUE / RANGE / FORMAT / CUSTOM */
    private RuleType ruleType;

    /** 规则名称 */
    private String ruleName;

    /** 目标表标识符 */
    private String tableIdentifier;

    /** 目标字段名 */
    private String fieldName;

    /** 告警级别：INFO / WARN / ERROR / CRITICAL */
    private String severity;

    /** 是否启用 */
    private boolean enabled;

    /** 规则参数 */
    private Map<String, Object> params;

    /** 规则类型枚举 */
    public enum RuleType {
        NOT_NULL,
        UNIQUE,
        RANGE,
        FORMAT,
        CUSTOM
    }

    /**
     * 获取参数值，带默认值。
     */
    public Object getParam(String key, Object defaultValue) {
        if (params == null) {
            return defaultValue;
        }
        Object value = params.get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取参数值（无默认值）。
     */
    public Object getParam(String key) {
        return params == null ? null : params.get(key);
    }
}