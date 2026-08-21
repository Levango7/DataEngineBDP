package com.levango7.dataenginebdp.federated.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FederatedQualityService} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>质量规则创建</li>
 *   <li>跨集群应用质量规则</li>
 *   <li>联邦质量评分聚合</li>
 *   <li>质量告警生成</li>
 *   <li>规则模板库</li>
 *   <li>边界条件（空报告/不存在的规则）</li>
 * </ul>
 */
class FederatedQualityServiceTest {

    private FederatedQualityService.ClusterQualityExecutor executor;
    private FederatedQualityService service;

    @BeforeEach
    void setUp() {
        executor = mock(FederatedQualityService.ClusterQualityExecutor.class);
        service = new FederatedQualityService(executor);
    }

    @Test
    void createQualityRule_shouldAssignIdAndStore() {
        FederatedGovernanceView.QualityRule rule = FederatedGovernanceView.QualityRule.builder()
                .name("not_null_check")
                .dimension(FederatedGovernanceView.QualityDimension.COMPLETENESS)
                .expression("id IS NOT NULL")
                .severity("ERROR")
                .enabled(true)
                .build();

        FederatedGovernanceView.QualityRule created = service.createQualityRule(rule);
        assertThat(created.getRuleId()).isNotNull();
        assertThat(created.isTemplate()).isFalse();
        assertThat(service.getAllQualityRules()).hasSize(1);
    }

    @Test
    void applyQualityRule_shouldExecuteAcrossClusters() {
        FederatedGovernanceView.QualityRule rule = buildRule("rule-1", "COMPLETENESS");
        service.createQualityRule(rule);

        FederatedGovernanceView.QualityReport reportA = buildReport("table-a", "cluster-a", 90.0, true);
        FederatedGovernanceView.QualityReport reportB = buildReport("table-b", "cluster-b", 80.0, true);
        when(executor.executeRule(any(), anyString()))
                .thenReturn(List.of(reportA))
                .thenReturn(List.of(reportB));

        List<FederatedGovernanceView.QualityReport> reports = service.applyQualityRule(
                "rule-1", List.of("cluster-a", "cluster-b"));

        assertThat(reports).hasSize(2);
        assertThat(reports).extracting(FederatedGovernanceView.QualityReport::getClusterId)
                .containsExactlyInAnyOrder("cluster-a", "cluster-b");
    }

    @Test
    void applyQualityRule_shouldReturnEmptyForNonExistentRule() {
        List<FederatedGovernanceView.QualityReport> reports = service.applyQualityRule(
                "non-existent", List.of("cluster-a"));
        assertThat(reports).isEmpty();
    }

