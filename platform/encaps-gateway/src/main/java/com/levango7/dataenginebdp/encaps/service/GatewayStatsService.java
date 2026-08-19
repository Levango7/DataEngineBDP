package com.levango7.dataenginebdp.encaps.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关调用统计服务。
 *
 * <p>优先从 Prometheus 查询真实指标（HTTP 请求总数、成功率、延迟分布、QPS）；
 * 当 {@code app.gateway.prometheus-url} 未配置或查询失败时，回退到本地
 * {@code /actuator/metrics/http.server.requests} 聚合，确保开发环境可用。</p>
 *
 * <p>对齐前端 {@code GatewayStats} 契约：
 * todayCallCount / avgLatencyMs / successRate / activeKeyCount。</p>
 */
@Slf4j
@Service
public class GatewayStatsService {

    private static final String SUCCESS_RATE_PROMQL =
            "sum(rate(http_server_requests_seconds_count{status=~\"2..\"}[1d]))"
                    + " / sum(rate(http_server_requests_seconds_count[1d]))";
    private static final String AVG_LATENCY_PROMQL =
            "sum(rate(http_server_requests_seconds_sum[1d]))"
                    + " / sum(rate(http_server_requests_seconds_count[1d]))";
    private static final String TODAY_CALL_PROMQL =
            "sum(increase(http_server_requests_seconds_count[1d]))";

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String prometheusUrl;
    private final String actuatorBase;

    /**
     * 构造服务。
     *
     * @param prometheusUrl Prometheus 地址（如 http://prometheus:9090）；为空则只用本地 actuator
     * @param actuatorBase  本地 actuator 基址（如 http://localhost:8080）
     */
    public GatewayStatsService(
            @Value("${app.gateway.prometheus-url:}") String prometheusUrl,
            @Value("${app.gateway.actuator-base:http://localhost:8080}") String actuatorBase) {
        this.prometheusUrl = prometheusUrl;
        this.actuatorBase = actuatorBase;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 查询网关统计。
     *
     * @param activeKeyCount 活跃密钥数（由 Controller 传入，避免本服务依赖 Repository）
     * @return 统计 map，键对齐前端 GatewayStats
     */
    public Map<String, Object> getStats(long activeKeyCount) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("activeKeyCount", activeKeyCount);

        if (prometheusUrl == null || prometheusUrl.isBlank()) {
            // 开发环境：从本地 actuator 聚合
            fillFromActuator(stats);
            return stats;
        }

        try {
            stats.put("todayCallCount", (long) queryInstant(TODAY_CALL_PROMQL));
            stats.put("avgLatencyMs", Math.round(queryInstant(AVG_LATENCY_PROMQL) * 1000));
            double successRate = queryInstant(SUCCESS_RATE_PROMQL) * 100;
            stats.put("successRate", Math.round(successRate * 100) / 100.0);
        } catch (Exception e) {
            log.warn("Prometheus 查询失败，回退本地 actuator: {}", e.getMessage());
            fillFromActuator(stats);
        }
        return stats;
    }

    /** 调用 Prometheus /api/v1/query 瞬时查询，返回 scalar 值。 */
    private double queryInstant(String promql) {
        String url = prometheusUrl + "/api/v1/query?query=" + promql;
        String body = restTemplate.getForObject(url, String.class);
        if (body == null) {
            return 0;
        }
        try {
            JsonNode root = mapper.readTree(body);
            if (!"success".equals(root.path("status").asText())) {
                return 0;
            }
            JsonNode result = root.path("data").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return 0;
            }
            String value = result.get(0).path("value").get(1).asText("0");
            return Double.parseDouble(value);
        } catch (Exception e) {
            throw new RestClientException("解析 Prometheus 响应失败: " + e.getMessage(), e);
        }
    }

    /** 从本地 actuator 聚合统计（开发环境兜底）。 */
    private void fillFromActuator(Map<String, Object> stats) {
        try {
            String body = restTemplate.getForObject(
                    actuatorBase + "/actuator/metrics/http.server.requests", String.class);
            if (body == null) {
                stats.put("todayCallCount", 0);
                stats.put("avgLatencyMs", 0);
                stats.put("successRate", 100.0);
                return;
            }
            JsonNode node = mapper.readTree(body);
            long count = 0;
            double sumSec = 0;
            if (node.has("measurements")) {
                for (JsonNode m : node.path("measurements")) {
                    String stat = m.path("statistic").asText();
                    double val = m.path("value").asDouble();
                    if ("COUNT".equals(stat)) {
                        count = (long) val;
                    } else if ("TOTAL_TIME".equals(stat)) {
                        sumSec = val;
                    }
                }
            }
            // actuator 不分 status，简化为全部成功
            stats.put("todayCallCount", count);
            stats.put("avgLatencyMs", count == 0 ? 0 : Math.round((sumSec / count) * 1000));
            stats.put("successRate", 100.0);
        } catch (Exception e) {
            log.warn("本地 actuator 查询失败，使用零值: {}", e.getMessage());
            stats.put("todayCallCount", 0);
            stats.put("avgLatencyMs", 0);
            stats.put("successRate", 100.0);
        }
    }
}
