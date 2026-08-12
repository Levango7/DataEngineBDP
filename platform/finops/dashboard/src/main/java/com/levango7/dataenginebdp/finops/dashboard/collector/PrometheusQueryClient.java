package com.levango7.dataenginebdp.finops.dashboard.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Prometheus 查询客户端。
 *
 * <p>封装 Prometheus HTTP API 的 instant query 与 range query 调用。
 * 与 cost-model 的 PrometheusQueryClient 行为一致，便于复用查询表达式。</p>
 */
@Component
public class PrometheusQueryClient {

    private static final Logger log = LoggerFactory.getLogger(PrometheusQueryClient.class);

    private final RestClient restClient;
    private final String prometheusUrl;

    public PrometheusQueryClient(@Value("${app.prometheus.url}") String prometheusUrl) {
        this.prometheusUrl = prometheusUrl;
        this.restClient = RestClient.builder()
                .baseUrl(prometheusUrl)
                .build();
        log.info("Prometheus 查询客户端已初始化: {}", prometheusUrl);
    }

    /**
     * 执行 instant query。
     */
    public Map<String, Object> instantQuery(String query) {
        log.debug("PromQL instant: {}", query);
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/query")
                        .queryParam("query", query)
                        .build())
                .retrieve()
                .body(Map.class);
    }

    /**
     * 执行 range query。
     */
    public Map<String, Object> rangeQuery(String query, long start, long end, Duration step) {
        log.debug("PromQL range: {} [{}-{} step={}s]", query, start, end, step.getSeconds());
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/query_range")
                        .queryParam("query", query)
                        .queryParam("start", start)
                        .queryParam("end", end)
                        .queryParam("step", step.getSeconds() + "s")
                        .build())
                .retrieve()
                .body(Map.class);
    }

    /**
     * 探测 Prometheus 是否可用。
     */
    public boolean isAvailable() {
        try {
            restClient.get()
                    .uri("/-/healthy")
                    .retrieve()
                    .body(Map.class);
            return true;
        } catch (Exception e) {
            log.debug("Prometheus 不可用: {}", e.getMessage());
            return false;
        }
    }

    public String getPrometheusUrl() {
        return prometheusUrl;
    }
}