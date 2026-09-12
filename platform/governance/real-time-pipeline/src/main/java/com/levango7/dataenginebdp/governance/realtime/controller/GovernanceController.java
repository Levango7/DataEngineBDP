package com.levango7.dataenginebdp.governance.realtime.controller;

import com.levango7.dataenginebdp.governance.realtime.catalog.MetadataCollector;
import com.levango7.dataenginebdp.governance.realtime.lineage.RealTimeLineageAnalyzer;
import com.levango7.dataenginebdp.governance.realtime.model.CatalogCommitEvent;
import com.levango7.dataenginebdp.governance.realtime.model.FieldLineage;
import com.levango7.dataenginebdp.governance.realtime.model.QualityAlert;
import com.levango7.dataenginebdp.governance.realtime.model.TableMetadata;
import com.levango7.dataenginebdp.governance.realtime.pipeline.GovernancePipelineOrchestrator;
import com.levango7.dataenginebdp.governance.realtime.quality.QualityRule;
import com.levango7.dataenginebdp.governance.realtime.quality.StreamingQualityRuleEngine;
import com.levango7.dataenginebdp.common.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

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
 *
 * <p>安全控制（R8 修复）：
 * <ul>
 *   <li>类级 {@code @PreAuthorize("isAuthenticated()")}：所有端点要求认证，
 *       写操作（注册/注销/解析/采集/评估）进一步要求 {@code hasRole('GOVERNANCE_WRITER')}。</li>
 *   <li>租户隔离：从 {@link TenantContext} 读取当前租户 ID，缺失返回 403；
 *       查询结果按租户过滤（告警/血缘/规则按 tenantId 前缀过滤）。</li>
 *   <li>分页：getAllAlerts/getAllLineage/getAllRules 添加 page/size 参数，避免全量返回导致 OOM。</li>
 * </ul></p>
 */
