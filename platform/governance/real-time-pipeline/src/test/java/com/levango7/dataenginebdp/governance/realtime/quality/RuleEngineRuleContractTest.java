package com.levango7.dataenginebdp.governance.realtime.quality;

import com.levango7.dataenginebdp.governance.realtime.model.QualityRuleResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 "rule-engine 拉取的规则" 在 pipeline 评估前的参数形态是否可用。
 *
 * <p>这是契约耦合的实测：{@link RuleEngineRuleMapper} 把 rule-engine 视图映射为
 * {@link QualityRule}，其 {@code params} 必须能被 {@link QualityRuleEvaluator} 消费。
 */
class RuleEngineRuleContractTest {

    private static QualityRule mapped(String checkType, String threshold) {
        Map<String, Object> view = Map.of(
                "id", "1",
                "name", "r",
                "targetTable", "ods.orders",
                "targetField", "amount",
                "checkType", checkType,
                "threshold", threshold,
                "actionOnFail", "WARN",
                "status", "enabled");
        List<Map<String, Object>> views = List.of(view);
        return RuleEngineRuleMapper.mapAll(views, "tenant-a").get(0);
    }

    /** RANGE 规则的 min/max 必须是 Number，否则评估器强转会炸。 */
    @Test
    void rangeParamsAreNumeric() {
        QualityRule rule = mapped("range", "min=1,max=10");

        assertThat(rule.getParam("min"))
                .as("RANGE 的 min 必须是 Number（评估器强转 (Number)）")
                .isInstanceOf(Number.class);
        assertThat(rule.getParam("max"))
                .as("RANGE 的 max 必须是 Number")
                .isInstanceOf(Number.class);
    }

    /** 端到端：映射出的 RANGE 规则必须能真正评估，而不是因类型不匹配报 ERROR。 */
    @Test
    void mappedRangeRuleEvaluatesWithoutError() {
        QualityRule rule = mapped("range", "min=1,max=10");
        QualityRuleEvaluator evaluator = new QualityRuleEvaluator();

        QualityRuleResult inRange = evaluator.evaluate(rule, "rec-1", 5);
        assertThat(inRange.getResult())
                .as("范围内的值应 PASS，而不是因 ClassCastException 变 ERROR")
                .isEqualTo("PASS");

        QualityRuleResult outOfRange = evaluator.evaluate(rule, "rec-2", 99);
        assertThat(outOfRange.getResult())
                .as("范围外的值应 FAIL")
                .isEqualTo("FAIL");
    }

    /** 模板路径的 threshold 是 SQL，不能当作数值阈值透传。 */
    @Test
    void sqlThresholdIsNotTreatedAsNumericThreshold() {
        QualityRule rule = mapped("not_null", "sql:SELECT COUNT(*) FROM ods.orders WHERE amount IS NULL");

        assertThat(rule.getParams())
                .as("SQL 表达式应放进 sql 键，而不是被当成 threshold 数值")
                .containsKey("sql")
                .doesNotContainKey("threshold");
    }
}