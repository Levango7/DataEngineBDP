package com.shuqing.bigdata.ruleengine.engine;

import com.shuqing.bigdata.ruleengine.model.Rule;
import com.shuqing.bigdata.ruleengine.model.RuleExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 告警（ALERT）规则执行器。
 *
 * <p>评估告警条件表达式（形如 {@code metric op threshold}，如 {@code errorRate > 0.05}），
 * 从 {@code context} 中读取指标值进行比较：
 * <ul>
 *   <li>条件<b>触发</b> → 记为告警，记录 WARN 日志，返回 {@code FAIL}（ALERT_TRIGGERED）</li>
 *   <li>条件<b>未触发</b> → 返回 {@code PASS}（ALERT_NOT_TRIGGERED）</li>
 *   <li>表达式<b>无法评估</b>（缺指标值或格式不匹配）→ 返回 {@code PASS}（ALERT_NOT_EVALUATED）</li>
 * </ul>
 * 当前告警通知通过日志输出，Webhook/邮件通道后续通过注入 {@code AlertNotifier} 实现。</p>
 */
@Component
public class AlertRuleExecutor implements RuleExecutor {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleExecutor.class);

    @Override
    public String getType() {
        return "ALERT";
    }

    @Override
    public RuleExecutionResult execute(Rule rule, Map<String, Object> context) {
        long start = System.currentTimeMillis();
        String expression = rule.getExpression() == null ? "" : rule.getExpression();
        Map<String, Object> details = new HashMap<>();
        details.put("type", "ALERT");
        details.put("expression", expression);

        try {
            ConditionEvaluator.EvalResult er = ConditionEvaluator.evaluate(expression, context);
            details.put("evaluated", er.evaluated());
            details.put("triggered", er.triggered());
            if (er.evaluated()) {
                details.put("detail", er.detail());
            }

            if (!er.evaluated()) {
                // 表达式无法评估（如缺少指标值）→ 视为未触发，PASS
                return buildResult(rule, "PASS", "ALERT_NOT_EVALUATED", details, start);
            }

            if (er.triggered()) {
                // 触发告警：记录告警日志（Webhook/邮件通道后续实现）
                log.warn("ALERT TRIGGERED: ruleId={}, name={}, severity={}, expr={}, detail={}",
                        rule.getId(), rule.getName(), rule.getSeverity(), expression, er.detail());
                details.put("notified", false); // Webhook 未实现，仅日志通知
                return buildResult(rule, "FAIL", "ALERT_TRIGGERED", details, start);
            } else {
                return buildResult(rule, "PASS", "ALERT_NOT_TRIGGERED", details, start);
            }
        } catch (Exception e) {
            log.error("Alert rule execution failed: ruleId={}, expr={}", rule.getId(), expression, e);
            details.put("error", e.getMessage());
            return buildResult(rule, "ERROR", "ALERT_EXECUTION_ERROR: " + e.getMessage(),
                    details, start);
        }
    }

    private RuleExecutionResult buildResult(Rule rule, String status, String message,
                                            Map<String, Object> details, long start) {
        return RuleExecutionResult.builder()
                .ruleId(rule.getId())
                .status(status)
                .message(message)
                .details(details)
                .durationMs(System.currentTimeMillis() - start)
                .executedAt(LocalDateTime.now())
                .build();
    }
}
