package com.levango7.dataenginebdp.ruleengine.service;

import com.levango7.dataenginebdp.ruleengine.engine.RuleExecutor;
import com.levango7.dataenginebdp.ruleengine.model.BatchRuleExecutionRequest;
import com.levango7.dataenginebdp.ruleengine.model.BatchRuleExecutionResult;
import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionRequest;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionResult;
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

    /**
     * 批量执行（任务 F）：规则并行执行，单条失败隔离。
     *
     * <p>每条规则独立执行（互不阻塞），异常规则返回 ERROR 状态，
     * 不影响批次其他规则。结果按规则 ID 顺序返回。</p>
     *
     * @param request 批量请求（ruleIds + context）
     * @return 批量结果（逐条 + 汇总计数）
     */
    public BatchRuleExecutionResult executeBatch(BatchRuleExecutionRequest request) {
        long start = System.currentTimeMillis();
        List<Long> ruleIds = request.getRuleIds() == null ? List.of() : request.getRuleIds();
        Map<String, Object> context = request.getContext();
        String tenantId = request.getTenantId();

        List<RuleExecutionResult> results = ruleIds.parallelStream()
                .map(ruleId -> {
                    try {
                        RuleExecutionRequest single = new RuleExecutionRequest();
                        single.setRuleId(ruleId);
                        single.setContext(context);
                        single.setTenantId(tenantId);
                        return execute(single);
                    } catch (Exception e) {
                        // 失败隔离：单条异常不影响批次其他规则
                        return RuleExecutionResult.builder()
                                .ruleId(ruleId)
                                .status("ERROR")
                                .message("EXECUTION_FAILED: " + e.getMessage())
                                .durationMs(0L)
                                .executedAt(java.time.LocalDateTime.now())
                                .build();
                    }
                })
                .toList();

        int success = (int) results.stream()
                .filter(r -> "PASS".equals(r.getStatus()) || "FAIL".equals(r.getStatus()))
                .count();
        BatchRuleExecutionResult result = new BatchRuleExecutionResult();
        result.setResults(results);
        result.setSuccessCount(success);
        result.setFailedCount(results.size() - success);
        result.setTotalDurationMs(System.currentTimeMillis() - start);
        result.setExecutedAt(java.time.LocalDateTime.now());
        return result;
    }
}