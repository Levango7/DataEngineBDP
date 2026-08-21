package com.shuqing.bigdata.ruleengine.service;

import com.shuqing.bigdata.ruleengine.engine.RuleExecutor;
import com.shuqing.bigdata.ruleengine.model.Rule;
import com.shuqing.bigdata.ruleengine.model.RuleExecutionRequest;
import com.shuqing.bigdata.ruleengine.model.RuleExecutionResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 规则执行服务。
 *
 * <p>根据规则类型分派到对应 {@link RuleExecutor}，MVP 阶段返回模拟结果。</p>
 */
@Service
public class RuleExecutionService {

    private final RuleService ruleService;
    private final Map<String, RuleExecutor> executorsByType;

    public RuleExecutionService(RuleService ruleService, List<RuleExecutor> executors) {
        this.ruleService = ruleService;
        this.executorsByType = executors.stream()
                .collect(Collectors.toMap(RuleExecutor::getType, Function.identity()));
    }

    /** 执行规则 */
    public RuleExecutionResult execute(RuleExecutionRequest request) {
        long start = System.currentTimeMillis();

        Long ruleId = request.getRuleId();
        Rule rule = ruleService.getById(ruleId);
        if (rule == null) {
            return RuleExecutionResult.builder()
                    .ruleId(ruleId)
                    .status("ERROR")
                    .message("RULE_NOT_FOUND")
                    .durationMs(System.currentTimeMillis() - start)
                    .executedAt(java.time.LocalDateTime.now())
                    .build();
        }

        RuleExecutor executor = executorsByType.get(rule.getType());
        if (executor == null) {
            return RuleExecutionResult.builder()
                    .ruleId(ruleId)
                    .status("ERROR")
                    .message("UNSUPPORTED_RULE_TYPE: " + rule.getType())
                    .durationMs(System.currentTimeMillis() - start)
                    .executedAt(java.time.LocalDateTime.now())
                    .build();
        }

        RuleExecutionResult result = executor.execute(rule, request.getContext());
        // 由执行器填充的 durationMs 为执行器内部耗时，此处覆盖为端到端耗时
        result.setDurationMs(System.currentTimeMillis() - start);
        return result;
    }
}