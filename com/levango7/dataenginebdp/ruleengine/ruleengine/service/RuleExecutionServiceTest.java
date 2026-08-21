package com.shuqing.bigdata.ruleengine.service;

import com.shuqing.bigdata.ruleengine.engine.AlertRuleExecutor;
import com.shuqing.bigdata.ruleengine.engine.DqRuleExecutor;
import com.shuqing.bigdata.ruleengine.engine.MaskRuleExecutor;
import com.shuqing.bigdata.ruleengine.model.Rule;
import com.shuqing.bigdata.ruleengine.model.RuleExecutionRequest;
import com.shuqing.bigdata.ruleengine.model.RuleExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * RuleExecutionService 单元测试。
 *
 * <p>注入真实执行器（无 JdbcTemplate），验证 DQ/ALERT/MASK 真实执行路径及错误分支。</p>
 */
@ExtendWith(MockitoExtension.class)
class RuleExecutionServiceTest {

    @Mock
    private RuleService ruleService;

    private RuleExecutionService ruleExecutionService;

    @BeforeEach
    void setUp() {
        // 手动构造，注入真实的执行器列表（DQ 无 JdbcTemplate，仅支持条件模式）
        ruleExecutionService = new RuleExecutionService(
                ruleService,
                List.of(new DqRuleExecutor(), new MaskRuleExecutor(), new AlertRuleExecutor())
        );
    }

    @Test
    @DisplayName("execute — DQ规则条件模式执行成功(PASS)")
    void execute_dqRule_conditionNotTriggered_shouldReturnPass() {
        Rule rule = new Rule();
        rule.setId(1L);
        rule.setType("DQ");
        rule.setExpression("nullCount > 0");

        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(1L);
        request.setContext(Map.of("nullCount", 0)); // 无违规

        when(ruleService.getById(1L)).thenReturn(rule);

        RuleExecutionResult result = ruleExecutionService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("DQ_CHECK_PASSED");
        assertThat(result.getRuleId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("execute — DQ规则条件模式发现违规返回FAIL")
    void execute_dqRule_conditionTriggered_shouldReturnFail() {
        Rule rule = new Rule();
        rule.setId(1L);
        rule.setType("DQ");
        rule.setExpression("nullCount > 0");

        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(1L);
        request.setContext(Map.of("nullCount", 5)); // 有违规

        when(ruleService.getById(1L)).thenReturn(rule);

        RuleExecutionResult result = ruleExecutionService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("FAIL");
        assertThat(result.getMessage()).isEqualTo("DQ_CHECK_FAILED");
    }

    @Test
    @DisplayName("execute — MASK规则脱敏执行成功(PASS)")
    void execute_maskRule_shouldReturnPass() {
        Rule rule = new Rule();
        rule.setId(2L);
        rule.setType("MASK");
        rule.setExpression("mask:3,4");

        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(2L);
        request.setContext(Map.of("input", "13812345678"));

        when(ruleService.getById(2L)).thenReturn(rule);

        RuleExecutionResult result = ruleExecutionService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("MASK_APPLIED");
        assertThat(result.getRuleId()).isEqualTo(2L);
        assertThat(result.getDetails()).containsEntry("maskedValue", "138****5678");
    }

    @Test
    @DisplayName("execute — ALERT规则未触发返回PASS")
    void execute_alertRule_notTriggered_shouldReturnPass() {
        Rule rule = new Rule();
        rule.setId(3L);
        rule.setType("ALERT");
        rule.setExpression("errorRate > 0.05");

        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(3L);
        request.setContext(Map.of("errorRate", 0.01)); // 未触发

        when(ruleService.getById(3L)).thenReturn(rule);

        RuleExecutionResult result = ruleExecutionService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("ALERT_NOT_TRIGGERED");
        assertThat(result.getRuleId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("execute — ALERT规则触发返回FAIL")
    void execute_alertRule_triggered_shouldReturnFail() {
        Rule rule = new Rule();
        rule.setId(3L);
        rule.setType("ALERT");
        rule.setExpression("errorRate > 0.05");

        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(3L);
        request.setContext(Map.of("errorRate", 0.1)); // 触发

        when(ruleService.getById(3L)).thenReturn(rule);

        RuleExecutionResult result = ruleExecutionService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("FAIL");
        assertThat(result.getMessage()).isEqualTo("ALERT_TRIGGERED");
    }

    @Test
    @DisplayName("execute — ALERT规则缺少指标值返回PASS(无法评估)")
    void execute_alertRule_metricMissing_shouldReturnPass() {
        Rule rule = new Rule();
        rule.setId(3L);
        rule.setType("ALERT");
        rule.setExpression("errorRate > 0.05");

        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(3L);
        // context 未设置 → null → 无法评估 → PASS

        when(ruleService.getById(3L)).thenReturn(rule);

        RuleExecutionResult result = ruleExecutionService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("ALERT_NOT_EVALUATED");
    }

    @Test
    @DisplayName("execute — 规则不存在时返回ERROR")
    void execute_ruleNotFound_shouldReturnError() {
        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(999L);

        when(ruleService.getById(999L)).thenReturn(null);

        RuleExecutionResult result = ruleExecutionService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).isEqualTo("RULE_NOT_FOUND");
    }

    @Test
    @DisplayName("execute — 不支持的规则类型返回ERROR")
    void execute_unsupportedType_shouldReturnError() {
        Rule rule = new Rule();
        rule.setId(4L);
        rule.setType("UNKNOWN");

        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(4L);

        when(ruleService.getById(4L)).thenReturn(rule);

        RuleExecutionResult result = ruleExecutionService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).contains("UNSUPPORTED_RULE_TYPE");
    }

    @Test
    @DisplayName("execute — durationMs为端到端耗时")
    void execute_shouldRecordDuration() {
        Rule rule = new Rule();
        rule.setId(1L);
        rule.setType("DQ");
        rule.setExpression("nullCount > 0");

        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(1L);
        request.setContext(Map.of("nullCount", 0));

        when(ruleService.getById(1L)).thenReturn(rule);

        RuleExecutionResult result = ruleExecutionService.execute(request);

        assertThat(result.getDurationMs()).isNotNull();
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0L);
    }
}
