package com.levango7.dataenginebdp.ruleengine.service;

import com.levango7.dataenginebdp.ruleengine.model.BatchRuleExecutionRequest;
import com.levango7.dataenginebdp.ruleengine.model.BatchRuleExecutionResult;
import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionRequest;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * RuleExecutionService.executeBatch 单元测试（任务 F）。
 *
 * <p>验证并行批量执行、单条失败隔离、汇总计数。</p>
 */
@ExtendWith(MockitoExtension.class)
class RuleExecutionServiceBatchTest {

    @Mock
    private com.levango7.dataenginebdp.ruleengine.service.RuleService ruleService;

    private RuleExecutionService service;

    @BeforeEach
    void setUp() {
        // 注入真实 DQ executor：规则 1 → FAIL(违规), 规则 3 → PASS
        com.levango7.dataenginebdp.ruleengine.engine.RuleExecutor dq =
                new com.levango7.dataenginebdp.ruleengine.engine.RuleExecutor() {
                    @Override
                    public String getType() {
                        return "DQ";
                    }

                    @Override
                    public RuleExecutionResult execute(Rule rule, Map<String, Object> context) {
                        return rule.getId() == 3L ? pass(3L) : fail(rule.getId());
                    }
                };
        service = new RuleExecutionService(ruleService, List.of(dq));
    }

    private Rule rule(long id, String type) {
        Rule r = new Rule();
        r.setId(id);
        r.setType(type);
        return r;
    }

    private RuleExecutionResult pass(long id) {
        return RuleExecutionResult.builder()
                .ruleId(id).status("PASS").message("ok")
                .durationMs(5L).executedAt(java.time.LocalDateTime.now()).build();
    }

    private RuleExecutionResult fail(long id) {
        return RuleExecutionResult.builder()
                .ruleId(id).status("FAIL").message("violation")
                .durationMs(5L).executedAt(java.time.LocalDateTime.now()).build();
    }

    @Test
    void executeBatch_parallelAndCountsResults() {
        // 规则 1 和 3 存在 → PASS；规则 2 不存在 → ERROR（失败隔离）
        when(ruleService.getById(1L)).thenReturn(rule(1L, "DQ"));
        when(ruleService.getById(2L)).thenReturn(null); // 不存在
        when(ruleService.getById(3L)).thenReturn(rule(3L, "DQ"));

        BatchRuleExecutionRequest request = new BatchRuleExecutionRequest();
        request.setRuleIds(List.of(1L, 2L, 3L));
        request.setContext(new HashMap<>());

        BatchRuleExecutionResult result = service.executeBatch(request);

        assertThat(result.getResults()).hasSize(3);
        // 1 → FAIL(执行了), 2 → ERROR(不存在, 隔离), 3 → PASS
        Map<Long, String> statusById = new HashMap<>();
        result.getResults().forEach(r -> statusById.put(r.getRuleId(), r.getStatus()));
        assertThat(statusById.get(1L)).isEqualTo("FAIL");
        assertThat(statusById.get(2L)).isEqualTo("ERROR");
        assertThat(statusById.get(3L)).isEqualTo("PASS");

        // 汇总：PASS/FAIL 算成功，ERROR 算失败
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getTotalDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.getExecutedAt()).isNotNull();
    }

    @Test
    void executeBatch_emptyRuleIds_returnsEmpty() {
        BatchRuleExecutionRequest request = new BatchRuleExecutionRequest();
        request.setRuleIds(List.of());

        BatchRuleExecutionResult result = service.executeBatch(request);
        assertThat(result.getResults()).isEmpty();
        assertThat(result.getSuccessCount()).isZero();
    }
}