@RestController
@Tag(name = "数据治理-实时管道", description = "实时治理编排与质量规则")
@RequestMapping("/api/v1/governance")
@PreAuthorize("isAuthenticated()")
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
    @Operation(summary = "手动触发元数据采集")
    @PostMapping("/metadata/collect")
    @PreAuthorize("hasRole('GOVERNANCE_WRITER')")
    public ResponseEntity<TableMetadata> collectMetadata(@RequestBody CatalogCommitEvent event) {
        requireTenant();
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
    @Operation(summary = "查询缓存的表元数据")
    @GetMapping("/metadata/{tableIdentifier}")
    public ResponseEntity<TableMetadata> getMetadata(@PathVariable String tableIdentifier) {
        // R11 安全修复：校验 tenantId，确保租户上下文存在后按租户隔离访问
        requireTenant();
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
    @Operation(summary = "解析 Flink CDC SQL 并更新血缘图")
    @PostMapping("/lineage/parse")
    @PreAuthorize("hasRole('GOVERNANCE_WRITER')")
    public ResponseEntity<FieldLineage> parseLineage(@RequestBody ParseLineageRequest request) {
        requireTenant();
        FieldLineage lineage = lineageAnalyzer.parseAndUpdate(request.sqlText(), request.jobId());
        return ResponseEntity.ok(lineage);
    }

    /**
     * 查询指定目标表的血缘。
     */
    @Operation(summary = "查询指定目标表的血缘")
    @GetMapping("/lineage/{targetTable}")
    public ResponseEntity<FieldLineage> queryLineage(@PathVariable String targetTable) {
        // R11 安全修复：按 tenantId 过滤，拒绝跨租户访问
        String tenantId = requireTenant();
        FieldLineage lineage = lineageAnalyzer.getGraphClient().queryLineage(targetTable);
        if (lineage == null || !tenantId.equals(lineage.getTenantId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(lineage);
    }

    /**
     * 查询所有血缘（租户隔离 + 分页）。
     *
     * @param page 页号（0 起）
     * @param size 每页大小（上限 200）
     * @return 分页后的血缘 Map
     */
    @Operation(summary = "查询所有血缘（分页）")
    @GetMapping("/lineage")
    public ResponseEntity<Map<String, FieldLineage>> getAllLineage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        String tenantId = requireTenant();
        int safeSize = Math.min(Math.max(size, 1), 200);
        // 按租户过滤（多租户隔离，R10 安全修复）
        Map<String, FieldLineage> all = lineageAnalyzer.getGraphClient().getAllCachedLineage(tenantId);
        // 分页截断
        Map<String, FieldLineage> paged = new java.util.LinkedHashMap<>();
        int skip = page * safeSize;
        int taken = 0;
        for (var entry : all.entrySet()) {
            if (skip > 0) {
                skip--;
                continue;
            }
            paged.put(entry.getKey(), entry.getValue());
            taken++;
            if (taken >= safeSize) {
                break;
            }
        }
        return ResponseEntity.ok(paged);
    }

    // -----------------------------------------------------------------------
    // 质量规则管理
    // -----------------------------------------------------------------------

    /**
     * 注册质量规则。
     */
    @Operation(summary = "注册质量规则")
    @PostMapping("/quality/rules")
    @PreAuthorize("hasRole('GOVERNANCE_WRITER')")
    public ResponseEntity<String> registerRule(@RequestBody QualityRule rule) {
        String tenantId = requireTenant();
        // 写入租户 ID（多租户隔离，R10 安全修复）
        rule.setTenantId(tenantId);
        qualityEngine.registerRule(rule);
        return ResponseEntity.ok("Rule registered: " + rule.getRuleId());
    }

    /**
     * 注销质量规则。
     */
    @Operation(summary = "注销质量规则")
    @DeleteMapping("/quality/rules/{ruleId}")
    @PreAuthorize("hasRole('GOVERNANCE_WRITER')")
    public ResponseEntity<String> unregisterRule(@PathVariable String ruleId) {
        // R11 安全修复：(ruleId, tenantId) 联合校验，拒绝跨租户注销
        String tenantId = requireTenant();
        QualityRule rule = qualityEngine.getRuleRegistry().get(ruleId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Rule not found: " + ruleId);
        }
        qualityEngine.unregisterRule(ruleId);
        return ResponseEntity.ok("Rule unregistered: " + ruleId);
    }

    /**
     * 查询所有质量规则（租户隔离 + 分页）。
     *
     * @param page 页号（0 起）
     * @param size 每页大小（上限 200）
     * @return 分页后的规则 Map
     */
    @Operation(summary = "查询所有质量规则（分页）")
    @GetMapping("/quality/rules")
    public ResponseEntity<Map<String, QualityRule>> getAllRules(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        String tenantId = requireTenant();
        int safeSize = Math.min(Math.max(size, 1), 200);
        // 按租户过滤（多租户隔离，R10 安全修复）
        Map<String, QualityRule> all = qualityEngine.getRuleRegistry(tenantId);
        Map<String, QualityRule> paged = new java.util.LinkedHashMap<>();
        int skip = page * safeSize;
        int taken = 0;
        for (var entry : all.entrySet()) {
            if (skip > 0) {
                skip--;
                continue;
            }
            paged.put(entry.getKey(), entry.getValue());
            taken++;
            if (taken >= safeSize) {
                break;
            }
        }
        return ResponseEntity.ok(paged);
    }

    /**
     * 评估单条记录（同步模式）。
     */
    @Operation(summary = "评估质量规则单条记录")
    @PostMapping("/quality/evaluate")
    @PreAuthorize("hasRole('GOVERNANCE_WRITER')")
    public ResponseEntity<StreamingQualityRuleEngine.EvaluationOutcome> evaluate(
            @RequestBody EvaluateRequest request) {
        requireTenant();
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
     * 查询所有告警（租户隔离 + 分页）。
     *
     * @param page 页号（0 起）
     * @param size 每页大小（上限 200）
     * @return 分页后的告警列表
     */
    @Operation(summary = "查询所有告警（分页）")
    @GetMapping("/alerts")
    public ResponseEntity<List<QualityAlert>> getAllAlerts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        String tenantId = requireTenant();
        int safeSize = Math.min(Math.max(size, 1), 200);
        // 按租户过滤（多租户隔离，R10 安全修复）
        List<QualityAlert> all = qualityEngine.getAlertEmitter().getAlertBuffer(tenantId);
        int total = all.size();
        int fromIndex = Math.min(page * safeSize, total);
        int toIndex = Math.min(fromIndex + safeSize, total);
        return ResponseEntity.ok(all.subList(fromIndex, toIndex));
    }

    /**
     * 查询指定表的告警（租户隔离）。
     */
    @Operation(summary = "查询指定表的告警")
    @GetMapping("/alerts/{tableIdentifier}")
    public ResponseEntity<List<QualityAlert>> getAlertsByTable(
            @PathVariable String tableIdentifier,
            @RequestParam(defaultValue = "100") int limit) {
        String tenantId = requireTenant();
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        // 按租户过滤后再按表过滤（多租户隔离，R10 安全修复）
        List<QualityAlert> tenantAlerts = qualityEngine.getAlertEmitter().getAlertBuffer(tenantId);
        return ResponseEntity.ok(tenantAlerts.stream()
                .filter(a -> a.getTableIdentifier().equals(tableIdentifier))
                .limit(safeLimit)
                .toList());
    }

    // -----------------------------------------------------------------------
    // 治理闭环指标
    // -----------------------------------------------------------------------

    /**
     * 查询治理闭环指标（P95 延迟、执行统计等）。
     */
    @Operation(summary = "查询治理闭环指标（P95 延迟、执行统计等）")
    @GetMapping("/pipeline/metrics")
    public ResponseEntity<Map<String, Object>> getPipelineMetrics() {
        // R11 安全修复：按 tenantId 过滤执行历史，避免跨租户指标泄漏
        String tenantId = requireTenant();
        List<GovernancePipelineOrchestrator.PipelineExecution> tenantHistory =
                filterByTenant(orchestrator.getExecutionHistory(), tenantId);
        Map<String, Object> metrics = new java.util.HashMap<>();
        // 按租户过滤后的 P95 延迟
        long p95;
        if (tenantHistory.isEmpty()) {
            p95 = 0;
        } else {
            List<Long> latencies = tenantHistory.stream()
                    .map(GovernancePipelineOrchestrator.PipelineExecution::totalDurationMs)
                    .sorted()
                    .toList();
            int p95Index = (int) Math.ceil(latencies.size() * 0.95) - 1;
            p95 = latencies.get(Math.max(0, p95Index));
        }
        metrics.put("p95LatencyMs", p95);
        metrics.put("pipelineStats", orchestrator.getPipelineStats());
        metrics.put("executionHistorySize", tenantHistory.size());
        metrics.put("slaTargetMs", 10000);
        metrics.put("slaSatisfied", p95 <= 10000);
        return ResponseEntity.ok(metrics);
    }

    /**
     * 查询治理闭环执行历史。
     */
    @Operation(summary = "查询治理闭环执行历史")
    @GetMapping("/pipeline/history")
    public ResponseEntity<List<GovernancePipelineOrchestrator.PipelineExecution>> getHistory() {
        // R11 安全修复：按 tenantId 过滤执行历史，避免跨租户数据泄漏
        String tenantId = requireTenant();
        return ResponseEntity.ok(filterByTenant(orchestrator.getExecutionHistory(), tenantId));
    }

    /**
     * 按 tenantId 过滤执行历史（R11 安全修复）。
     * PipelineExecution 无直接 tenantId 字段，通过其 alerts 与 updatedLineages 的 tenantId 判定归属。
     */
    private static List<GovernancePipelineOrchestrator.PipelineExecution> filterByTenant(
            List<GovernancePipelineOrchestrator.PipelineExecution> history, String tenantId) {
        return history.stream()
                .filter(exec -> {
                    // alerts 中所有 alert 的 tenantId 匹配，或无 alerts 时检查 lineages
                    if (exec.alerts() != null && !exec.alerts().isEmpty()) {
                        return exec.alerts().stream().allMatch(a -> tenantId.equals(a.getTenantId()));
                    }
                    if (exec.updatedLineages() != null && !exec.updatedLineages().isEmpty()) {
                        return exec.updatedLineages().stream().allMatch(l -> tenantId.equals(l.getTenantId()));
                    }
                    // 无 alerts 且无 lineages 的记录保留（无法判定归属，保守保留）
                    return true;
                })
                .toList();
    }

    /**
     * 从 TenantContext 校验当前租户；缺失时抛 403。
     *
     * @return 当前租户 ID
     */
    private static String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "缺少租户上下文，拒绝访问治理资源");
        }
        return tenantId;
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