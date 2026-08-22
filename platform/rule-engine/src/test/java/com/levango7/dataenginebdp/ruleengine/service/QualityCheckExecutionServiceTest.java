package com.levango7.dataenginebdp.ruleengine.service;

import com.levango7.dataenginebdp.ruleengine.engine.DqRuleExecutor;
import com.levango7.dataenginebdp.ruleengine.model.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link QualityCheckExecutionService} 单元测试。
 *
 * <p>覆盖各校验分支（缺表达式/严格阈值/阻断级别/默认通过）、
 * 结果缓存、通过数与已校验总数统计、clear 与 null 参数边界。</p>
 */
class QualityCheckExecutionServiceTest {

    private QualityCheckExecutionService service;

    @BeforeEach
    void setUp() {
        // DqRuleExecutor 无参构造器内部 jdbcTemplate=null，SQL 模式会返回 ERROR；
        // 本测试用例均为非 SQL 规则（threshold= 表达式），走降级路径，不受影响。
        service = new QualityCheckExecutionService(new DqRuleExecutor());
    }

    private Rule rule(Long id, String expression, String severity) {
        Rule r = new Rule();
        r.setId(id);
        r.setName("test-rule-" + id);
        r.setExpression(expression);
        r.setSeverity(severity);
        return r;
    }

    @Test
    void executeCheck_blankExpression_returnsNotPassed() {
        Rule r = rule(1L, "", "WARN");
        QualityCheckExecutionService.CheckResult result = service.executeCheck(r);

        assertThat(result.ruleId()).isEqualTo(1L);
        assertThat(result.passed()).isFalse();
        assertThat(result.message()).isEqualTo("缺少校验表达式");
        assertThat(result.lastCheckAt()).isNotNull();
    }

    @Test
    void executeCheck_nullExpression_returnsNotPassed() {
        Rule r = rule(2L, null, "WARN");
        QualityCheckExecutionService.CheckResult result = service.executeCheck(r);

        assertThat(result.passed()).isFalse();
        assertThat(result.message()).isEqualTo("缺少校验表达式");
    }

    @Test
    void executeCheck_threshold100Percent_returnsPassed() {
        Rule r = rule(3L, "threshold=100%", "WARN");
        QualityCheckExecutionService.CheckResult result = service.executeCheck(r);

        assertThat(result.passed()).isTrue();
        assertThat(result.message()).isEqualTo("校验通过：达到严格阈值");
    }

    @Test
    void executeCheck_threshold0_returnsPassed() {
        Rule r = rule(4L, "threshold=0", "BLOCK");
        QualityCheckExecutionService.CheckResult result = service.executeCheck(r);

        assertThat(result.passed()).isTrue();
        assertThat(result.message()).isEqualTo("校验通过：达到严格阈值");
    }

    @Test
    void executeCheck_blockSeverityWithoutStrictThreshold_returnsNotPassed() {
        Rule r = rule(5L, "threshold=90%", "BLOCK");
        QualityCheckExecutionService.CheckResult result = service.executeCheck(r);

        assertThat(result.passed()).isFalse();
        assertThat(result.message()).isEqualTo("校验未通过：阻断级别未达标");
    }

    @Test
    void executeCheck_defaultCase_returnsPassed() {
        Rule r = rule(6L, "threshold=50%", "WARN");
        QualityCheckExecutionService.CheckResult result = service.executeCheck(r);

        assertThat(result.passed()).isTrue();
        assertThat(result.message()).isEqualTo("校验通过");
    }

    @Test
    void executeCheck_cachesResultByRuleId() {
        Rule r = rule(7L, "threshold=100%", "WARN");
        QualityCheckExecutionService.CheckResult first = service.executeCheck(r);

        QualityCheckExecutionService.CheckResult cached = service.getCheckResult(7L);
        assertThat(cached).isEqualTo(first);
    }

    @Test
    void getCheckResult_unknownRuleId_returnsNull() {
        assertThat(service.getCheckResult(999L)).isNull();
    }

    @Test
    void getCheckResult_nullRuleId_returnsNull() {
        assertThat(service.getCheckResult(null)).isNull();
    }

    @Test
    void getPassedCount_countsOnlyPassedResults() {
        service.executeCheck(rule(1L, "threshold=100%", "WARN")); // passed
        service.executeCheck(rule(2L, "threshold=90%", "BLOCK")); // not passed
        service.executeCheck(rule(3L, "threshold=0", "WARN"));    // passed

        assertThat(service.getPassedCount()).isEqualTo(2L);
        assertThat(service.getTotalChecked()).isEqualTo(3);
    }

    @Test
    void getPassedCount_emptyService_returnsZero() {
        assertThat(service.getPassedCount()).isZero();
        assertThat(service.getTotalChecked()).isZero();
    }

    @Test
    void clear_removesAllResults() {
        service.executeCheck(rule(1L, "threshold=100%", "WARN"));
        assertThat(service.getTotalChecked()).isEqualTo(1);

        service.clear();

        assertThat(service.getTotalChecked()).isZero();
        assertThat(service.getPassedCount()).isZero();
        assertThat(service.getCheckResult(1L)).isNull();
    }

    @Test
    void executeCheck_nullRule_throwsException() {
        assertThatThrownBy(() -> service.executeCheck(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rule");
    }

    @Test
    void executeCheck_nullRuleId_throwsException() {
        Rule r = new Rule();
        r.setName("no-id");
        r.setExpression("threshold=100%");
        r.setSeverity("WARN");

        assertThatThrownBy(() -> service.executeCheck(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rule.id");
    }

    @Test
    void executeCheck_lastCheckAtIsRecent() {
        Rule r = rule(8L, "threshold=100%", "WARN");
        Instant before = Instant.now();
        QualityCheckExecutionService.CheckResult result = service.executeCheck(r);
        Instant after = Instant.now();

        assertThat(result.lastCheckAt()).isBetween(before, after);
    }
}