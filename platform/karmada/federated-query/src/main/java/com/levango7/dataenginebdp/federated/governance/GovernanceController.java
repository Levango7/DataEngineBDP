package com.levango7.dataenginebdp.federated.governance;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联邦治理 REST API 控制器。
 *
 * <p>端点：
 * <ul>
 *   <li>GET  /api/v1/federated/governance/metadata/tables - 跨集群元数据</li>
 *   <li>GET  /api/v1/federated/governance/metadata/tables/{tableId} - 表详情</li>
 *   <li>GET  /api/v1/federated/governance/metadata/conflicts - 元数据冲突</li>
 *   <li>GET  /api/v1/federated/governance/lineage/{tableId} - 跨集群血缘</li>
 *   <li>GET  /api/v1/federated/governance/lineage/{tableId}/upstream - 上游血缘</li>
 *   <li>GET  /api/v1/federated/governance/lineage/{tableId}/downstream - 下游血缘</li>
 *   <li>GET  /api/v1/federated/governance/quality/reports - 质量报告</li>
 *   <li>GET  /api/v1/federated/governance/quality/reports/{tableId} - 表质量报告</li>
 *   <li>POST /api/v1/federated/governance/quality/rules - 创建质量规则</li>
 *   <li>POST /api/v1/federated/governance/quality/rules/{ruleId}/apply - 跨集群应用规则</li>
 *   <li>GET  /api/v1/federated/governance/quality/score - 联邦质量评分</li>
 *   <li>GET  /api/v1/federated/governance/quality/alerts - 质量告警</li>
 *   <li>GET  /api/v1/federated/governance/quality/templates - 规则模板库</li>
 *   <li>GET  /api/v1/federated/governance/dashboard - 治理仪表盘</li>
 *   <li>POST /api/v1/federated/governance/sync - 触发元数据同步</li>
 * </ul>
 */
@Slf4j
@RestController
@Tag(name = "多集群联邦-联邦治理", description = "跨集群元数据/血缘/质量治理")
@RequestMapping("/api/v1/federated/governance")
public class GovernanceController {

    private final FederatedMetadataService metadataService;
    private final FederatedLineageService lineageService;
    private final FederatedQualityService qualityService;

    public GovernanceController(FederatedMetadataService metadataService,
                                FederatedLineageService lineageService,
                                FederatedQualityService qualityService) {
        this.metadataService = metadataService;
        this.lineageService = lineageService;
        this.qualityService = qualityService;
    }

    // ==================================================================
    // 元数据 API
    // ==================================================================

    /**
     * 跨集群元数据表列表。
     *
     * <p>GET /api/v1/federated/governance/metadata/tables
     */
    @Operation(summary = "跨集群元数据表列表")
    @GetMapping("/metadata/tables")
    public ResponseEntity<Map<String, Object>> getFederatedTables(
            @RequestParam(required = false) String cluster) {
        List<FederatedGovernanceView.TableMetadata> tables = metadataService.getFederatedTables(cluster);
        return ResponseEntity.ok(Map.of(
                "data", tables,
                "total", tables.size(),
                "timestamp", Instant.now().toString()));
    }

    /**
     * 表详情（合并多集群信息）。
     *
     * <p>GET /api/v1/federated/governance/metadata/tables/{tableId}
     */
    @Operation(summary = "表详情（合并多集群信息）")
    @GetMapping("/metadata/tables/{tableId}")
    public ResponseEntity<Map<String, Object>> getFederatedTable(@PathVariable String tableId) {
        FederatedGovernanceView.TableMetadata table = metadataService.getFederatedTable(tableId);
        if (table == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("data", table, "timestamp", Instant.now().toString()));
    }

    /**
     * 元数据冲突检测。
     *
     * <p>GET /api/v1/federated/governance/metadata/conflicts
     */
    @Operation(summary = "元数据冲突检测")
    @GetMapping("/metadata/conflicts")
    public ResponseEntity<Map<String, Object>> getMetadataConflicts() {
        List<FederatedGovernanceView.MetadataConflict> conflicts = metadataService.detectConflicts();
        return ResponseEntity.ok(Map.of(
                "data", conflicts,
                "total", conflicts.size(),
                "timestamp", Instant.now().toString()));
    }

    // ==================================================================
    // 血缘 API
    // ==================================================================

    /**
     * 跨集群血缘（完整图）。
     *
     * <p>GET /api/v1/federated/governance/lineage/{tableId}
     */
    @Operation(summary = "跨集群血缘（完整图）")
    @GetMapping("/lineage/{tableId}")
    public ResponseEntity<Map<String, Object>> getFederatedLineage(@PathVariable String tableId) {
        FederatedGovernanceView.LineageView view = lineageService.getFederatedLineage(tableId);
        return ResponseEntity.ok(Map.of("data", view, "timestamp", Instant.now().toString()));
    }

    /**
     * 上游血缘（数据来源链）。
     *
     * <p>GET /api/v1/federated/governance/lineage/{tableId}/upstream
     */
    @Operation(summary = "上游血缘（数据来源链）")
    @GetMapping("/lineage/{tableId}/upstream")
    public ResponseEntity<Map<String, Object>> getUpstreamLineage(@PathVariable String tableId) {
        FederatedGovernanceView.LineageGraph graph = lineageService.getUpstreamLineage(tableId);
        return ResponseEntity.ok(Map.of("data", graph, "timestamp", Instant.now().toString()));
    }

