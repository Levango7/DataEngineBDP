package com.levango7.dataenginebdp.governance.realtime.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * rule-engine 规则视图 → pipeline {@link QualityRule} 的映射（台账 #6 阶段 3）。
 *
 * <p>两侧模型**不是 1:1**：rule-engine 持久化的是通用 {@code Rule(type/expression/description)}，
 * 对外经 {@code QualityRuleController.toView} 暴露
 * {@code {id,name,targetTable,targetField,checkType,threshold,actionOnFail,status}}；
 * 而 pipeline 需要 {@code QualityRule(ruleType,tableIdentifier,fieldName,params,...)}。
 * 本类集中承载该映射（纯静态、无 IO，便于单测），HTTP 侧只负责取数。
 *
 * <p>容错原则：**映射不了的规则跳过并告警**，绝不臆造字段（例如 targetTable 为空时丢弃）。
 */
final class RuleEngineRuleMapper {

    /** rule-engine 模板路径渲染 SQL 的表达式前缀（见 {@code BuiltinRuleTemplates.renderSql}）。 */
    private static final String SQL_PREFIX = "sql:";

    private RuleEngineRuleMapper() {
    }

    /**
     * 从列表响应体中取出规则视图数组。
     *
     * <p>兼容三种形状：直接数组、{@code {list:[...]}}、以及被 ApiResponseAdvice 包装的
     * {@code {code,data:{list:[...]}}}（rule-engine 的列表契约是 {@code {list,total,page,size}}）。
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> extractList(Map<String, Object> body) {
        if (body == null) {
            return List.of();
        }
        Object data = body.containsKey("data") ? body.get("data") : body;
        if (data instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        if (data instanceof Map<?, ?> map) {
            Object list = map.get("list");
            if (list instanceof List<?> l) {
                return (List<Map<String, Object>>) l;
            }
        }
        return List.of();
    }

    /**
     * 单个视图 → {@link QualityRule}；无法映射时返回 {@code null}。
     *
     * @param view     规则视图（来自 rule-engine）
     * @param tenantId 该规则归属租户（引擎按租户隔离）
     */
    static QualityRule mapView(Map<String, Object> view, String tenantId) {
        if (view == null) {
            return null;
        }
        String ruleId = asString(view.get("id"));
        String table = asString(view.get("targetTable"));
        QualityRule.RuleType type = mapType(asString(view.get("checkType")));
        if (ruleId == null || ruleId.isBlank() || table == null || table.isBlank() || type == null) {
            return null;
        }
        return QualityRule.builder()
                .ruleId(ruleId)
                .tenantId(tenantId)
                .ruleType(type)
                .ruleName(asString(view.get("name")))
                .tableIdentifier(table)
                .fieldName(asString(view.get("targetField")))
                .severity(asString(view.get("actionOnFail")))
                .enabled("enabled".equalsIgnoreCase(asString(view.get("status"))))
                .params(parseParams(asString(view.get("threshold"))))
                .build();
    }

    /** checkType（rule-engine 小写形态）→ pipeline 枚举；未知一律 CUSTOM。 */
    static QualityRule.RuleType mapType(String checkType) {
        if (checkType == null || checkType.isBlank()) {
            return null;
        }
        return switch (checkType.trim().toLowerCase()) {
            case "not_null", "notnull", "non_null" -> QualityRule.RuleType.NOT_NULL;
            case "unique" -> QualityRule.RuleType.UNIQUE;
            case "range", "between" -> QualityRule.RuleType.RANGE;
            case "format", "regex", "pattern" -> QualityRule.RuleType.FORMAT;
            default -> QualityRule.RuleType.CUSTOM;
        };
    }

    /**
     * threshold 字符串 → params。
     *
     * <p>三类形态分别处理，保证 {@link QualityRuleEvaluator} 拿到它期望的类型：
     * <ol>
     *   <li><b>SQL 表达式</b>（{@code sql:} 前缀，模板路径渲染产物）：放 {@code sql} 键，
     *       <b>不</b>当作数值阈值——它没有数值语义。</li>
     *   <li><b>键值对</b>（{@code "min=1,max=10"}）：{@code min}/{@code max} 解析为
     *       {@link Number}，因为评估器 {@code evalRange} 对其做 {@code (Number)} 强转，
     *       保留字符串会抛 {@link ClassCastException}。</li>
     *   <li><b>其他</b>：整体放 {@code threshold} 键，信息不丢。</li>
     * </ol>
     */
    static Map<String, Object> parseParams(String threshold) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (threshold == null || threshold.isBlank()) {
            return params;
        }
        String trimmed = threshold.trim();
        if (trimmed.startsWith(SQL_PREFIX)) {
            params.put("sql", trimmed.substring(SQL_PREFIX.length()).trim());
            return params;
        }
        if (trimmed.contains("=")) {
            for (String kv : trimmed.split(",")) {
                int i = kv.indexOf('=');
                if (i > 0) {
                    String key = kv.substring(0, i).trim();
                    String value = kv.substring(i + 1).trim();
                    params.put(key, isNumericParam(key) ? asNumber(value) : value);
                }
            }
        }
        if (params.isEmpty()) {
            params.put("threshold", trimmed);
        } else if (!params.containsKey("threshold")) {
            params.put("threshold", trimmed);
        }
        return params;
    }

    /** 需要数值语义的键：评估器对它们做 {@code (Number)} 强转。 */
    private static boolean isNumericParam(String key) {
        return "min".equals(key) || "max".equals(key);
    }

    /** 数值键能解析成数字就给 Double，否则保留原文（不臆造 0）。 */
    private static Object asNumber(String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 便于测试：把视图数组映射为规则列表（跳过无法映射项）。 */
    static List<QualityRule> mapAll(List<Map<String, Object>> views, String tenantId) {
        List<QualityRule> out = new ArrayList<>();
        if (views == null) {
            return out;
        }
        for (Map<String, Object> v : views) {
            QualityRule r = mapView(v, tenantId);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }
}
