package com.shuqing.bigdata.governance.realtime.controller;

import com.shuqing.bigdata.governance.realtime.catalog.MetadataCollector;
import com.shuqing.bigdata.governance.realtime.lineage.RealTimeLineageAnalyzer;
import com.shuqing.bigdata.governance.realtime.model.CatalogCommitEvent;
import com.shuqing.bigdata.governance.realtime.model.FieldLineage;
import com.shuqing.bigdata.governance.realtime.model.QualityAlert;
import com.shuqing.bigdata.governance.realtime.model.TableMetadata;
import com.shuqing.bigdata.governance.realtime.pipeline.GovernancePipelineOrchestrator;
import com.shuqing.bigdata.governance.realtime.quality.QualityRule;
import com.shuqing.bigdata.governance.realtime.quality.StreamingQualityRuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 实时治理管道 REST 控制器。
 *
 * <p>提供治理管道的管理与查询接口：
 * <ul>
 *   <li>手动触发元数据采集</li>
 *   <li>查询血缘图</li>
 *   <li>注册/注销质量规则</li>
 *   <li>查询告警</li>
 *   <li>查询治理闭环指标（P95 延迟等）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/governance")
public class GovernanceController {

    private static final Logger log = LoggerFactory.getLogger(GovernanceController.class);

    private final MetadataCollector metadataCollector;
    private final RealTimeLineageAnalyzer lineageAnalyzer;
    private final StreamingQualityRuleEngine qualityEngine;
    private final GovernancePipelineOrchestrator orchestrator;

    @Autowired
    public GovernanceController(MetadataCollector metadataCollector,
                                RealTimeLineageAnalyzer lineageAnalyzer,
                                StreamingQualityRuleEngine qualityEngine,
                                GovernancePipelineOrchestrator orchestrator) {
        this.metadataCollector = metadataCollector;
        this.lineageAnalyzer = lineageAnalyzer;
        this.qualityEngine = qualityEngine;
        this.orchestrator = orchestrator;
    }

    // -----------------------------------------------------------------------
    // 元数据采集
    // -----------------------------------------------------------------------

