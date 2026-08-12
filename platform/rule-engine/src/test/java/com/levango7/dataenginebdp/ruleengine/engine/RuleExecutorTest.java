package com.levango7.dataenginebdp.ruleengine.engine;

import com.levango7.dataenginebdp.ruleengine.model.Rule;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 规则执行器单元测试。
 *
 * <p>直接测试 DqRuleExecutor、MaskRuleExecutor、AlertRuleExecutor 的真实执行逻辑：
 * <ul>
 *   <li>DQ — 条件模式（基于 context 评估）+ SQL 模式（mock JdbcTemplate）</li>
 *   <li>ALERT — 条件评估，触发/未触发分支</li>
 *   <li>MASK — 四种脱敏策略（mask/hash/replace/pseudonymize）</li>
 * </ul>
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
        dqRule.setExpression("nullCount > 0");

        maskRule = new Rule();
        maskRule.setId(2L);
        maskRule.setType("MASK");
        maskRule.setExpression("mask:3,4");

        alertRule = new Rule();
        alertRule.setId(3L);
        alertRule.setType("ALERT");
        alertRule.setExpression("errorRate > 0.05");
    }

    // ==================== DqRuleExecutor ====================

    @Test
    @DisplayName("DqRuleExecutor.getType — 返回DQ")
    void dqExecutor_getType_shouldReturnDQ() {
        DqRuleExecutor executor = new DqRuleExecutor();
        assertThat(executor.getType()).isEqualTo("DQ");
    }

    @Test
    @DisplayName("DQ条件模式 — 条件未触发(无违规)返回PASS")
    void dqExecutor_conditionNotTriggered_shouldReturnPass() {
        // nullCount=0，不满足 "nullCount > 0" → 未触发 → PASS
        DqRuleExecutor executor = new DqRuleExecutor();
        RuleExecutionResult result = executor.execute(dqRule, Map.of("nullCount", 0));

        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("DQ_CHECK_PASSED");
        assertThat(result.getRuleId()).isEqualTo(1L);
        assertThat(result.getDetails()).containsEntry("type", "DQ");
        assertThat(result.getDetails()).containsEntry("triggered", false);
        assertThat(result.getDetails()).containsEntry("evaluated", true);
    }

    @Test
    @DisplayName("DQ条件模式 — 条件触发(有违规)返回FAIL")
    void dqExecutor_conditionTriggered_shouldReturnFail() {
        // nullCount=5，满足 "nullCount > 0" → 触发 → FAIL
        DqRuleExecutor executor = new DqRuleExecutor();
        RuleExecutionResult result = executor.execute(dqRule, Map.of("nullCount", 5));

        assertThat(result.getStatus()).isEqualTo("FAIL");
        assertThat(result.getMessage()).isEqualTo("DQ_CHECK_FAILED");
        assertThat(result.getDetails()).containsEntry("triggered", true);
        assertThat(result.getDetails()).containsEntry("evaluated", true);
    }

    @Test
    @DisplayName("DQ条件模式 — 缺少指标值时返回PASS(无法评估视为无违规)")
    void dqExecutor_metricMissing_shouldReturnPass() {
        DqRuleExecutor executor = new DqRuleExecutor();
        RuleExecutionResult result = executor.execute(dqRule, Map.of());

        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getDetails()).containsEntry("evaluated", false);
    }

    @Test
    @DisplayName("DQ条件模式 — expression为null时details中为空字符串")
    void dqExecutor_execute_nullExpression_shouldHandleGracefully() {
        dqRule.setExpression(null);
        DqRuleExecutor executor = new DqRuleExecutor();
        RuleExecutionResult result = executor.execute(dqRule, Map.of());

        assertThat(result.getDetails()).containsEntry("expression", "");
        assertThat(result.getStatus()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("DQ SQL模式 — violationCount=0返回PASS")
    void dqExecutor_sqlMode_zeroViolation_shouldReturnPass() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        dqRule.setExpression("sql:SELECT COUNT(*) FROM orders WHERE amount IS NULL");
        DqRuleExecutor executor = new DqRuleExecutor(jdbc);

        RuleExecutionResult result = executor.execute(dqRule, Map.of());

        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("DQ_CHECK_PASSED");
        assertThat(result.getDetails()).containsEntry("violationCount", 0L);
    }

    @Test
    @DisplayName("DQ SQL模式 — violationCount>0返回FAIL")
    void dqExecutor_sqlMode_nonZeroViolation_shouldReturnFail() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);
        dqRule.setExpression("sql:SELECT COUNT(*) FROM orders WHERE amount IS NULL");
        DqRuleExecutor executor = new DqRuleExecutor(jdbc);

        RuleExecutionResult result = executor.execute(dqRule, Map.of());

        assertThat(result.getStatus()).isEqualTo("FAIL");
        assertThat(result.getMessage()).isEqualTo("DQ_CHECK_FAILED");
        assertThat(result.getDetails()).containsEntry("violationCount", 3L);
    }

    @Test
    @DisplayName("DQ SQL模式 — 未配置JdbcTemplate返回ERROR")
    void dqExecutor_sqlMode_noDataSource_shouldReturnError() {
        dqRule.setExpression("sql:SELECT COUNT(*) FROM orders WHERE amount IS NULL");
        DqRuleExecutor executor = new DqRuleExecutor(); // 无 JdbcTemplate

        RuleExecutionResult result = executor.execute(dqRule, Map.of());

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).isEqualTo("DATA_SOURCE_NOT_CONFIGURED");
    }

    // ==================== MaskRuleExecutor ====================

    @Test
    @DisplayName("MaskRuleExecutor.getType — 返回MASK")
    void maskExecutor_getType_shouldReturnMASK() {
        MaskRuleExecutor executor = new MaskRuleExecutor();
        assertThat(executor.getType()).isEqualTo("MASK");
    }

    @Test
    @DisplayName("MASK mask策略 — 保留前后位掩码")
    void maskExecutor_maskStrategy_shouldMaskMiddle() {
        MaskRuleExecutor executor = new MaskRuleExecutor();
        // mask:3,4 对 13812345678(11位) → 138****5678
        RuleExecutionResult result = executor.execute(maskRule,
                Map.of("input", "13812345678"));

        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("MASK_APPLIED");
        assertThat(result.getRuleId()).isEqualTo(2L);
        assertThat(result.getDetails()).containsEntry("maskedValue", "138****5678");
        assertThat(result.getDetails()).containsEntry("strategy", "mask");
    }

    @Test
    @DisplayName("MASK hash策略 — 返回SHA-256十六进制摘要")
    void maskExecutor_hashStrategy_shouldReturnHash() {
        MaskRuleExecutor executor = new MaskRuleExecutor();
        maskRule.setExpression("hash");
        RuleExecutionResult result = executor.execute(maskRule, Map.of("input", "secret"));

        assertThat(result.getStatus()).isEqualTo("PASS");
        String masked = (String) result.getDetails().get("maskedValue");
        // SHA-256 输出 64 位十六进制
        assertThat(masked).hasSize(64);
        assertThat(masked).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("MASK replace策略 — 整体替换")
    void maskExecutor_replaceStrategy_shouldReplaceAll() {
        MaskRuleExecutor executor = new MaskRuleExecutor();
        maskRule.setExpression("replace:[REDACTED]");
        RuleExecutionResult result = executor.execute(maskRule, Map.of("input", "sensitive"));

        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getDetails()).containsEntry("maskedValue", "[REDACTED]");
    }

    @Test
    @DisplayName("MASK pseudonymize策略 — 等长随机字符串")
    void maskExecutor_pseudonymizeStrategy_shouldReturnSameLengthRandom() {
        MaskRuleExecutor executor = new MaskRuleExecutor();
        maskRule.setExpression("pseudonymize");
        String original = "user12345";
        RuleExecutionResult result = executor.execute(maskRule, Map.of("input", original));

        assertThat(result.getStatus()).isEqualTo("PASS");
        String masked = (String) result.getDetails().get("maskedValue");
        assertThat(masked).hasSize(original.length());
        assertThat(masked).matches("[a-z]+");
        assertThat(masked).isNotEqualTo(original);
    }

    @Test
    @DisplayName("MASK — 输入缺失返回ERROR")
    void maskExecutor_inputMissing_shouldReturnError() {
        MaskRuleExecutor executor = new MaskRuleExecutor();
        RuleExecutionResult result = executor.execute(maskRule, Map.of());

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).isEqualTo("MASK_INPUT_MISSING");
    }

    @Test
    @DisplayName("MASK — 未知策略返回ERROR")
    void maskExecutor_unknownStrategy_shouldReturnError() {
        MaskRuleExecutor executor = new MaskRuleExecutor();
        maskRule.setExpression("PHONE_MASK"); // 未知策略
        RuleExecutionResult result = executor.execute(maskRule, Map.of("input", "13812345678"));

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getMessage()).startsWith("MASK_STRATEGY_ERROR");
    }

    @Test
    @DisplayName("MASK — 从value键读取输入")
    void maskExecutor_readFromValueKey_shouldWork() {
        MaskRuleExecutor executor = new MaskRuleExecutor();
        RuleExecutionResult result = executor.execute(maskRule, Map.of("value", "13812345678"));

        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getDetails()).containsEntry("maskedValue", "138****5678");
    }

    // ==================== AlertRuleExecutor ====================

    @Test
    @DisplayName("AlertRuleExecutor.getType — 返回ALERT")
    void alertExecutor_getType_shouldReturnALERT() {
        AlertRuleExecutor executor = new AlertRuleExecutor();
        assertThat(executor.getType()).isEqualTo("ALERT");
    }

    @Test
    @DisplayName("ALERT — 条件触发返回FAIL")
    void alertExecutor_triggered_shouldReturnFail() {
        // errorRate=0.1 > 0.05 → 触发告警
        AlertRuleExecutor executor = new AlertRuleExecutor();
        RuleExecutionResult result = executor.execute(alertRule, Map.of("errorRate", 0.1));

        assertThat(result.getStatus()).isEqualTo("FAIL");
        assertThat(result.getMessage()).isEqualTo("ALERT_TRIGGERED");
        assertThat(result.getRuleId()).isEqualTo(3L);
        assertThat(result.getDetails()).containsEntry("type", "ALERT");
        assertThat(result.getDetails()).containsEntry("triggered", true);
        assertThat(result.getDetails()).containsEntry("notified", false);
    }

    @Test
    @DisplayName("ALERT — 条件未触发返回PASS")
    void alertExecutor_notTriggered_shouldReturnPass() {
        // errorRate=0.01，不满足 > 0.05 → 未触发
        AlertRuleExecutor executor = new AlertRuleExecutor();
        RuleExecutionResult result = executor.execute(alertRule, Map.of("errorRate", 0.01));

        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("ALERT_NOT_TRIGGERED");
        assertThat(result.getDetails()).containsEntry("triggered", false);
    }

    @Test
    @DisplayName("ALERT — 缺少指标值返回PASS(无法评估)")
    void alertExecutor_metricMissing_shouldReturnPass() {
        AlertRuleExecutor executor = new AlertRuleExecutor();
        RuleExecutionResult result = executor.execute(alertRule, Map.of());

        assertThat(result.getStatus()).isEqualTo("PASS");
        assertThat(result.getMessage()).isEqualTo("ALERT_NOT_EVALUATED");
        assertThat(result.getDetails()).containsEntry("evaluated", false);
    }

    // ==================== 通用契约 ====================

    @Test
    @DisplayName("执行器 — durationMs非负")
    void executor_durationMs_shouldBeNonNegative() {
        DqRuleExecutor executor = new DqRuleExecutor();
        RuleExecutionResult result = executor.execute(dqRule, Map.of("nullCount", 0));

        assertThat(result.getDurationMs()).isNotNull();
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("执行器 — executedAt不为null")
    void executor_executedAt_shouldNotBeNull() {
        DqRuleExecutor executor = new DqRuleExecutor();
        RuleExecutionResult result = executor.execute(dqRule, Map.of("nullCount", 0));

        assertThat(result.getExecutedAt()).isNotNull();
    }

    @Test
    @DisplayName("执行器 — details始终包含type和expression")
    void executor_details_shouldContainTypeAndExpression() {
        DqRuleExecutor executor = new DqRuleExecutor();
        RuleExecutionResult result = executor.execute(dqRule, new HashMap<>(Map.of("nullCount", 0)));

        assertThat(result.getDetails()).containsKeys("type", "expression");
    }
}
