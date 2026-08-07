package com.shuqing.bigdata.governance.realtime.quality;

import com.shuqing.bigdata.governance.realtime.model.QualityAlert;
import com.shuqing.bigdata.governance.realtime.model.QualityRuleResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式质量规则引擎。
 *
 * <p>整合 {@link QualityRuleEvaluator}（规则评估）与 {@link QualityAlertEmitter}
 * （告警发射），提供端到端的流式质量治理能力。
 *
 * <p>两种运行模式：
 * <ol>
 *   <li><b>同步模式</b>（本组件）：Spring Boot REST 端点直接调用 {@link #evaluateAndAlert}，
 *       适用于低吞吐场景与测试</li>
 *   <li><b>Flink CEP 模式</b>（{@code QualityRuleCepJob}）：将规则编译为 Flink CEP Pattern，
 *       提交到 Flink 集群流式评估，适用于高吞吐生产场景</li>
 * </ol>
 *
 * <p>性能目标：质量评估 + 告警发射 ≤ 5s（治理闭环 10s 预算的一部分）。
 */
@Component
public class StreamingQualityRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(StreamingQualityRuleEngine.class);

    private final QualityRuleEvaluator evaluator;
    private final QualityAlertEmitter alertEmitter;
    private final Timer engineTimer;

    /** 规则注册表：ruleId → QualityRule */
    private final ConcurrentHashMap<String, QualityRule> ruleRegistry = new ConcurrentHashMap<>();

    /** 表 → 规则 ID 列表（用于按表批量评估） */
    private final ConcurrentHashMap<String, List<String>> tableRuleIndex = new ConcurrentHashMap<>();

    @Autowired
    public StreamingQualityRuleEngine(QualityRuleEvaluator evaluator,
                                      QualityAlertEmitter alertEmitter,
                                      MeterRegistry meterRegistry) {
        this.evaluator = evaluator;
        this.alertEmitter = alertEmitter;
        this.engineTimer = Timer.builder("governance.quality.engine.duration")
                .description("质量规则引擎端到端耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /** 测试用构造函数（无 MeterRegistry） */
    public StreamingQualityRuleEngine(QualityRuleEvaluator evaluator,
                                      QualityAlertEmitter alertEmitter) {
        this.evaluator = evaluator;
        this.alertEmitter = alertEmitter;
        this.engineTimer = null;
    }

    /**
     * 注册质量规则。
     *
     * @param rule 质量规则
     */
    public void registerRule(QualityRule rule) {
        ruleRegistry.put(rule.getRuleId(), rule);
        tableRuleIndex.computeIfAbsent(rule.getTableIdentifier(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(rule.getRuleId());
        log.info("Quality rule registered: ruleId={}, type={}, table={}, field={}",
                rule.getRuleId(), rule.getRuleType(), rule.getTableIdentifier(), rule.getFieldName());
    }

    /**
     * 注销质量规则。
     *
     * @param ruleId 规则 ID
     */
    public void unregisterRule(String ruleId) {
        QualityRule removed = ruleRegistry.remove(ruleId);
        if (removed != null) {
            List<String> rules = tableRuleIndex.get(removed.getTableIdentifier());
            if (rules != null) {
                rules.remove(ruleId);
            }
            evaluator.clearUniqueState(ruleId);
            log.info("Quality rule unregistered: ruleId={}", ruleId);
        }
    }

    /**
     * 评估单条记录的指定规则，违规即告警。
     *
     * @param ruleId 规则 ID
     * @param recordId 记录 ID
     * @param fieldValue 字段值
     * @param violationTimestamp 违规数据产生时间戳
     * @param pipelineStartTimestamp 治理闭环开始时间戳
     * @return 评估结果；若违规则同时返回触发的告警（通过 result.isViolation() 判断）
     */
    public EvaluationOutcome evaluateAndAlert(String ruleId, String recordId, Object fieldValue,
                                              Instant violationTimestamp, Instant pipelineStartTimestamp) {
        long start = System.currentTimeMillis();
        QualityRule rule = ruleRegistry.get(ruleId);
        if (rule == null) {
            log.warn("Rule not found: ruleId={}", ruleId);
            return new EvaluationOutcome(null, null);
        }

        // Step 1: 评估规则
        QualityRuleResult result = evaluator.evaluate(rule, recordId, fieldValue);

        // Step 2: 违规即告警
        QualityAlert alert = null;
        if (result.isViolation()) {
            alert = alertEmitter.emit(result, violationTimestamp, pipelineStartTimestamp);
        }

        long duration = System.currentTimeMillis() - start;
        if (engineTimer != null) {
            engineTimer.record(java.time.Duration.ofMillis(duration));
        }
        return new EvaluationOutcome(result, alert);
    }

    /**
     * 批量评估指定表的所有规则。
     *
     * @param tableIdentifier 表标识符
     * @param recordId 记录 ID
     * @param fieldValues 字段名 → 字段值
     * @param violationTimestamp 违规数据产生时间戳
     * @param pipelineStartTimestamp 治理闭环开始时间戳
     * @return 评估结果列表（含违规告警）
     */
    public List<EvaluationOutcome> evaluateTable(String tableIdentifier, String recordId,
                                                 Map<String, Object> fieldValues,
                                                 Instant violationTimestamp,
                                                 Instant pipelineStartTimestamp) {
        List<EvaluationOutcome> outcomes = new java.util.ArrayList<>();
        List<String> ruleIds = tableRuleIndex.get(tableIdentifier);
        if (ruleIds == null) {
            return outcomes;
        }
        for (String ruleId : ruleIds) {
            QualityRule rule = ruleRegistry.get(ruleId);
            if (rule == null || !rule.isEnabled()) {
                continue;
            }
            Object fieldValue = fieldValues.get(rule.getFieldName());
            EvaluationOutcome outcome = evaluateAndAlert(ruleId, recordId, fieldValue,
                    violationTimestamp, pipelineStartTimestamp);
            outcomes.add(outcome);
        }
        return outcomes;
    }

    /**
     * 获取所有已注册规则。
     */
    public Map<String, QualityRule> getRuleRegistry() {
        return java.util.Collections.unmodifiableMap(ruleRegistry);
    }

    /**
     * 获取告警发射器（用于查询告警缓冲）。
     */
    public QualityAlertEmitter getAlertEmitter() {
        return alertEmitter;
    }

    /**
     * 评估结果与告警的组合。
     *
     * @param result 评估结果（可能为 null 表示规则不存在）
     * @param alert 触发的告警（无违规时为 null）
     */
    public record EvaluationOutcome(QualityRuleResult result, QualityAlert alert) {}
}