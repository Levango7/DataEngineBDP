package com.levango7.dataenginebdp.federated.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨集群质量规则统一治理服务。
 *
 * <p>职责：
 * <ul>
 *   <li>统一质量规则定义（完整性/一致性/准确性/及时性/唯一性）</li>
 *   <li>跨集群应用质量规则（{@link #applyQualityRule(String, List)}）</li>
 *   <li>质量报告（{@link #getQualityReport(String)}）</li>
 *   <li>联邦质量评分（{@link #getFederatedQualityScore()}）</li>
 *   <li>质量规则模板库</li>
 *   <li>质量告警聚合</li>
 * </ul>
 *
 * <p>存储：内存 {@link ConcurrentHashMap}，生产环境可替换为持久化实现。
 * 集群间质量检查通过 {@link ClusterQualityExecutor} 接口抽象，便于 Mock。
 *
 * <p>验收标准：
 * <ul>
 *   <li>质量规则可跨集群批量应用</li>
 *   <li>联邦质量评分按维度和集群聚合</li>
 *   <li>质量告警能聚合多集群结果</li>
 * </ul>
 */
@Slf4j
@Service
public class FederatedQualityService {

    /** ruleId → 质量规则。 */
    private final ConcurrentHashMap<String, FederatedGovernanceView.QualityRule> ruleStore = new ConcurrentHashMap<>();

    /** reportId → 质量报告。 */
    private final ConcurrentHashMap<String, FederatedGovernanceView.QualityReport> reportStore = new ConcurrentHashMap<>();

    /** tableId → 报告 ID 列表。 */
    private final ConcurrentHashMap<String, List<String>> tableReportsIndex = new ConcurrentHashMap<>();

    /** alertId → 质量告警。 */
    private final ConcurrentHashMap<String, FederatedGovernanceView.QualityAlert> alertStore = new ConcurrentHashMap<>();

    /** 质量规则模板库：templateName → 规则。 */
    private final ConcurrentHashMap<String, FederatedGovernanceView.QualityRule> templateLibrary = new ConcurrentHashMap<>();

    private final ClusterQualityExecutor qualityExecutor;

    public FederatedQualityService(ClusterQualityExecutor qualityExecutor) {
        this.qualityExecutor = qualityExecutor;
        initTemplateLibrary();
    }

    /**
     * 初始化质量规则模板库。
     */
    private void initTemplateLibrary() {
        registerTemplate("not_null", "非空检查", FederatedGovernanceView.QualityDimension.COMPLETENESS,
                "column IS NOT NULL", "检查列是否存在空值", "ERROR");
        registerTemplate("unique_check", "唯一性检查", FederatedGovernanceView.QualityDimension.UNIQUENESS,
                "COUNT(DISTINCT column) = COUNT(column)", "检查列值是否唯一", "ERROR");
        registerTemplate("range_check", "范围检查", FederatedGovernanceView.QualityDimension.ACCURACY,
                "column BETWEEN min AND max", "检查列值是否在指定范围", "WARN");
        registerTemplate("referential_integrity", "引用完整性检查", FederatedGovernanceView.QualityDimension.CONSISTENCY,
                "FOREIGN KEY references valid", "检查外键引用是否存在", "ERROR");
        registerTemplate("freshness_check", "及时性检查", FederatedGovernanceView.QualityDimension.TIMELINESS,
                "max(updated_at) >= NOW() - interval", "检查数据是否及时更新", "WARN");
    }

    private void registerTemplate(String name, String displayName, String dimension,
                                  String expression, String description, String severity) {
        FederatedGovernanceView.QualityRule rule = FederatedGovernanceView.QualityRule.builder()
                .ruleId("template:" + name)
                .name(displayName)
                .dimension(dimension)
                .expression(expression)
                .description(description)
                .severity(severity)
                .enabled(true)
                .template(true)
                .createdAt(Instant.now())
                .build();
        templateLibrary.put(name, rule);
    }

    /**
     * 创建质量规则。
     *
     * @param rule 质量规则（ruleId 为空则自动生成）
     * @return 保存后的规则
     */
    public FederatedGovernanceView.QualityRule createQualityRule(FederatedGovernanceView.QualityRule rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        if (rule.getRuleId() == null || rule.getRuleId().isEmpty()) {
            rule.setRuleId("rule:" + UUID.randomUUID());
        }
        if (rule.getCreatedAt() == null) {
            rule.setCreatedAt(Instant.now());
        }
        rule.setTemplate(false);
        ruleStore.put(rule.getRuleId(), rule);
        log.info("Quality rule created: id={} name={} dimension={}", rule.getRuleId(), rule.getName(), rule.getDimension());
        return rule;
    }

    /**
     * 跨集群应用质量规则。
     *
     * @param ruleId 规则 ID
     * @param clusterIds 集群 ID 列表
     * @return 生成的报告列表
     */
    public List<FederatedGovernanceView.QualityReport> applyQualityRule(String ruleId, List<String> clusterIds) {
        FederatedGovernanceView.QualityRule rule = ruleStore.get(ruleId);
        if (rule == null) {
            log.warn("Quality rule not found: {}", ruleId);
            return Collections.emptyList();
        }
        if (!rule.isEnabled()) {
            log.warn("Quality rule is disabled: {}", ruleId);
            return Collections.emptyList();
        }
        if (clusterIds == null) {
            clusterIds = Collections.emptyList();
        }

        // 更新规则应用集群
        for (String cid : clusterIds) {
            if (!rule.getAppliedClusters().contains(cid)) {
                rule.getAppliedClusters().add(cid);
            }
        }

        List<FederatedGovernanceView.QualityReport> reports = new ArrayList<>();
        for (String clusterId : clusterIds) {
            List<FederatedGovernanceView.QualityReport> clusterReports = qualityExecutor.executeRule(rule, clusterId);
            if (clusterReports == null) {
                continue;
            }
            for (FederatedGovernanceView.QualityReport report : clusterReports) {
                if (report.getReportId() == null) {
                    report.setReportId("report:" + UUID.randomUUID());
                }
                if (report.getGeneratedAt() == null) {
                    report.setGeneratedAt(Instant.now());
                }
                reportStore.put(report.getReportId(), report);
                tableReportsIndex
                        .computeIfAbsent(report.getTableId(), k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(report.getReportId());
                reports.add(report);

                // 失败的规则产生告警
                generateAlertsFromReport(rule, report);
            }
        }
        log.info("Quality rule applied: rule={} clusters={} reports={}", ruleId, clusterIds.size(), reports.size());
        return reports;
    }

    /**
     * 获取表的质量报告。
     *
     * @param tableId 表 ID
     * @return 质量报告列表
     */
    public List<FederatedGovernanceView.QualityReport> getQualityReport(String tableId) {
        List<String> reportIds = tableReportsIndex.getOrDefault(tableId, Collections.emptyList());
        List<FederatedGovernanceView.QualityReport> result = new ArrayList<>();
        for (String id : reportIds) {
            FederatedGovernanceView.QualityReport r = reportStore.get(id);
            if (r != null) {
                result.add(r);
            }
        }
        return result;
    }

    /**
     * 获取所有质量报告。
     */
    public List<FederatedGovernanceView.QualityReport> getAllQualityReports() {
        return new ArrayList<>(reportStore.values());
    }

    /**
     * 获取联邦质量评分。
     *
     * <p>按集群和维度聚合所有报告的评分。
     *
     * @return 联邦质量评分
     */
    public FederatedGovernanceView.FederatedQualityScore getFederatedQualityScore() {
        Map<String, List<Double>> clusterScoreLists = new LinkedHashMap<>();
        Map<String, List<Double>> dimensionScoreLists = new LinkedHashMap<>();
        List<Double> allScores = new ArrayList<>();
        java.util.Set<String> tableIds = new java.util.HashSet<>();
        java.util.Set<String> clusterIds = new java.util.HashSet<>();

        for (FederatedGovernanceView.QualityReport report : reportStore.values()) {
            allScores.add(report.getOverallScore());
            tableIds.add(report.getTableId());
            if (report.getClusterId() != null) {
                clusterIds.add(report.getClusterId());
                clusterScoreLists.computeIfAbsent(report.getClusterId(), k -> new ArrayList<>())
                        .add(report.getOverallScore());
            }
            for (Map.Entry<String, Double> entry : report.getDimensionScores().entrySet()) {
                dimensionScoreLists.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(entry.getValue());
            }
        }

        double overall = average(allScores);
        Map<String, Double> clusterScores = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : clusterScoreLists.entrySet()) {
            clusterScores.put(entry.getKey(), average(entry.getValue()));
        }
        Map<String, Double> dimensionScores = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : dimensionScoreLists.entrySet()) {
            dimensionScores.put(entry.getKey(), average(entry.getValue()));
        }
        // 触发 clusterIds/tableIds 已在上面填充，此处无需额外操作

        return FederatedGovernanceView.FederatedQualityScore.builder()
                .overallScore(overall)
                .clusterScores(clusterScores)
                .dimensionScores(dimensionScores)
                .tableCount(tableIds.size())
                .clusterCount(clusterIds.size())
                .generatedAt(Instant.now())
                .build();
    }

    /**
     * 获取所有质量告警。
     */
    public List<FederatedGovernanceView.QualityAlert> getQualityAlerts() {
        return new ArrayList<>(alertStore.values());
    }

    /**
     * 确认告警。
     */
    public void acknowledgeAlert(String alertId) {
        FederatedGovernanceView.QualityAlert alert = alertStore.get(alertId);
        if (alert != null) {
            alert.setAcknowledged(true);
        }
    }

    /**
     * 获取质量规则模板库。
     */
    public List<FederatedGovernanceView.QualityRule> getRuleTemplates() {
        return new ArrayList<>(templateLibrary.values());
    }

    /**
     * 从模板创建规则。
     *
     * @param templateName 模板名
     * @param ruleName 新规则名
     * @return 创建的规则，模板不存在返回 null
     */
    public FederatedGovernanceView.QualityRule createRuleFromTemplate(String templateName, String ruleName) {
        FederatedGovernanceView.QualityRule template = templateLibrary.get(templateName);
        if (template == null) {
            return null;
        }
        FederatedGovernanceView.QualityRule rule = FederatedGovernanceView.QualityRule.builder()
                .ruleId("rule:" + UUID.randomUUID())
                .name(ruleName != null ? ruleName : template.getName())
                .dimension(template.getDimension())
                .expression(template.getExpression())
                .description(template.getDescription())
                .severity(template.getSeverity())
                .enabled(true)
                .template(false)
                .createdAt(Instant.now())
                .build();
        ruleStore.put(rule.getRuleId(), rule);
        return rule;
    }

    /**
     * 获取所有质量规则。
     */
    public List<FederatedGovernanceView.QualityRule> getAllQualityRules() {
        return new ArrayList<>(ruleStore.values());
    }

    /**
     * 构建质量视图。
     */
    public FederatedGovernanceView.QualityView buildQualityView() {
        return FederatedGovernanceView.QualityView.builder()
                .rules(getAllQualityRules())
                .reports(getAllQualityReports())
                .alerts(getQualityAlerts())
                .federatedScore(getFederatedQualityScore())
                .generatedAt(Instant.now())
                .build();
    }

    /**
     * 清空所有质量数据（用于测试）。
     */
    public void clear() {
        ruleStore.clear();
        reportStore.clear();
        tableReportsIndex.clear();
        alertStore.clear();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 从报告生成告警（失败的规则产生告警）。
     */
    private void generateAlertsFromReport(FederatedGovernanceView.QualityRule rule,
                                          FederatedGovernanceView.QualityReport report) {
        for (Map.Entry<String, Boolean> entry : report.getRuleResults().entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) {
                String alertId = "alert:" + UUID.randomUUID();
                FederatedGovernanceView.QualityAlert alert = FederatedGovernanceView.QualityAlert.builder()
                        .alertId(alertId)
                        .ruleId(rule.getRuleId())
                        .tableId(report.getTableId())
                        .clusterId(report.getClusterId())
                        .severity(rule.getSeverity())
                        .message("Quality rule " + rule.getName() + " failed on table " + report.getTableName())
                        .alertedAt(Instant.now())
                        .acknowledged(false)
                        .build();
                alertStore.put(alertId, alert);
            }
        }
    }

    /**
     * 计算平均值，空列表返回 0。
     */
    private double average(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /**
     * 集群质量执行器接口（抽象集群间质量检查，便于 Mock）。
     */
    public interface ClusterQualityExecutor {
        /**
         * 在指定集群上执行质量规则，返回各表的质量报告。
         *
         * @param rule 质量规则
         * @param clusterId 集群 ID
         * @return 质量报告列表
         */
        List<FederatedGovernanceView.QualityReport> executeRule(
                FederatedGovernanceView.QualityRule rule, String clusterId);
    }

}