    /**
     * 下游血缘（数据消费链）。
     *
     * <p>GET /api/v1/federated/governance/lineage/{tableId}/downstream
     */
    @Operation(summary = "下游血缘（数据消费链）")
    @GetMapping("/lineage/{tableId}/downstream")
    public ResponseEntity<Map<String, Object>> getDownstreamLineage(@PathVariable String tableId) {
        FederatedGovernanceView.LineageGraph graph = lineageService.getDownstreamLineage(tableId);
        return ResponseEntity.ok(Map.of("data", graph, "timestamp", Instant.now().toString()));
    }

    // ==================================================================
    // 质量规则 API
    // ==================================================================

    /**
     * 质量报告列表。
     *
     * <p>GET /api/v1/federated/governance/quality/reports
     */
    @Operation(summary = "质量报告列表")
    @GetMapping("/quality/reports")
    public ResponseEntity<Map<String, Object>> getQualityReports(
            @RequestParam(required = false) String tableId) {
        List<FederatedGovernanceView.QualityReport> reports;
        if (tableId != null && !tableId.isEmpty()) {
            reports = qualityService.getQualityReport(tableId);
        } else {
            reports = qualityService.getAllQualityReports();
        }
        return ResponseEntity.ok(Map.of(
                "data", reports,
                "total", reports.size(),
                "timestamp", Instant.now().toString()));
    }

    /**
     * 创建质量规则。
     *
     * <p>POST /api/v1/federated/governance/quality/rules
     */
    @Operation(summary = "创建质量规则")
    @PostMapping("/quality/rules")
    public ResponseEntity<Map<String, Object>> createQualityRule(@Valid @RequestBody FederatedGovernanceView.QualityRule rule) {
        log.info("Create quality rule: name={} dimension={}", rule.getName(), rule.getDimension());
        FederatedGovernanceView.QualityRule created = qualityService.createQualityRule(rule);
        return ResponseEntity.ok(Map.of("data", created, "timestamp", Instant.now().toString()));
    }

    /**
     * 跨集群应用质量规则。
     *
     * <p>POST /api/v1/federated/governance/quality/rules/{ruleId}/apply
     */
    @Operation(summary = "跨集群应用质量规则")
    @PostMapping("/quality/rules/{ruleId}/apply")
    public ResponseEntity<Map<String, Object>> applyQualityRule(
            @PathVariable String ruleId, @RequestBody List<String> clusterIds) {
        log.info("Apply quality rule: ruleId={} clusters={}", ruleId, clusterIds);
        List<FederatedGovernanceView.QualityReport> reports = qualityService.applyQualityRule(ruleId, clusterIds);
        return ResponseEntity.ok(Map.of(
                "data", reports,
                "total", reports.size(),
                "timestamp", Instant.now().toString()));
    }

    /**
     * 联邦质量评分。
     *
     * <p>GET /api/v1/federated/governance/quality/score
     */
    @Operation(summary = "联邦质量评分")
    @GetMapping("/quality/score")
    public ResponseEntity<Map<String, Object>> getFederatedQualityScore() {
        FederatedGovernanceView.FederatedQualityScore score = qualityService.getFederatedQualityScore();
        return ResponseEntity.ok(Map.of("data", score, "timestamp", Instant.now().toString()));
    }

    /**
     * 质量告警列表。
     *
     * <p>GET /api/v1/federated/governance/quality/alerts
     */
    @Operation(summary = "质量告警列表")
    @GetMapping("/quality/alerts")
    public ResponseEntity<Map<String, Object>> getQualityAlerts() {
        List<FederatedGovernanceView.QualityAlert> alerts = qualityService.getQualityAlerts();
        return ResponseEntity.ok(Map.of(
                "data", alerts,
                "total", alerts.size(),
                "timestamp", Instant.now().toString()));
    }

    /**
     * 质量规则模板库。
     *
     * <p>GET /api/v1/federated/governance/quality/templates
     */
    @Operation(summary = "质量规则模板库")
    @GetMapping("/quality/templates")
    public ResponseEntity<Map<String, Object>> getRuleTemplates() {
        List<FederatedGovernanceView.QualityRule> templates = qualityService.getRuleTemplates();
        return ResponseEntity.ok(Map.of(
                "data", templates,
                "total", templates.size(),
                "timestamp", Instant.now().toString()));
    }

    // ==================================================================
    // 治理仪表盘 & 同步
    // ==================================================================

    /**
     * 治理仪表盘（聚合元数据/血缘/质量视图）。
     *
     * <p>GET /api/v1/federated/governance/dashboard
     */
    @Operation(summary = "治理仪表盘（聚合元数据/血缘/质量视图）")
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        FederatedGovernanceView.MetadataView metadata = metadataService.buildMetadataView();
        FederatedGovernanceView.QualityView quality = qualityService.buildQualityView();
        FederatedGovernanceView.FederatedQualityScore score = quality.getFederatedScore();

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("metadata", metadata);
        dashboard.put("quality", quality);
        dashboard.put("clusterCount", metadata.getClusters().size());
        dashboard.put("tableCount", metadata.getTotalTables());
        dashboard.put("overallQualityScore", score != null ? score.getOverallScore() : 0.0);
        dashboard.put("conflictCount", metadata.getConflicts().size());
        dashboard.put("alertCount", quality.getAlerts().size());
        dashboard.put("generatedAt", Instant.now());
        return ResponseEntity.ok(dashboard);
    }

    /**
     * 触发元数据同步。
     *
     * <p>POST /api/v1/federated/governance/sync
     */
    @Operation(summary = "触发元数据同步")
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncMetadata(@Valid @RequestBody FederatedGovernanceView.SyncRequest request) {
        log.info("Trigger metadata sync: cluster={} force={}", request.getClusterId(), request.isForce());
        FederatedGovernanceView.SyncResult result = metadataService.syncMetadata(request.getClusterId());
        return ResponseEntity.ok(Map.of("data", result, "timestamp", Instant.now().toString()));
    }
}