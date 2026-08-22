package com.levango7.dataenginebdp.governance.realtime.quality;

import com.levango7.dataenginebdp.governance.realtime.model.QualityRuleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QualityRuleEvaluator} 单元测试。
 *
 * <p>覆盖五种规则类型的评估逻辑：
 * <ul>
 *   <li>NOT_NULL：非空检查</li>
 *   <li>UNIQUE：唯一性检查</li>
 *   <li>RANGE：范围检查（含边界值）</li>
 *   <li>FORMAT：正则格式检查</li>
 *   <li>CUSTOM：自定义表达式检查</li>
 *   <li>禁用规则与统计</li>
 * </ul>
 */
@DisplayName("QualityRuleEvaluator 质量规则评估")
class QualityRuleEvaluatorTest {

    private QualityRuleEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new QualityRuleEvaluator();
    }

    private QualityRule buildRule(String ruleId, QualityRule.RuleType type, String field,
                                  Map<String, Object> params) {
        return QualityRule.builder()
                .ruleId(ruleId)
                .ruleType(type)
                .ruleName("test-rule-" + ruleId)
                .tableIdentifier("db.test_table")
                .fieldName(field)
                .severity("WARN")
                .enabled(true)
                .params(params)
                .build();
    }

    @Nested
    @DisplayName("NOT_NULL 规则")
    class NotNullRule {

        @Test
        @DisplayName("非空值应 PASS")
        void nonNullValuePasses() {
            QualityRule rule = buildRule("r-nn-1", QualityRule.RuleType.NOT_NULL, "name", null);

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", "alice");

            assertThat(result.getResult()).isEqualTo("PASS");
            assertThat(result.isViolation()).isFalse();
            assertThat(result.getViolationValue()).isNull();
            assertThat(result.getRuleId()).isEqualTo("r-nn-1");
            assertThat(result.getRuleType()).isEqualTo("NOT_NULL");
            assertThat(result.getFieldName()).isEqualTo("name");
            assertThat(result.getRecordId()).isEqualTo("rec-1");
        }

        @Test
        @DisplayName("null 值应 FAIL")
        void nullValueFails() {
            QualityRule rule = buildRule("r-nn-2", QualityRule.RuleType.NOT_NULL, "name", null);

            QualityRuleResult result = evaluator.evaluate(rule, "rec-2", null);

            assertThat(result.getResult()).isEqualTo("FAIL");
            assertThat(result.isViolation()).isTrue();
            assertThat(result.getViolationValue()).isNull();
        }
    }

    @Nested
    @DisplayName("UNIQUE 规则")
    class UniqueRule {

        @Test
        @DisplayName("首次出现的值应 PASS")
        void firstOccurrencePasses() {
            QualityRule rule = buildRule("r-uq-1", QualityRule.RuleType.UNIQUE, "id", null);

            QualityRuleResult r1 = evaluator.evaluate(rule, "rec-1", 100);
            assertThat(r1.getResult()).isEqualTo("PASS");
        }

        @Test
        @DisplayName("重复值应 FAIL")
        void duplicateValueFails() {
            QualityRule rule = buildRule("r-uq-2", QualityRule.RuleType.UNIQUE, "id", null);

            evaluator.evaluate(rule, "rec-1", 100);
            QualityRuleResult r2 = evaluator.evaluate(rule, "rec-2", 100);

            assertThat(r2.getResult()).isEqualTo("FAIL");
            assertThat(r2.isViolation()).isTrue();
            assertThat(r2.getViolationValue()).isEqualTo(100);
        }

        @Test
        @DisplayName("null 值不参与唯一性检查，应 PASS")
        void nullValuePassesUnique() {
            QualityRule rule = buildRule("r-uq-3", QualityRule.RuleType.UNIQUE, "id", null);

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", null);
            assertThat(result.getResult()).isEqualTo("PASS");
        }
    }

    @Nested
    @DisplayName("RANGE 规则")
    class RangeRule {

        @Test
        @DisplayName("范围内值应 PASS")
        void valueInRangePasses() {
            QualityRule rule = buildRule("r-rg-1", QualityRule.RuleType.RANGE, "age",
                    Map.of("min", 0, "max", 150));

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", 25);

            assertThat(result.getResult()).isEqualTo("PASS");
        }

        @Test
        @DisplayName("超出上限应 FAIL")
        void valueAboveMaxFails() {
            QualityRule rule = buildRule("r-rg-2", QualityRule.RuleType.RANGE, "age",
                    Map.of("min", 0, "max", 150));

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", 200);

            assertThat(result.getResult()).isEqualTo("FAIL");
            assertThat(result.getViolationValue()).isEqualTo(200);
        }

        @Test
        @DisplayName("低于下限应 FAIL")
        void valueBelowMinFails() {
            QualityRule rule = buildRule("r-rg-3", QualityRule.RuleType.RANGE, "age",
                    Map.of("min", 0, "max", 150));

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", -1);

            assertThat(result.getResult()).isEqualTo("FAIL");
        }

        @Test
        @DisplayName("边界值等于 min 应 PASS（闭区间）")
        void boundaryMinPasses() {
            QualityRule rule = buildRule("r-rg-4", QualityRule.RuleType.RANGE, "age",
                    Map.of("min", 0, "max", 150));

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", 0);

            assertThat(result.getResult()).isEqualTo("PASS");
        }

        @Test
        @DisplayName("边界值等于 max 应 PASS（闭区间）")
        void boundaryMaxPasses() {
            QualityRule rule = buildRule("r-rg-5", QualityRule.RuleType.RANGE, "age",
                    Map.of("min", 0, "max", 150));

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", 150);

            assertThat(result.getResult()).isEqualTo("PASS");
        }

        @Test
        @DisplayName("null 值不参与范围检查，应 PASS")
        void nullValuePassesRange() {
            QualityRule rule = buildRule("r-rg-6", QualityRule.RuleType.RANGE, "age",
                    Map.of("min", 0, "max", 150));

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", null);
            assertThat(result.getResult()).isEqualTo("PASS");
        }
    }

    @Nested
    @DisplayName("FORMAT 规则")
    class FormatRule {

        @Test
        @DisplayName("匹配正则的值应 PASS")
        void matchingValuePasses() {
            QualityRule rule = buildRule("r-fmt-1", QualityRule.RuleType.FORMAT, "email",
                    Map.of("pattern", "[a-z]+@[a-z]+\\.[a-z]+"));

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", "alice@example.com");

            assertThat(result.getResult()).isEqualTo("PASS");
        }

        @Test
        @DisplayName("不匹配正则的值应 FAIL")
        void nonMatchingValueFails() {
            QualityRule rule = buildRule("r-fmt-2", QualityRule.RuleType.FORMAT, "email",
                    Map.of("pattern", "[a-z]+@[a-z]+\\.[a-z]+"));

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", "invalid-email");

            assertThat(result.getResult()).isEqualTo("FAIL");
            assertThat(result.getViolationValue()).isEqualTo("invalid-email");
        }

        @Test
        @DisplayName("缺少 pattern 参数应 PASS（降级）")
        void missingPatternPasses() {
            QualityRule rule = buildRule("r-fmt-3", QualityRule.RuleType.FORMAT, "email", null);

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", "anything");
            assertThat(result.getResult()).isEqualTo("PASS");
        }
    }

    @Nested
    @DisplayName("CUSTOM 规则")
    class CustomRule {

        @Test
        @DisplayName("字段值等于表达式指定值应 FAIL（违规）")
        void valueEqualsExpressionTargetFails() {
            QualityRule rule = buildRule("r-cu-1", QualityRule.RuleType.CUSTOM, "status",
                    Map.of("expression", "field == 'INVALID'"));

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", "INVALID");

            assertThat(result.getResult()).isEqualTo("FAIL");
            assertThat(result.isViolation()).isTrue();
        }

        @Test
        @DisplayName("字段值不等于表达式指定值应 PASS")
        void valueNotEqualsExpressionTargetPasses() {
            QualityRule rule = buildRule("r-cu-2", QualityRule.RuleType.CUSTOM, "status",
                    Map.of("expression", "field == 'INVALID'"));

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", "VALID");

            assertThat(result.getResult()).isEqualTo("PASS");
        }

        @Test
        @DisplayName("缺少 expression 参数应 PASS（降级）")
        void missingExpressionPasses() {
            QualityRule rule = buildRule("r-cu-3", QualityRule.RuleType.CUSTOM, "status", null);

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", "anything");
            assertThat(result.getResult()).isEqualTo("PASS");
        }
    }

    @Nested
    @DisplayName("禁用规则与评估统计")
    class DisabledAndStats {

        @Test
        @DisplayName("禁用规则应直接 PASS，不执行评估")
        void disabledRulePasses() {
            QualityRule rule = QualityRule.builder()
                    .ruleId("r-dis-1")
                    .ruleType(QualityRule.RuleType.NOT_NULL)
                    .ruleName("disabled-rule")
                    .tableIdentifier("db.test_table")
                    .fieldName("name")
                    .severity("WARN")
                    .enabled(false)
                    .build();

            QualityRuleResult result = evaluator.evaluate(rule, "rec-1", null);

            assertThat(result.getResult()).isEqualTo("PASS");
            assertThat(result.isViolation()).isFalse();
        }

        @Test
        @DisplayName("评估统计应记录 PASS/FAIL 计数")
        void evalStatsRecordsCounts() {
            QualityRule rule = buildRule("r-stat-1", QualityRule.RuleType.NOT_NULL, "name", null);

            evaluator.evaluate(rule, "rec-1", "alice");
            evaluator.evaluate(rule, "rec-2", null);
            evaluator.evaluate(rule, "rec-3", "bob");

            Map<String, Long> stats = evaluator.getEvalStats();
            assertThat(stats).containsEntry("PASSCount", 2L);
            assertThat(stats).containsEntry("FAILCount", 1L);
        }

        @Test
        @DisplayName("clearAllState 应清空所有状态")
        void clearAllStateResetsEverything() {
            QualityRule rule = buildRule("r-clr-1", QualityRule.RuleType.UNIQUE, "id", null);

            evaluator.evaluate(rule, "rec-1", 100);
            evaluator.clearAllState();

            // 清空后再次评估相同值应 PASS（去重状态已清空）
            QualityRuleResult result = evaluator.evaluate(rule, "rec-2", 100);
            assertThat(result.getResult()).isEqualTo("PASS");
        }
    }
}