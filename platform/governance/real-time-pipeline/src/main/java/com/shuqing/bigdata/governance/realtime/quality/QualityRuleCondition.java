package com.shuqing.bigdata.governance.realtime.quality;

import com.shuqing.bigdata.governance.realtime.model.QualityRuleResult;
import org.apache.flink.cep.pattern.conditions.IterativeCondition;

/**
 * 质量规则 CEP 条件过滤函数。
 *
 * <p>实现 Flink {@link IterativeCondition}，对每条 {@link QualityRuleCepJob.QualityRecord}
 * 评估指定 {@link QualityRule}，返回 {@code true} 表示违规（匹配 CEP 模式）。
 *
 * <p>委托给 {@link QualityRuleEvaluator} 的同步评估逻辑，保持一致性。
 */
public class QualityRuleCondition extends IterativeCondition<QualityRuleCepJob.QualityRecord> {

    private static final long serialVersionUID = 1L;

    private final QualityRule rule;
    private final transient QualityRuleEvaluator evaluator;

    public QualityRuleCondition(QualityRule rule) {
        this.rule = rule;
        this.evaluator = new QualityRuleEvaluator();
    }

    @Override
    public boolean filter(QualityRuleCepJob.QualityRecord record,
                          Context<QualityRuleCepJob.QualityRecord> ctx) throws Exception {
        if (record == null) {
            return false;
        }
        // 仅评估与规则匹配的字段
        if (!rule.getTableIdentifier().equals(record.tableIdentifier)
                || !rule.getFieldName().equals(record.fieldName)) {
            return false;
        }
        QualityRuleResult result = evaluator.evaluate(rule, record.recordId, record.fieldValue);
        return result.isViolation();
    }
}
