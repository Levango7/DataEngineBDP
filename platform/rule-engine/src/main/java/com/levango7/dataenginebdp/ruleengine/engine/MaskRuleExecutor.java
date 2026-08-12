package com.levango7.dataenginebdp.ruleengine.engine;

import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据脱敏（MASK）规则执行器。
 *
 * <p>对真实数据执行脱敏函数，表达式格式为 {@code <strategy>[:params]}，支持四种策略：
 * <ul>
 *   <li>{@code mask[:keepPrefix,keepSuffix]} — 掩码，如 {@code mask:3,4} 对手机号</li>
 *   <li>{@code hash[:algorithm]} — 哈希摘要，默认 SHA-256</li>
 *   <li>{@code replace[:replacement]} — 整体替换，默认 {@code ***}</li>
 *   <li>{@code pseudonymize} — 假名化，等长随机字符串</li>
 * </ul>
 * 输入数据从 {@code context} 中按优先级 {@code input} > {@code value} > {@code column} 读取，
 * 脱敏结果放入 {@code details.maskedValue}。输入缺失时返回 {@code ERROR}（MASK_INPUT_MISSING）。</p>
 */
@Component
public class MaskRuleExecutor implements RuleExecutor {

    private static final Logger log = LoggerFactory.getLogger(MaskRuleExecutor.class);

    @Override
    public String getType() {
        return "MASK";
    }

    @Override
    public RuleExecutionResult execute(Rule rule, Map<String, Object> context) {
        long start = System.currentTimeMillis();
        String expression = rule.getExpression() == null ? "" : rule.getExpression();
        Map<String, Object> details = new HashMap<>();
        details.put("type", "MASK");
        details.put("expression", expression);

        try {
            Object input = MaskFunctions.extractInput(context);
            details.put("inputPresent", input != null);

            if (input == null) {
                log.warn("MASK input missing: ruleId={}, expr={}", rule.getId(), expression);
                return buildResult(rule, "ERROR", "MASK_INPUT_MISSING", details, start);
            }

            String inputValue = String.valueOf(input);
            String masked = MaskFunctions.apply(expression, inputValue);
            details.put("maskedValue", masked);
            details.put("strategy", MaskFunctions.parseStrategy(expression));
            details.put("originalLength", inputValue.length());

            log.debug("MASK applied: ruleId={}, strategy={}, originalLength={}",
                    rule.getId(), MaskFunctions.parseStrategy(expression), inputValue.length());
            return buildResult(rule, "PASS", "MASK_APPLIED", details, start);
        } catch (IllegalArgumentException e) {
            log.error("Mask rule strategy error: ruleId={}, expr={}", rule.getId(), expression, e);
            details.put("error", e.getMessage());
            return buildResult(rule, "ERROR", "MASK_STRATEGY_ERROR: " + e.getMessage(),
                    details, start);
        } catch (Exception e) {
            log.error("Mask rule execution failed: ruleId={}, expr={}", rule.getId(), expression, e);
            details.put("error", e.getMessage());
            return buildResult(rule, "ERROR", "MASK_EXECUTION_ERROR: " + e.getMessage(),
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
