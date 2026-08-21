package com.levango7.dataenginebdp.federated.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link GovernanceController} REST API 单元测试（MockMvc）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>元数据 API（表列表/表详情/冲突）</li>
 *   <li>血缘 API（完整/上游/下游）</li>
 *   <li>质量 API（报告/规则/评分/告警/模板）</li>
 *   <li>仪表盘 API</li>
 *   <li>同步 API</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GovernanceControllerTest {

    private MockMvc mockMvc;

    @Mock
    private FederatedMetadataService metadataService;

    @Mock
    private FederatedLineageService lineageService;

    @Mock
    private FederatedQualityService qualityService;

    @InjectMocks
    private GovernanceController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getFederatedTables_shouldReturnTableList() throws Exception {
        FederatedGovernanceView.TableMetadata table = FederatedGovernanceView.TableMetadata.builder()
                .tableId("cluster-a:db.orders")
                .database("db")
                .table("orders")
                .fullName("db.orders")
                .clusterId("cluster-a")
                .build();
        when(metadataService.getFederatedTables(anyString())).thenReturn(List.of(table));

        mockMvc.perform(get("/api/v1/federated/governance/metadata/tables").param("cluster", "cluster-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.data[0].table").value("orders"));
    }

    @Test
    void getFederatedTable_shouldReturnTableDetail() throws Exception {
        FederatedGovernanceView.TableMetadata table = FederatedGovernanceView.TableMetadata.builder()
                .tableId("cluster-a:db.orders")
                .database("db")
                .table("orders")
                .fullName("db.orders")
                .clusterId("cluster-a")
                .build();
        when(metadataService.getFederatedTable("cluster-a:db.orders")).thenReturn(table);

        mockMvc.perform(get("/api/v1/federated/governance/metadata/tables/cluster-a:db.orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.table").value("orders"));
    }

    @Test
    void getFederatedTable_shouldReturn404ForNonExistent() throws Exception {
        when(metadataService.getFederatedTable("non-existent")).thenReturn(null);

        mockMvc.perform(get("/api/v1/federated/governance/metadata/tables/non-existent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMetadataConflicts_shouldReturnConflicts() throws Exception {
        FederatedGovernanceView.MetadataConflict conflict = FederatedGovernanceView.MetadataConflict.builder()
                .fullName("db.orders")
                .conflictType("TYPE_MISMATCH")
                .description("schema mismatch")
                .detectedAt(Instant.now())
                .build();
        when(metadataService.detectConflicts()).thenReturn(List.of(conflict));

        mockMvc.perform(get("/api/v1/federated/governance/metadata/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.data[0].conflictType").value("TYPE_MISMATCH"));
    }

    @Test
    void getFederatedLineage_shouldReturnLineageView() throws Exception {
        FederatedGovernanceView.LineageView view = FederatedGovernanceView.LineageView.builder()
                .graph(FederatedGovernanceView.LineageGraph.builder()
                        .nodes(List.of(FederatedGovernanceView.LineageNode.builder()
                                .nodeId("table-1")
                                .name("orders")
                                .build()))
                        .edges(Collections.emptyList())
                        .build())
                .generatedAt(Instant.now())
                .build();
        when(lineageService.getFederatedLineage("table-1")).thenReturn(view);

        mockMvc.perform(get("/api/v1/federated/governance/lineage/table-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.graph.nodes[0].nodeId").value("table-1"));
    }

    @Test
    void getUpstreamLineage_shouldReturnUpstreamGraph() throws Exception {
        FederatedGovernanceView.LineageGraph graph = FederatedGovernanceView.LineageGraph.builder()
                .nodes(List.of(FederatedGovernanceView.LineageNode.builder()
                        .nodeId("source")
                        .name("raw")
                        .build()))
                .edges(Collections.emptyList())
                .build();
        when(lineageService.getUpstreamLineage("table-1")).thenReturn(graph);

        mockMvc.perform(get("/api/v1/federated/governance/lineage/table-1/upstream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].nodeId").value("source"));
    }

    @Test
    void getDownstreamLineage_shouldReturnDownstreamGraph() throws Exception {
        FederatedGovernanceView.LineageGraph graph = FederatedGovernanceView.LineageGraph.builder()
                .nodes(Collections.emptyList())
                .edges(Collections.emptyList())
                .build();
        when(lineageService.getDownstreamLineage("table-1")).thenReturn(graph);

        mockMvc.perform(get("/api/v1/federated/governance/lineage/table-1/downstream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes").isEmpty());
    }

    @Test
    void createQualityRule_shouldCreateAndReturn() throws Exception {
        FederatedGovernanceView.QualityRule rule = FederatedGovernanceView.QualityRule.builder()
                .ruleId("rule-1")
                .name("not_null")
                .dimension("COMPLETENESS")
                .expression("id IS NOT NULL")
                .severity("ERROR")
                .enabled(true)
                .build();
        when(qualityService.createQualityRule(any())).thenReturn(rule);

        mockMvc.perform(post("/api/v1/federated/governance/quality/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"not_null\",\"dimension\":\"COMPLETENESS\","
                                + "\"expression\":\"id IS NOT NULL\",\"severity\":\"ERROR\","
                                + "\"enabled\":true,\"template\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ruleId").value("rule-1"));
    }

    @Test
    void applyQualityRule_shouldReturnReports() throws Exception {
        FederatedGovernanceView.QualityReport report = FederatedGovernanceView.QualityReport.builder()
                .reportId("report-1")
                .tableId("table-1")
                .tableName("orders")
                .clusterId("cluster-a")
                .overallScore(95.0)
                .build();
        when(qualityService.applyQualityRule(anyString(), any())).thenReturn(List.of(report));

        mockMvc.perform(post("/api/v1/federated/governance/quality/rules/rule-1/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"cluster-a\",\"cluster-b\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.data[0].reportId").value("report-1"));
    }

    @Test
    void getFederatedQualityScore_shouldReturnScore() throws Exception {
        Map<String, Double> clusterScores = new LinkedHashMap<>();
        clusterScores.put("cluster-a", 90.0);
        FederatedGovernanceView.FederatedQualityScore score = FederatedGovernanceView.FederatedQualityScore.builder()
                .overallScore(90.0)
                .clusterScores(clusterScores)
                .tableCount(5)
                .clusterCount(1)
                .generatedAt(Instant.now())
                .build();
        when(qualityService.getFederatedQualityScore()).thenReturn(score);

        mockMvc.perform(get("/api/v1/federated/governance/quality/score"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallScore").value(90.0))
                .andExpect(jsonPath("$.data.tableCount").value(5));
    }

    @Test
    void getQualityAlerts_shouldReturnAlerts() throws Exception {
        FederatedGovernanceView.QualityAlert alert = FederatedGovernanceView.QualityAlert.builder()
                .alertId("alert-1")
                .ruleId("rule-1")
                .severity("ERROR")
                .message("quality check failed")
                .alertedAt(Instant.now())
                .acknowledged(false)
                .build();
        when(qualityService.getQualityAlerts()).thenReturn(List.of(alert));

        mockMvc.perform(get("/api/v1/federated/governance/quality/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.data[0].alertId").value("alert-1"));
    }

    @Test
    void getRuleTemplates_shouldReturnTemplates() throws Exception {
        FederatedGovernanceView.QualityRule template = FederatedGovernanceView.QualityRule.builder()
                .ruleId("template:not_null")
                .name("非空检查")
                .dimension("COMPLETENESS")
                .template(true)
                .build();
        when(qualityService.getRuleTemplates()).thenReturn(List.of(template));

        mockMvc.perform(get("/api/v1/federated/governance/quality/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.data[0].template").value(true));
    }

    @Test
    void getDashboard_shouldReturnAggregatedView() throws Exception {
        FederatedGovernanceView.MetadataView metadataView = FederatedGovernanceView.MetadataView.builder()
                .totalTables(10)
                .clusters(List.of("cluster-a", "cluster-b"))
                .conflicts(Collections.emptyList())
                .generatedAt(Instant.now())
                .build();
        FederatedGovernanceView.QualityView qualityView = FederatedGovernanceView.QualityView.builder()
                .alerts(Collections.emptyList())
                .federatedScore(FederatedGovernanceView.FederatedQualityScore.builder()
                        .overallScore(85.0)
                        .build())
                .generatedAt(Instant.now())
                .build();
        when(metadataService.buildMetadataView()).thenReturn(metadataView);
        when(qualityService.buildQualityView()).thenReturn(qualityView);

        mockMvc.perform(get("/api/v1/federated/governance/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(10))
                .andExpect(jsonPath("$.clusterCount").value(2))
                .andExpect(jsonPath("$.overallQualityScore").value(85.0));
    }

    @Test
    void syncMetadata_shouldTriggerSync() throws Exception {
        FederatedGovernanceView.SyncResult result = FederatedGovernanceView.SyncResult.builder()
                .clusterId("cluster-a")
                .success(true)
                .syncedTables(5)
                .elapsedMs(100)
                .syncedAt(Instant.now())
                .build();
        when(metadataService.syncMetadata(anyString())).thenReturn(result);

        mockMvc.perform(post("/api/v1/federated/governance/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterId\":\"cluster-a\",\"force\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.syncedTables").value(5));
    }
}