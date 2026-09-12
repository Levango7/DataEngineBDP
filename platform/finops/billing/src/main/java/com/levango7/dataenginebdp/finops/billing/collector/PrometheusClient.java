package com.levango7.dataenginebdp.finops.billing.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Prometheus 查询客户端（billing 模块内嵌）。
 *
 * <p>封装 Prometheus HTTP API 的 range query 调用，用于采集租户级资源用量指标。
 * 与 cost-model 的 PrometheusQueryClient 保持一致的接口契约，但本模块独立部署，
 * 不依赖 cost-model 的类。</p>
 */
@Component
public class PrometheusClient {

    private static final Logger log = LoggerFactory.getLogger(PrometheusClient.class);

    private final RestClient restClient;
    private final String prometheusUrl;

    public PrometheusClient(@Value("${app.prometheus.url:http://localhost:19090}") String prometheusUrl) {
        this.prometheusUrl = prometheusUrl;
        this.restClient = RestClient.builder()
                .baseUrl(prometheusUrl)
                .build();
        log.info("Billing Prometheus 客户端已初始化: {}", prometheusUrl);
    }

    /**
     * 执行 range query。
     *
     * @param query PromQL 查询表达式
     * @param start 起始时间（Unix 秒）
     * @param end   结束时间（Unix 秒）
     * @param step  步长
     * @return Prometheus 响应（Map 结构）
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
     *
     * <p>当 Prometheus 不可用时，账单生成器将以 0 用量降级生成账单，
     * 保证出账闭环不因监控基础设施故障而中断。</p>
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