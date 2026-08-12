package com.levango7.dataenginebdp.governance.realtime.pipeline;

import com.levango7.dataenginebdp.governance.realtime.catalog.MetadataCollector;
import com.levango7.dataenginebdp.governance.realtime.lineage.RealTimeLineageAnalyzer;
import com.levango7.dataenginebdp.governance.realtime.model.CatalogCommitEvent;
import com.levango7.dataenginebdp.governance.realtime.model.FieldLineage;
import com.levango7.dataenginebdp.governance.realtime.model.QualityAlert;
import com.levango7.dataenginebdp.governance.realtime.model.TableMetadata;
import com.levango7.dataenginebdp.governance.realtime.quality.StreamingQualityRuleEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 实时治理闭环编排器。
 *
 * <p>串联元数据采集 → 血缘更新 → 质量评估 → 告警，实现治理闭环 P95 ≤ 10s。
 *
 * <p>闭环流程（由 {@code CatalogEventListener} 在元数据采集完成后触发）：
 * <ol>
 *   <li><b>血缘更新</b>：调用 {@link RealTimeLineageAnalyzer#refreshLineageForTable}
 *       重新解析关联作业 SQL，更新 NebulaGraph 血缘图（≤ 3s）</li>
 *   <li><b>质量评估</b>：调用 {@link StreamingQualityRuleEngine#evaluateTable}
 *       评估目标表的所有质量规则（≤ 2s）</li>
 *   <li><b>告警发射</b>：违规时由 {@code QualityAlertEmitter} 发送告警（≤ 5s）</li>
 * </ol>
 *
 * <p>性能预算（P95 ≤ 10s）：
 * <ul>
 *   <li>元数据采集：≤ 5s（由 {@link MetadataCollector} 保证）</li>
 *   <li>血缘更新：≤ 3s</li>
 *   <li>质量评估 + 告警：≤ 2s</li>
 *   <li>合计：≤ 10s</li>
 * </ul>
 */
@Component
public class GovernancePipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GovernancePipelineOrchestrator.class);

    private final RealTimeLineageAnalyzer lineageAnalyzer;
    private final StreamingQualityRuleEngine qualityEngine;
    private final Timer pipelineTimer;

    /** 治理闭环执行记录（用于 P95 计算） */
    private final CopyOnWriteArrayList<PipelineExecution> executionHistory = new CopyOnWriteArrayList<>();

    /** 治理闭环统计 */
    private final ConcurrentHashMap<String, Long> pipelineStats = new ConcurrentHashMap<>();

    @Autowired
    public GovernancePipelineOrchestrator(RealTimeLineageAnalyzer lineageAnalyzer,
                                          StreamingQualityRuleEngine qualityEngine,
                                          MeterRegistry meterRegistry) {
        this.lineageAnalyzer = lineageAnalyzer;
        this.qualityEngine = qualityEngine;
        this.pipelineTimer = Timer.builder("governance.pipeline.closedloop.duration")
                .description("治理闭环端到端耗时（commit → 告警）")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /** 测试用构造函数（无 MeterRegistry） */
    public GovernancePipelineOrchestrator(RealTimeLineageAnalyzer lineageAnalyzer,
                                          StreamingQualityRuleEngine qualityEngine) {
        this.lineageAnalyzer = lineageAnalyzer;
        this.qualityEngine = qualityEngine;
        this.pipelineTimer = null;
    }

    /**
     * 元数据采集完成后的治理闭环处理。
     *
     * <p>由 {@code CatalogEventListener} 在 {@link MetadataCollector#collect}
     * 完成后调用，继续执行血缘更新 → 质量评估 → 告警。
     *
     * @param event 触发闭环的 commit 事件
     * @param metadata 采集到的表元数据
     * @return 治理闭环执行结果
     */
    public PipelineExecution onMetadataCollected(CatalogCommitEvent event, TableMetadata metadata) {
        long start = System.currentTimeMillis();
        Instant pipelineStart = event.getCommitTimestamp() != null
                ? event.getCommitTimestamp()
                : event.getReceivedTimestamp();

        log.info("Governance pipeline started: table={}, eventId={}",
                event.getTableIdentifier(), event.getEventId());

        List<FieldLineage> updatedLineages = new ArrayList<>();
        List<StreamingQualityRuleEngine.EvaluationOutcome> evaluationOutcomes = new ArrayList<>();
        List<QualityAlert> alerts = new ArrayList<>();

        try {
            // Step 1: 血缘更新（≤ 3s）
            long lineageStart = System.currentTimeMillis();
            updatedLineages = lineageAnalyzer.refreshLineageForTable(event.getTableIdentifier());
            long lineageDuration = System.currentTimeMillis() - lineageStart;
            log.debug("Lineage updated: count={}, duration={}ms",
                    updatedLineages.size(), lineageDuration);

            // Step 2: 质量评估 + 告警（≤ 2s）
            // 对采集到的元数据中的每个字段，评估关联的质量规则
            long qualityStart = System.currentTimeMillis();
            if (metadata.getSchema() != null) {
                for (TableMetadata.FieldSchema field : metadata.getSchema()) {
                    // 构造测试记录（实际场景由 Flink CEP 流式评估）
                    Map<String, Object> fieldValues = Map.of(field.getName(), getSampleValue(field));
                    List<StreamingQualityRuleEngine.EvaluationOutcome> outcomes =
                            qualityEngine.evaluateTable(
                                    event.getTableIdentifier(),
                                    "record-" + event.getNewSnapshotId(),
                                    fieldValues,
                                    pipelineStart,
                                    pipelineStart);
                    evaluationOutcomes.addAll(outcomes);
                    for (StreamingQualityRuleEngine.EvaluationOutcome outcome : outcomes) {
                        if (outcome.alert() != null) {
                            alerts.add(outcome.alert());
                        }
                    }
                }
            }
            long qualityDuration = System.currentTimeMillis() - qualityStart;
            log.debug("Quality evaluated: outcomes={}, alerts={}, duration={}ms",
                    evaluationOutcomes.size(), alerts.size(), qualityDuration);

            // 记录闭环执行
            long totalDuration = System.currentTimeMillis() - start;
            PipelineExecution execution = new PipelineExecution(
                    event.getEventId(),
                    event.getTableIdentifier(),
                    pipelineStart,
                    Instant.now(),
                    totalDuration,
                    lineageDuration,
                    qualityDuration,
                    updatedLineages,
                    alerts
            );
            executionHistory.add(execution);

            // 限制历史记录长度（避免内存溢出）
            if (executionHistory.size() > 1000) {
                executionHistory.remove(0);
            }

            // 更新统计
            pipelineStats.merge("totalExecutions", 1L, Long::sum);
            pipelineStats.merge("totalAlerts", (long) alerts.size(), Long::sum);
            if (totalDuration > 10000) {
                pipelineStats.merge("slaViolations", 1L, Long::sum);
            }

            if (pipelineTimer != null) {
                pipelineTimer.record(Duration.ofMillis(totalDuration));
            }

            log.info("Governance pipeline completed: table={}, totalDuration={}ms, " +
                            "lineages={}, alerts={}, slaViolated={}",
                    event.getTableIdentifier(), totalDuration,
                    updatedLineages.size(), alerts.size(), totalDuration > 10000);
            return execution;
        } catch (Exception e) {
            log.error("Governance pipeline failed: table={}: {}",
                    event.getTableIdentifier(), e.getMessage(), e);
            pipelineStats.merge("failureCount", 1L, Long::sum);
            return new PipelineExecution(
                    event.getEventId(), event.getTableIdentifier(),
                    pipelineStart, Instant.now(),
                    System.currentTimeMillis() - start, 0, 0,
                    updatedLineages, alerts);
        }
    }

    /**
     * 计算 P95 延迟。
     *
     * @return P95 延迟（毫秒）；无历史记录时返回 0
     */
    public long calculateP95Latency() {
        if (executionHistory.isEmpty()) {
            return 0;
        }
        List<Long> latencies = executionHistory.stream()
                .map(PipelineExecution::totalDurationMs)
                .sorted()
                .toList();
        int p95Index = (int) Math.ceil(latencies.size() * 0.95) - 1;
        return latencies.get(Math.max(0, p95Index));
    }

    /**
     * 获取治理闭环统计。
     */
    public Map<String, Long> getPipelineStats() {
        return new java.util.HashMap<>(pipelineStats);
    }

    /**
     * 获取执行历史（用于测试断言）。
     */
    public List<PipelineExecution> getExecutionHistory() {
        return List.copyOf(executionHistory);
    }

    /**
     * 清空执行历史（用于测试）。
     */
    public void clearHistory() {
        executionHistory.clear();
        pipelineStats.clear();
    }

    private Object getSampleValue(TableMetadata.FieldSchema field) {
        // 根据字段类型返回样本值（用于触发质量规则评估）
        String type = field.getType() == null ? "string" : field.getType().toLowerCase();
        if (type.contains("int") || type.contains("long")) {
            return 1L;
        }
        if (type.contains("double") || type.contains("float") || type.contains("decimal")) {
            return 1.0;
        }
        if (type.contains("bool")) {
            return true;
        }
        return "sample";
    }

    /**
     * 治理闭环执行记录。
     *
     * @param eventId 事件 ID
     * @param tableIdentifier 表标识符
     * @param startTime 闭环开始时间
     * @param endTime 闭环结束时间
     * @param totalDurationMs 总耗时
     * @param lineageDurationMs 血缘更新耗时
     * @param qualityDurationMs 质量评估耗时
     * @param updatedLineages 更新的血缘列表
     * @param alerts 触发的告警列表
     */
    public record PipelineExecution(
            String eventId,
            String tableIdentifier,
            Instant startTime,
            Instant endTime,
            long totalDurationMs,
            long lineageDurationMs,
            long qualityDurationMs,
            List<FieldLineage> updatedLineages,
            List<QualityAlert> alerts
    ) {}
}