    @Test
    void applyQualityRule_shouldGenerateAlertsForFailures() {
        FederatedGovernanceView.QualityRule rule = buildRule("rule-1", "COMPLETENESS");
        service.createQualityRule(rule);

        // 报告中规则执行失败
        Map<String, Boolean> ruleResults = new LinkedHashMap<>();
        ruleResults.put("rule-1", false);
        FederatedGovernanceView.QualityReport failedReport = FederatedGovernanceView.QualityReport.builder()
                .tableId("table-a")
                .tableName("orders")
                .clusterId("cluster-a")
                .ruleResults(ruleResults)
                .overallScore(50.0)
                .build();
        when(executor.executeRule(any(), anyString())).thenReturn(List.of(failedReport));

        service.applyQualityRule("rule-1", List.of("cluster-a"));

        List<FederatedGovernanceView.QualityAlert> alerts = service.getQualityAlerts();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getRuleId()).isEqualTo("rule-1");
        assertThat(alerts.get(0).isAcknowledged()).isFalse();
    }

    @Test
    void getFederatedQualityScore_shouldAggregateByClusterAndDimension() {
        FederatedGovernanceView.QualityRule rule = buildRule("rule-1", "COMPLETENESS");
        service.createQualityRule(rule);

        Map<String, Double> dimScoresA = new LinkedHashMap<>();
        dimScoresA.put("COMPLETENESS", 90.0);
        dimScoresA.put("UNIQUENESS", 95.0);
        FederatedGovernanceView.QualityReport reportA = FederatedGovernanceView.QualityReport.builder()
                .tableId("table-a")
                .tableName("orders")
                .clusterId("cluster-a")
                .ruleResults(Collections.emptyMap())
                .dimensionScores(dimScoresA)
                .overallScore(92.0)
                .build();
        Map<String, Double> dimScoresB = new LinkedHashMap<>();
        dimScoresB.put("COMPLETENESS", 80.0);
        dimScoresB.put("UNIQUENESS", 85.0);
        FederatedGovernanceView.QualityReport reportB = FederatedGovernanceView.QualityReport.builder()
                .tableId("table-b")
                .tableName("customers")
                .clusterId("cluster-b")
                .ruleResults(Collections.emptyMap())
                .dimensionScores(dimScoresB)
                .overallScore(82.0)
                .build();
        when(executor.executeRule(any(), anyString()))
                .thenReturn(List.of(reportA))
                .thenReturn(List.of(reportB));

        service.applyQualityRule("rule-1", List.of("cluster-a", "cluster-b"));

        FederatedGovernanceView.FederatedQualityScore score = service.getFederatedQualityScore();
        assertThat(score.getTableCount()).isEqualTo(2);
        assertThat(score.getClusterCount()).isEqualTo(2);
        assertThat(score.getOverallScore()).isBetween(80.0, 95.0);
        assertThat(score.getClusterScores()).containsKeys("cluster-a", "cluster-b");
        assertThat(score.getDimensionScores()).containsKeys("COMPLETENESS", "UNIQUENESS");
        // COMPLETENESS 平均 = (90 + 80) / 2 = 85
        assertThat(score.getDimensionScores().get("COMPLETENESS")).isEqualTo(85.0);
    }

    @Test
    void getFederatedQualityScore_shouldReturnZeroForNoReports() {
        FederatedGovernanceView.FederatedQualityScore score = service.getFederatedQualityScore();
        assertThat(score.getOverallScore()).isEqualTo(0.0);
        assertThat(score.getTableCount()).isEqualTo(0);
        assertThat(score.getClusterCount()).isEqualTo(0);
    }

    @Test
    void getRuleTemplates_shouldContainBuiltinTemplates() {
        List<FederatedGovernanceView.QualityRule> templates = service.getRuleTemplates();
        assertThat(templates).isNotEmpty();
        assertThat(templates).extracting(FederatedGovernanceView.QualityRule::getDimension)
                .contains(
                        FederatedGovernanceView.QualityDimension.COMPLETENESS,
                        FederatedGovernanceView.QualityDimension.UNIQUENESS,
                        FederatedGovernanceView.QualityDimension.ACCURACY,
                        FederatedGovernanceView.QualityDimension.CONSISTENCY,
                        FederatedGovernanceView.QualityDimension.TIMELINESS);
    }

    @Test
    void createRuleFromTemplate_shouldCreateNonTemplateRule() {
        FederatedGovernanceView.QualityRule rule = service.createRuleFromTemplate("not_null", "my_not_null");
        assertThat(rule).isNotNull();
        assertThat(rule.isTemplate()).isFalse();
        assertThat(rule.getName()).isEqualTo("my_not_null");
        assertThat(rule.getDimension()).isEqualTo(FederatedGovernanceView.QualityDimension.COMPLETENESS);
    }

    @Test
    void createRuleFromTemplate_shouldReturnNullForUnknownTemplate() {
        FederatedGovernanceView.QualityRule rule = service.createRuleFromTemplate("unknown", "test");
        assertThat(rule).isNull();
    }

    @Test
    void acknowledgeAlert_shouldMarkAsAcknowledged() {
        FederatedGovernanceView.QualityRule rule = buildRule("rule-1", "COMPLETENESS");
        service.createQualityRule(rule);

        Map<String, Boolean> ruleResults = new LinkedHashMap<>();
        ruleResults.put("rule-1", false);
        FederatedGovernanceView.QualityReport failedReport = FederatedGovernanceView.QualityReport.builder()
                .tableId("table-a")
                .tableName("orders")
                .clusterId("cluster-a")
                .ruleResults(ruleResults)
                .overallScore(50.0)
                .build();
        when(executor.executeRule(any(), anyString())).thenReturn(List.of(failedReport));
        service.applyQualityRule("rule-1", List.of("cluster-a"));

        List<FederatedGovernanceView.QualityAlert> alerts = service.getQualityAlerts();
        String alertId = alerts.get(0).getAlertId();
        service.acknowledgeAlert(alertId);

        assertThat(service.getQualityAlerts().get(0).isAcknowledged()).isTrue();
    }

    private FederatedGovernanceView.QualityRule buildRule(String ruleId, String dimension) {
        return FederatedGovernanceView.QualityRule.builder()
                .ruleId(ruleId)
                .name("test-rule")
                .dimension(dimension)
                .expression("1=1")
                .severity("ERROR")
                .enabled(true)
                .build();
    }

    private FederatedGovernanceView.QualityReport buildReport(String tableId, String clusterId,
                                                              double score, boolean pass) {
        Map<String, Boolean> ruleResults = new LinkedHashMap<>();
        ruleResults.put("rule-1", pass);
        Map<String, Double> dimScores = new LinkedHashMap<>();
        dimScores.put("COMPLETENESS", score);
        return FederatedGovernanceView.QualityReport.builder()
                .tableId(tableId)
                .tableName(tableId)
                .clusterId(clusterId)
                .ruleResults(ruleResults)
                .dimensionScores(dimScores)
                .overallScore(score)
                .build();
    }
}