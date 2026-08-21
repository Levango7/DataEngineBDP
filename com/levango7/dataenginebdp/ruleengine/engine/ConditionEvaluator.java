package com.shuqing.bigdata.ruleengine.engine;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 条件表达式评估器。
 *
 * <p>支持形如 {@code metric op threshold} 的简单表达式，其中：
 * <ul>
 *   <li>{@code metric} — 指标名，从执行上下文 {@code context} 中按同名键读取</li>
 *   <li>{@code op} — 比较运算符，∈ {@code >, >=, <, <=, ==, !=}</li>
 *   <li>{@code threshold} — 字面量数值（整数或小数，支持负号）</li>
 * </ul>
 * 当 metric 在 context 中不存在或不可解析为数值时，返回 {@code evaluated=false}，
 * 由调用方决定如何处理（通常视为未触发）。</p>
 *
 * <p>该类为无状态工具类，线程安全。</p>
 */
final class ConditionEvaluator {

    /** 条件表达式正则：metric op threshold */
    private static final Pattern EXPR_PATTERN = Pattern.compile(
            "^\\s*(\\w+)\\s*(>=|<=|==|!=|>|<)\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");

    private ConditionEvaluator() {
        // 工具类，禁止实例化
    }

    /** 评估结果。evaluated=false 表示表达式无法评估（缺指标值或格式不匹配）。 */
    record EvalResult(boolean evaluated, boolean triggered, String detail) {
    }

    /**
     * 评估条件表达式。
     *
     * @param expression 条件表达式，如 {@code value > 100}
     * @param context    执行上下文，提供指标值
     * @return 评估结果；expression 为空或 null 时返回 evaluated=false
     */
    static EvalResult evaluate(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return new EvalResult(false, false, "empty expression");
        }
        Matcher m = EXPR_PATTERN.matcher(expression);
        if (!m.matches()) {
            return new EvalResult(false, false, "unparseable expression: " + expression);
        }
        String metric = m.group(1);
        String op = m.group(2);
        double threshold = Double.parseDouble(m.group(3));

        Object raw = context == null ? null : context.get(metric);
        if (raw == null) {
            return new EvalResult(false, false, "metric not found in context: " + metric);
        }
        double value;
        try {
            value = toDouble(raw);
        } catch (NumberFormatException e) {
            return new EvalResult(false, false, "metric value not numeric: " + raw);
        }

        boolean triggered = compare(value, op, threshold);
        return new EvalResult(true, triggered,
                metric + "=" + value + " " + op + " " + threshold + " => " + triggered);
    }

    private static boolean compare(double value, String op, double threshold) {
        return switch (op) {
            case ">" -> value > threshold;
            case ">=" -> value >= threshold;
            case "<" -> value < threshold;
            case "<=" -> value <= threshold;
            case "==" -> value == threshold;
            case "!=" -> value != threshold;
            default -> false;
        };
    }

    private static double toDouble(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(String.valueOf(raw));
    }
}