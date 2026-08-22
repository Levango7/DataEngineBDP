package com.levango7.dataenginebdp.governance.realtime.quality;

import org.apache.flink.cep.pattern.conditions.IterativeCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link QualityRuleCondition} 单元测试。
 *
 * <p>验证 CEP 条件过滤函数的行为：
 * <ul>
 *   <li>构造条件时应正确绑定规则</li>
 *   <li>filter 对 null 记录返回 false</li>
 *   <li>filter 对表/字段不匹配的记录返回 false</li>
 *   <li>filter 对匹配且违规的记录返回 true</li>
 *   <li>filter 对匹配但不违规的记录返回 false</li>
 * </ul>
 */
@DisplayName("QualityRuleCondition CEP 条件过滤")
class QualityRuleConditionTest {

    private IterativeCondition.Context<QualityRuleCepJob.QualityRecord> context;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // filter 实现未使用 context，使用 mock 占位以保持接口契约
        context = mock(IterativeCondition.Context.class);
    }

    private QualityRule buildRule(QualityRule.RuleType type, Map<String, Object> params) {
        return QualityRule.builder()
                .ruleId("r-cond-1")
                .ruleType(type)
                .ruleName("condition-test-rule")
                .tableIdentifier("db.test_table")
                .fieldName("status")
                .severity("WARN")
                .enabled(true)
                .params(params)
                .build();
    }

    private QualityRuleCepJob.QualityRecord buildRecord(String table, String field, Object value) {
        return new QualityRuleCepJob.QualityRecord("rec-1", table, field, value, System.currentTimeMillis());
    }

    @Nested
    @DisplayName("条件构造")
    class Construction {

        @Test
        @DisplayName("构造条件不应抛出异常")
        void constructWithoutError() {
            QualityRule rule = buildRule(QualityRule.RuleType.NOT_NULL, null);

            QualityRuleCondition condition = new QualityRuleCondition(rule);

            assertThat(condition).isNotNull();
        }
    }

    @Nested
    @DisplayName("filter 过滤逻辑")
    class FilterLogic {

        @Test
        @DisplayName("null 记录应返回 false")
        void nullRecordReturnsFalse() throws Exception {
            QualityRule rule = buildRule(QualityRule.RuleType.NOT_NULL, null);
            QualityRuleCondition condition = new QualityRuleCondition(rule);

            boolean result = condition.filter(null, context);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("表标识符不匹配应返回 false")
        void mismatchedTableReturnsFalse() throws Exception {
            QualityRule rule = buildRule(QualityRule.RuleType.NOT_NULL, null);
            QualityRuleCondition condition = new QualityRuleCondition(rule);

            QualityRuleCepJob.QualityRecord record =
                    buildRecord("db.other_table", "status", null);

            assertThat(condition.filter(record, context)).isFalse();
        }

        @Test
        @DisplayName("字段名不匹配应返回 false")
        void mismatchedFieldReturnsFalse() throws Exception {
            QualityRule rule = buildRule(QualityRule.RuleType.NOT_NULL, null);
            QualityRuleCondition condition = new QualityRuleCondition(rule);

            QualityRuleCepJob.QualityRecord record =
                    buildRecord("db.test_table", "other_field", null);

            assertThat(condition.filter(record, context)).isFalse();
        }

        @Test
        @DisplayName("匹配且违规（NOT_NULL + null 值）应返回 true")
        void matchingViolationReturnsTrue() throws Exception {
            QualityRule rule = buildRule(QualityRule.RuleType.NOT_NULL, null);
            QualityRuleCondition condition = new QualityRuleCondition(rule);

            QualityRuleCepJob.QualityRecord record =
                    buildRecord("db.test_table", "status", null);

            assertThat(condition.filter(record, context)).isTrue();
        }

        @Test
        @DisplayName("匹配但不违规（NOT_NULL + 非空值）应返回 false")
        void matchingNonViolationReturnsFalse() throws Exception {
            QualityRule rule = buildRule(QualityRule.RuleType.NOT_NULL, null);
            QualityRuleCondition condition = new QualityRuleCondition(rule);

            QualityRuleCepJob.QualityRecord record =
                    buildRecord("db.test_table", "status", "ACTIVE");

            assertThat(condition.filter(record, context)).isFalse();
        }

        @Test
        @DisplayName("RANGE 违规应返回 true")
        void rangeViolationReturnsTrue() throws Exception {
            QualityRule rule = buildRule(QualityRule.RuleType.RANGE,
                    Map.of("min", 0, "max", 100));
            QualityRuleCondition condition = new QualityRuleCondition(rule);

            QualityRuleCepJob.QualityRecord record =
                    buildRecord("db.test_table", "status", 200);

            assertThat(condition.filter(record, context)).isTrue();
        }

        @Test
        @DisplayName("CUSTOM 违规应返回 true")
        void customViolationReturnsTrue() throws Exception {
            QualityRule rule = buildRule(QualityRule.RuleType.CUSTOM,
                    Map.of("expression", "field == 'BAD'"));
            QualityRuleCondition condition = new QualityRuleCondition(rule);

            QualityRuleCepJob.QualityRecord record =
                    buildRecord("db.test_table", "status", "BAD");

            assertThat(condition.filter(record, context)).isTrue();
        }
    }
}