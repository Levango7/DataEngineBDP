package com.levango7.dataenginebdp.governance.realtime.quality;

import com.levango7.dataenginebdp.governance.realtime.model.QualityRuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 流式质量规则评估器。
 *
 * <p>对每条流记录评估 {@link QualityRule}，生成 {@link QualityRuleResult}。
 * 违规时（result=FAIL）由 {@code QualityAlertEmitter} 触发告警。
 *
 * <p>支持五种规则类型：
 * <ul>
 *   <li>{@code NOT_NULL}：字段非空检查</li>
 *   <li>{@code UNIQUE}：字段唯一性检查（基于内存 Set 去重，生产环境用 Flink KeyedState）</li>
 *   <li>{@code RANGE}：字段值范围检查（min ≤ value ≤ max）</li>
 *   <li>{@code FORMAT}：字段格式检查（正则匹配）</li>
 *   <li>{@code CUSTOM}：自定义表达式（简化版：字段值等于特定值时违规）</li>
 * </ul>
 *
 * <p>本组件提供同步评估接口，供 Spring Boot REST 端点直接调用；
 * Flink CEP 流式评估由 {@code QualityRuleCepJob} 提交到 Flink 集群执行。
 */
@Component
public class QualityRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(QualityRuleEvaluator.class);

    /** UNIQUE 规则的去重状态：ruleId → 已见值集合（生产环境用 Flink KeyedState） */
    private final ConcurrentHashMap<String, Set<Object>> uniqueState = new ConcurrentHashMap<>();

    /** 正则编译缓存：pattern → Pattern */
    private final ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    /** 评估统计 */
    private final ConcurrentHashMap<String, Long> evalStats = new ConcurrentHashMap<>();

    /**
     * 评估单条记录的指定规则。
     *
     * @param rule 质量规则
     * @param recordId 记录 ID
     * @param fieldValue 待评估的字段值
     * @return 评估结果
     */
    public QualityRuleResult evaluate(QualityRule rule, String recordId, Object fieldValue) {
        long start = System.currentTimeMillis();
        Map<String, Object> details = new HashMap<>();

        if (!rule.isEnabled()) {
            return buildResult(rule, recordId, "PASS", null, details, start);
        }

        String result;
        Object violationValue = null;

        try {
            switch (rule.getRuleType()) {
                case NOT_NULL:
                    result = evalNotNull(fieldValue);
                    if ("FAIL".equals(result)) {
                        violationValue = fieldValue;
                    }
                    break;
                case UNIQUE:
                    result = evalUnique(rule, fieldValue);
                    if ("FAIL".equals(result)) {
                        violationValue = fieldValue;
                    }
                    break;
                case RANGE:
                    result = evalRange(rule, fieldValue);
                    if ("FAIL".equals(result)) {
                        violationValue = fieldValue;
                    }
                    break;
                case FORMAT:
                    result = evalFormat(rule, fieldValue);
                    if ("FAIL".equals(result)) {
                        violationValue = fieldValue;
                    }
                    break;
                case CUSTOM:
                    result = evalCustom(rule, fieldValue);
                    if ("FAIL".equals(result)) {
                        violationValue = fieldValue;
                    }
                    break;
                default:
                    log.warn("Unknown rule type: {}", rule.getRuleType());
                    result = "PASS";
            }
        } catch (Exception e) {
            log.error("Rule evaluation failed: ruleId={}, type={}: {}",
                    rule.getRuleId(), rule.getRuleType(), e.getMessage());
            result = "ERROR";
            details.put("error", e.getMessage());
        }

        evalStats.merge(result + "Count", 1L, Long::sum);
        return buildResult(rule, recordId, result, violationValue, details, start);
    }

    // -----------------------------------------------------------------------
    // 各规则类型评估
    // -----------------------------------------------------------------------

    /**
     * NOT_NULL：字段非空检查。
     *
     * @return {@code PASS} 表示非空，{@code FAIL} 表示为 null
     */
    private String evalNotNull(Object value) {
        return value == null ? "FAIL" : "PASS";
    }

    /**
     * UNIQUE：字段唯一性检查。
     *
     * <p>基于内存 Set 去重（简化实现）。生产环境应在 Flink KeyedState 中维护去重集合，
     * 支持状态后端（RocksDB）与 checkpoint 容错。
     *
     * @return {@code PASS} 表示唯一，{@code FAIL} 表示重复
     */
    private String evalUnique(QualityRule rule, Object value) {
        if (value == null) {
            return "PASS"; // null 不参与唯一性检查
        }
        Set<Object> seen = uniqueState.computeIfAbsent(rule.getRuleId(), k -> ConcurrentHashMap.newKeySet());
        return seen.add(value) ? "PASS" : "FAIL";
    }

    /**
     * RANGE：字段值范围检查（min ≤ value ≤ max）。
     *
     * @return {@code PASS} 表示在范围内，{@code FAIL} 表示超出范围
     */
    private String evalRange(QualityRule rule, Object value) {
        if (value == null) {
            return "PASS"; // null 不参与范围检查（应由 NOT_NULL 检查）
        }
        Number min = (Number) rule.getParam("min", Double.NEGATIVE_INFINITY);
        Number max = (Number) rule.getParam("max", Double.POSITIVE_INFINITY);
        double numValue = toDouble(value);
        return (numValue >= min.doubleValue() && numValue <= max.doubleValue()) ? "PASS" : "FAIL";
    }

    /**
     * FORMAT：字段格式检查（正则匹配）。
     *
     * @return {@code PASS} 表示匹配，{@code FAIL} 表示不匹配
     */
    private String evalFormat(QualityRule rule, Object value) {
        if (value == null) {
            return "PASS";
        }
        String patternStr = (String) rule.getParam("pattern");
        if (patternStr == null) {
            return "PASS";
        }
        Pattern pattern = patternCache.computeIfAbsent(patternStr, Pattern::compile);
        return pattern.matcher(value.toString()).matches() ? "PASS" : "FAIL";
    }

    /**
     * CUSTOM：自定义表达式检查。
     *
     * <p>简化实现：支持 {@code expression} 参数为 {@code field == value} 形式，
     * 当字段值等于指定值时视为违规。生产环境可集成 Groovy 引擎或 SQL 表达式求值器。
     *
     * @return {@code PASS} 表示不违规，{@code FAIL} 表示违规
     */
    private String evalCustom(QualityRule rule, Object value) {
        String expression = (String) rule.getParam("expression");
        if (expression == null || value == null) {
            return "PASS";
        }
        // 简化：expression 形如 "field == 'specificValue'" 或 "field == 123"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "field\\s*==\\s*('?)([^']+)\\1").matcher(expression);
        if (m.find()) {
            String expectedValue = m.group(2).trim();
            return value.toString().equals(expectedValue) ? "FAIL" : "PASS";
        }
        // 不支持的表达式格式，降级为 PASS
        return "PASS";
    }

    // -----------------------------------------------------------------------
    // 辅助方法
    // -----------------------------------------------------------------------

    private double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    private QualityRuleResult buildResult(QualityRule rule, String recordId,
                                          String result, Object violationValue,
                                          Map<String, Object> details, long start) {
        return QualityRuleResult.builder()
                .ruleId(rule.getRuleId())
                .ruleType(rule.getRuleType().name())
                .ruleName(rule.getRuleName())
                .tableIdentifier(rule.getTableIdentifier())
                .fieldName(rule.getFieldName())
                .result(result)
                .violationValue(violationValue)
                .evaluatedAt(Instant.now())
                .evaluateDurationMs(System.currentTimeMillis() - start)
                .ruleParams(rule.getParams())
                .recordId(recordId)
                .build();
    }

    /**
     * 获取评估统计。
     */
    public Map<String, Long> getEvalStats() {
        return new HashMap<>(evalStats);
    }

    /**
     * 清理 UNIQUE 规则的去重状态（用于测试或规则删除）。
     *
     * @param ruleId 规则 ID
     */
    public void clearUniqueState(String ruleId) {
        uniqueState.remove(ruleId);
    }

    /**
     * 清理所有状态（用于测试）。
     */
    public void clearAllState() {
        uniqueState.clear();
        patternCache.clear();
        evalStats.clear();
    }
}