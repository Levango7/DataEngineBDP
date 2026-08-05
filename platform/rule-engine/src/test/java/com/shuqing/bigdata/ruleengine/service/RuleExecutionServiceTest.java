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
 */
@ExtendWith(MockitoExtension.class)
class RuleExecutionServiceTest {

    @Mock
    private RuleService ruleService;

    private RuleExecutionService ruleExecutionService;

    @BeforeEach
    void setUp() {
        // 手动构造，注入真实的执行器列表
        ruleExecutionService = new RuleExecutionService(
                ruleService,
                List.of(new DqRuleExecutor(), new MaskRuleExecutor(), new AlertRuleExecutor())
        );
    }

    @Test
    @DisplayName("execute — DQ规则执行成功")
    void execute_dqRule_shouldReturnPass() {
        Rule rule = new Rule();
        rule.setId(1L);
        rule.setType("DQ");
        rule.setExpression("NOT NULL");

        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(1L);
        request.setContext(Map.of());

        when(ruleService.getById(1L)).thenReturn(rule);

        RuleExecutionResult result = ruleExecutionService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("SIMULATED");
        assertThat(result.getRuleId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("execute — MASK规则执行成功")
    void execute_maskRule_shouldReturnPass() {
        Rule rule = new Rule();
        rule.setId(2L);
        rule.setType("MASK");
        rule.setExpression("PHONE_MASK");

        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(2L);
        request.setContext(Map.of("column", "phone"));

        when(ruleService.getById(2L)).thenReturn(rule);

        RuleExecutionResult result = ruleExecutionService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getRuleId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("execute — ALERT规则执行成功")
    void execute_alertRule_shouldReturnPass() {
        Rule rule = new Rule();
        rule.setId(3L);
        rule.setType("ALERT");
        rule.setExpression("THRESHOLD > 100");

        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(3L);

        when(ruleService.getById(3L)).thenReturn(rule);

        RuleExecutionResult result = ruleExecutionService.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getRuleId()).isEqualTo(3L);
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
        rule.setExpression("NOT NULL");

        RuleExecutionRequest request = new RuleExecutionRequest();
        request.setRuleId(1L);

        when(ruleService.getById(1L)).thenReturn(rule);

        RuleExecutionResult result = ruleExecutionService.execute(request);

        assertThat(result.getDurationMs()).isNotNull();
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0L);
    }
}
