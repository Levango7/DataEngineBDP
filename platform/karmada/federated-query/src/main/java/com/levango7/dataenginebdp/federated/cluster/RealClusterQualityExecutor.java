package com.levango7.dataenginebdp.federated.cluster;

import com.levango7.dataenginebdp.federated.governance.FederatedGovernanceView;
import com.levango7.dataenginebdp.federated.governance.FederatedQualityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实集群质量执行器。
 *
 * <p>向各集群的 rule-engine 服务发送质量检查请求，将结果映射为
 * {@link FederatedGovernanceView.QualityReport}。
 *
 * <p>Quality API 约定（与 platform/rule-engine 对齐）：
 * <pre>
 * POST {qualityUrl}/execute
 * Request Body:
 * {
 *   "rule": {
 *     "ruleId": "rule-1",
 *     "name": "not_null_check",
 *     "dimension": "COMPLETENESS",
 *     "expression": "id IS NOT NULL",
 *     "severity": "ERROR"
 *   },
 *   "clusterId": "cluster-a"
 * }
 * Response:
 * {
 *   "data": [
 *     {
 *       "tableId": "cluster-a:db.orders",
 *       "tableName": "orders",
 *       "clusterId": "cluster-a",
 *       "ruleResults": {"rule-1": true},
 *       "dimensionScores": {"COMPLETENESS": 95.0},
 *       "overallScore": 95.0,
 *       "checkedRows": 1000,
 *       "failedRows": 50
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>mTLS：复用 {@link com.levango7.dataenginebdp.federated.config.MtlsConfig#clusterWebClient}
 * 构造的 {@link WebClient}。
 */
@Slf4j
public class RealClusterQualityExecutor implements FederatedQualityService.ClusterQualityExecutor {

    private final WebClient webClient;
    private final FederatedClusterProperties props;

    public RealClusterQualityExecutor(WebClient webClient, FederatedClusterProperties props) {
        this.webClient = webClient;
        this.props = props;
    }

    @Override
    public List<FederatedGovernanceView.QualityReport> executeRule(
            FederatedGovernanceView.QualityRule rule, String clusterId) {
        if (rule == null || clusterId == null || clusterId.isEmpty()) {
            return Collections.emptyList();
        }
        FederatedClusterProperties.ClusterConfig cluster = props.findCluster(clusterId);
        if (cluster == null || cluster.getQualityUrl() == null) {
            log.warn("Cluster {} not configured or qualityUrl missing, return empty", clusterId);
            return Collections.emptyList();
        }
        if (!cluster.isEnabled()) {
            log.info("Cluster {} disabled, skip quality execution", clusterId);
            return Collections.emptyList();
        }

        String qualityUrl = cluster.getQualityUrl();
        Map<String, Object> requestBody = buildRequest(rule, clusterId);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = webClient.post()
                    .uri(qualityUrl + "/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(props.getResponseTimeout())
                    .block();
            return parseReports(resp, clusterId, rule.getRuleId());
        } catch (Exception e) {
            log.error("Execute quality rule failed: cluster={} url={} rule={} err={}",
                    clusterId, qualityUrl, rule.getRuleId(), e.getMessage(), e);
            throw new RuntimeException("Execute quality rule failed for cluster " + clusterId
                    + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 请求/响应处理
    // ------------------------------------------------------------------

    private Map<String, Object> buildRequest(FederatedGovernanceView.QualityRule rule, String clusterId) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> ruleMap = new LinkedHashMap<>();
        ruleMap.put("ruleId", rule.getRuleId());
        ruleMap.put("name", rule.getName());
        ruleMap.put("dimension", rule.getDimension());
        ruleMap.put("expression", rule.getExpression());
        ruleMap.put("severity", rule.getSeverity());
        ruleMap.put("enabled", rule.isEnabled());
        body.put("rule", ruleMap);
        body.put("clusterId", clusterId);
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<FederatedGovernanceView.QualityReport> parseReports(
            Map<String, Object> resp, String clusterId, String ruleId) {
        if (resp == null) {
            return Collections.emptyList();
        }
        Object data = resp.get("data");
        if (!(data instanceof List)) {
            // 兼容直接返回单个报告对象的 API
            if (resp.containsKey("tableId")) {
                return List.of(parseReport(resp, clusterId, ruleId));
            }
            return Collections.emptyList();
        }
        List<FederatedGovernanceView.QualityReport> reports = new ArrayList<>();
        for (Object item : (List<Object>) data) {
            if (item instanceof Map) {
                reports.add(parseReport((Map<String, Object>) item, clusterId, ruleId));
            }
        }
        return reports;
    }

    @SuppressWarnings("unchecked")
    private FederatedGovernanceView.QualityReport parseReport(
            Map<String, Object> raw, String defaultCluster, String ruleId) {
        Map<String, Boolean> ruleResults = new LinkedHashMap<>();
        Object rr = raw.get("ruleResults");
        if (rr instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) rr).entrySet()) {
                Object v = e.getValue();
                boolean pass = v instanceof Boolean ? (Boolean) v
                        : Boolean.parseBoolean(String.valueOf(v));
                ruleResults.put(e.getKey(), pass);
            }
        } else if (ruleId != null) {
            // 若响应未带 ruleResults，默认按 overallScore 推断
            double score = doubleValue(raw.get("overallScore"), 100.0);
            ruleResults.put(ruleId, score >= 60.0);
        }

        Map<String, Double> dimensionScores = new LinkedHashMap<>();
        Object ds = raw.get("dimensionScores");
        if (ds instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) ds).entrySet()) {
                dimensionScores.put(e.getKey(), doubleValue(e.getValue(), 0.0));
            }
        }

        return FederatedGovernanceView.QualityReport.builder()
                .tableId(str(raw.get("tableId")))
                .tableName(str(raw.get("tableName")))
                .clusterId(str(raw.get("clusterId"), defaultCluster))
                .ruleResults(ruleResults)
                .dimensionScores(dimensionScores)
                .overallScore(doubleValue(raw.get("overallScore"), 0.0))
                .checkedRows(longValue(raw.get("checkedRows"), 0L))
                .failedRows(longValue(raw.get("failedRows"), 0L))
                .generatedAt(instantValue(raw.get("generatedAt")))
                .build();
    }

    // ------------------------------------------------------------------
    // 类型转换工具
    // ------------------------------------------------------------------

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String str(Object o, String def) {
        String s = str(o);
        return (s == null || s.isEmpty()) ? def : s;
    }

    private static double doubleValue(Object o, double def) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o != null) {
            try {
                return Double.parseDouble(String.valueOf(o));
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    private static long longValue(Object o, long def) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o != null) {
            try {
                return Long.parseLong(String.valueOf(o));
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    private static Instant instantValue(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}