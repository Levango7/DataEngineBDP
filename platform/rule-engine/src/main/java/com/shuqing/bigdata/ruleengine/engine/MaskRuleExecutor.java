package com.shuqing.bigdata.ruleengine.engine;

import com.shuqing.bigdata.ruleengine.model.Rule;
import com.shuqing.bigdata.ruleengine.model.RuleExecutionResult;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 数据脱敏（MASK）规则执行器。
 *
 * <p>MVP 阶段返回模拟结果：status=PASS, message=SIMULATED。</p>
 */
@Component
public class MaskRuleExecutor implements RuleExecutor {

    @Override
    public String getType() {
        return "MASK";
    }

    @Override
    public RuleExecutionResult execute(Rule rule, Map<String, Object> context) {
        return RuleExecutionResult.builder()
                .ruleId(rule.getId())
                .status("PASS")
                .message("SIMULATED")
                .details(Map.of("type", "MASK", "expression", rule.getExpression() == null ? "" : rule.getExpression()))
                .durationMs(0L)
                .executedAt(LocalDateTime.now())
                .build();
    }
}