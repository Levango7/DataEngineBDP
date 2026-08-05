package com.shuqing.bigdata.ruleengine.engine;

import com.shuqing.bigdata.ruleengine.model.Rule;
import com.shuqing.bigdata.ruleengine.model.RuleExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则执行器单元测试。
 *
 * <p>直接测试 DqRuleExecutor、MaskRuleExecutor、AlertRuleExecutor 的执行逻辑。</p>
 */
class RuleExecutorTest {

    private Rule dqRule;
    private Rule maskRule;
    private Rule alertRule;

    @BeforeEach
    void setUp() {
        dqRule = new Rule();
        dqRule.setId(1L);
        dqRule.setType("DQ");
        dqRule.setExpression("NOT NULL");

        maskRule = new Rule();
        maskRule.setId(2L);
        maskRule.setType("MASK");
        maskRule.setExpression("PHONE_MASK");

        alertRule = new Rule();
        alertRule.setId(3L);
        alertRule.setType("ALERT");
        alertRule.setExpression("THRESHOLD > 100");
    }

    @Test
    @DisplayName("DqRuleExecutor.getType — 返回DQ")
    void dqExecutor_getType_shouldReturnDQ() {
        DqRuleExecutor executor = new DqRuleExecutor();
        assertThat(executor.getType()).isEqualTo("DQ");
    }

    @Test
    @DisplayName("DqRuleExecutor.execute — 返回PASS和SIMULATED")
    void dqExecutor_execute_shouldReturnPass() {
        DqRuleExecutor executor = new DqRuleExecutor();
        RuleExecutionResult result = executor.execute(dqRule, Map.of());

        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("SIMULATED");
        assertThat(result.getRuleId()).isEqualTo(1L);
        assertThat(result.getDetails()).containsEntry("type", "DQ");
    }

    @Test
    @DisplayName("DqRuleExecutor.execute — expression为null时details中为空字符串")
    void dqExecutor_execute_nullExpression_shouldHandleGracefully() {
        dqRule.setExpression(null);
        DqRuleExecutor executor = new DqRuleExecutor();
        RuleExecutionResult result = executor.execute(dqRule, Map.of());

        assertThat(result.getDetails()).containsEntry("expression", "");
    }

    @Test
    @DisplayName("MaskRuleExecutor.getType — 返回MASK")
    void maskExecutor_getType_shouldReturnMASK() {
        MaskRuleExecutor executor = new MaskRuleExecutor();
        assertThat(executor.getType()).isEqualTo("MASK");
    }

    @Test
    @DisplayName("MaskRuleExecutor.execute — 返回PASS和SIMULATED")
    void maskExecutor_execute_shouldReturnPass() {
        MaskRuleExecutor executor = new MaskRuleExecutor();
        RuleExecutionResult result = executor.execute(maskRule, Map.of());

        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("SIMULATED");
        assertThat(result.getRuleId()).isEqualTo(2L);
        assertThat(result.getDetails()).containsEntry("type", "MASK");
    }

    @Test
    @DisplayName("AlertRuleExecutor.getType — 返回ALERT")
    void alertExecutor_getType_shouldReturnALERT() {
        AlertRuleExecutor executor = new AlertRuleExecutor();
        assertThat(executor.getType()).isEqualTo("ALERT");
    }

    @Test
    @DisplayName("AlertRuleExecutor.execute — 返回PASS和SIMULATED")
    void alertExecutor_execute_shouldReturnPass() {
        AlertRuleExecutor executor = new AlertRuleExecutor();
        RuleExecutionResult result = executor.execute(alertRule, Map.of());

        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("SIMULATED");
        assertThat(result.getRuleId()).isEqualTo(3L);
        assertThat(result.getDetails()).containsEntry("type", "ALERT");
    }

    @Test
    @DisplayName("执行器 — durationMs初始为0")
    void executor_durationMs_shouldBeZero() {
        DqRuleExecutor executor = new DqRuleExecutor();
        RuleExecutionResult result = executor.execute(dqRule, Map.of());

        assertThat(result.getDurationMs()).isEqualTo(0L);
    }

    @Test
    @DisplayName("执行器 — executedAt不为null")
    void executor_executedAt_shouldNotBeNull() {
        DqRuleExecutor executor = new DqRuleExecutor();
        RuleExecutionResult result = executor.execute(dqRule, Map.of());

        assertThat(result.getExecutedAt()).isNotNull();
    }
}