    /**
     * 手动触发元数据采集。
     *
     * @param event commit 事件
     * @return 采集到的元数据
     */
    @PostMapping("/metadata/collect")
    public ResponseEntity<TableMetadata> collectMetadata(@RequestBody CatalogCommitEvent event) {
        if (event.getReceivedTimestamp() == null) {
            event.setReceivedTimestamp(Instant.now());
        }
        TableMetadata metadata = metadataCollector.collect(event);
        if (metadata == null) {
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok(metadata);
    }

    /**
     * 查询缓存的表元数据。
     */
    @GetMapping("/metadata/{tableIdentifier}")
    public ResponseEntity<TableMetadata> getMetadata(@PathVariable String tableIdentifier) {
        TableMetadata metadata = metadataCollector.getCached(tableIdentifier);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(metadata);
    }

    // -----------------------------------------------------------------------
    // 血缘管理
    // -----------------------------------------------------------------------

    /**
     * 解析 Flink CDC SQL 并更新血缘图。
     *
     * @param request 包含 sqlText 和 jobId
     * @return 解析得到的字段级血缘
     */
    @PostMapping("/lineage/parse")
    public ResponseEntity<FieldLineage> parseLineage(@RequestBody ParseLineageRequest request) {
        FieldLineage lineage = lineageAnalyzer.parseAndUpdate(request.sqlText(), request.jobId());
        return ResponseEntity.ok(lineage);
    }

    /**
     * 查询指定目标表的血缘。
     */
    @GetMapping("/lineage/{targetTable}")
    public ResponseEntity<FieldLineage> queryLineage(@PathVariable String targetTable) {
        FieldLineage lineage = lineageAnalyzer.getGraphClient().queryLineage(targetTable);
        if (lineage == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(lineage);
    }

    /**
     * 查询所有血缘。
     */
    @GetMapping("/lineage")
    public ResponseEntity<Map<String, FieldLineage>> getAllLineage() {
        return ResponseEntity.ok(lineageAnalyzer.getGraphClient().getAllCachedLineage());
    }

    // -----------------------------------------------------------------------
    // 质量规则管理
    // -----------------------------------------------------------------------

    /**
     * 注册质量规则。
     */
    @PostMapping("/quality/rules")
    public ResponseEntity<String> registerRule(@RequestBody QualityRule rule) {
        qualityEngine.registerRule(rule);
        return ResponseEntity.ok("Rule registered: " + rule.getRuleId());
    }

    /**
     * 注销质量规则。
     */
    @DeleteMapping("/quality/rules/{ruleId}")
    public ResponseEntity<String> unregisterRule(@PathVariable String ruleId) {
        qualityEngine.unregisterRule(ruleId);
        return ResponseEntity.ok("Rule unregistered: " + ruleId);
    }

    /**
     * 查询所有质量规则。
     */
    @GetMapping("/quality/rules")
    public ResponseEntity<Map<String, QualityRule>> getAllRules() {
        return ResponseEntity.ok(qualityEngine.getRuleRegistry());
    }

    /**
     * 评估单条记录（同步模式）。
     */
    @PostMapping("/quality/evaluate")
    public ResponseEntity<StreamingQualityRuleEngine.EvaluationOutcome> evaluate(
            @RequestBody EvaluateRequest request) {
        StreamingQualityRuleEngine.EvaluationOutcome outcome = qualityEngine.evaluateAndAlert(
                request.ruleId(), request.recordId(), request.fieldValue(),
                request.violationTimestamp() != null ? request.violationTimestamp() : Instant.now(),
                request.pipelineStartTimestamp() != null ? request.pipelineStartTimestamp() : Instant.now()
        );
        return ResponseEntity.ok(outcome);
    }

    // -----------------------------------------------------------------------
    // 告警查询
    // -----------------------------------------------------------------------

    /**
     * 查询所有告警。
     */
    @GetMapping("/alerts")
    public ResponseEntity<List<QualityAlert>> getAllAlerts() {
        return ResponseEntity.ok(qualityEngine.getAlertEmitter().getAlertBuffer());
    }

    /**
     * 查询指定表的告警。
     */
    @GetMapping("/alerts/{tableIdentifier}")
    public ResponseEntity<List<QualityAlert>> getAlertsByTable(
            @PathVariable String tableIdentifier,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(qualityEngine.getAlertEmitter().getRecentAlerts(tableIdentifier, limit));
    }

    // -----------------------------------------------------------------------
    // 治理闭环指标
    // -----------------------------------------------------------------------

    /**
     * 查询治理闭环指标（P95 延迟、执行统计等）。
     */
    @GetMapping("/pipeline/metrics")
    public ResponseEntity<Map<String, Object>> getPipelineMetrics() {
        Map<String, Object> metrics = new java.util.HashMap<>();
        metrics.put("p95LatencyMs", orchestrator.calculateP95Latency());
        metrics.put("pipelineStats", orchestrator.getPipelineStats());
        metrics.put("executionHistorySize", orchestrator.getExecutionHistory().size());
        metrics.put("slaTargetMs", 10000);
        metrics.put("slaSatisfied", orchestrator.calculateP95Latency() <= 10000);
        return ResponseEntity.ok(metrics);
    }

    /**
     * 查询治理闭环执行历史。
     */
    @GetMapping("/pipeline/history")
    public ResponseEntity<List<GovernancePipelineOrchestrator.PipelineExecution>> getHistory() {
        return ResponseEntity.ok(orchestrator.getExecutionHistory());
    }

    // -----------------------------------------------------------------------
    // 请求/响应 DTO
    // -----------------------------------------------------------------------

    public record ParseLineageRequest(String sqlText, String jobId) {}

    public record EvaluateRequest(
            String ruleId,
            String recordId,
            Object fieldValue,
            Instant violationTimestamp,
            Instant pipelineStartTimestamp
    ) {}
}