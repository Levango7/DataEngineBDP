package com.levango7.dataenginebdp.infra.orchestrator.capacity;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prometheus 指标采集服务（v2.1 容量规划）。
 *
 * <p>从 Prometheus 查询历史负载数据，作为容量预测模型的输入。
 * 采集指标包括：CPU 利用率、内存利用率、副本数、QPS。</p>
 *
 * <p>查询 PromQL 示例：</p>
 * <ul>
 *   <li>CPU: {@code rate(container_cpu_usage_seconds_total{pod=~"svc-.*"}[5m])}</li>
 *   <li>内存: {@code container_memory_working_set_bytes{pod=~"svc-.*"} / container_spec_memory_limit_bytes{pod=~"svc-.*"}}</li>
 *   <li>QPS: {@code rate(http_server_requests_seconds_count{service="svc"}[5m])}</li>
 * </ul>
 */
public class PrometheusMetricsService {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsService.class);

    private final WebClient webClient;
    private final CapacityConfig config;
    private final Map<String, List<LoadSample>> metricsCache = new ConcurrentHashMap<>();

    /**
     * 构造服务。
     *
     * @param webClientBuilder WebClient 构建器
     * @param config           容量配置
     */
    public PrometheusMetricsService(WebClient.Builder webClientBuilder, CapacityConfig config) {
        this.config = config;
        this.webClient = webClientBuilder
                .baseUrl(config.getPrometheusAddress())
                .build();
    }

    /**
     * 查询服务历史负载数据。
     *
     * @param serviceName 服务名
     * @return 历史负载数据点列表（按时间升序）
     */
    public List<LoadSample> queryHistoryLoad(String serviceName) {
        if (!config.isEnabled()) {
            log.warn("容量规划已禁用，返回空历史数据: {}", serviceName);
            return List.of();
        }

        log.info("查询服务[{}]历史负载数据，窗口={}天，间隔={}分钟",
                serviceName, config.getHistoryWindowDays(), config.getSampleIntervalMinutes());

        List<LoadSample> samples = new ArrayList<>();
        Instant end = Instant.now();
        Instant start = end.minus(config.historyWindow());
        Duration step = Duration.ofMinutes(config.getSampleIntervalMinutes());

        try {
            List<Double> cpuSeries = queryRange(buildCpuQuery(serviceName), start, end, step);
            List<Double> memorySeries = queryRange(buildMemoryQuery(serviceName), start, end, step);
            List<Double> qpsSeries = queryRange(buildQpsQuery(serviceName), start, end, step);
            List<Double> replicaSeries = queryRange(buildReplicaQuery(serviceName), start, end, step);

            int size = Math.min(Math.min(cpuSeries.size(), memorySeries.size()),
                    Math.min(qpsSeries.size(), replicaSeries.size()));

            Instant sampleTime = start;
            for (int i = 0; i < size; i++) {
                double cpu = clamp(cpuSeries.get(i), 0.0, 1.0);
                double memory = clamp(memorySeries.get(i), 0.0, 1.0);
                double qps = Math.max(0.0, qpsSeries.get(i));
                int replicas = (int) Math.max(1, Math.round(replicaSeries.get(i)));

                LoadSample sample = new LoadSample(sampleTime, cpu, memory, replicas, qps);
                if (sample.isValid()) {
                    samples.add(sample);
                }
                sampleTime = sampleTime.plus(step);
            }

            metricsCache.put(serviceName, samples);
            log.info("服务[{}]采集到 {} 个有效数据点", serviceName, samples.size());
        } catch (Exception e) {
            log.error("查询服务[{}]历史负载数据失败: {}", serviceName, e.getMessage(), e);
            return metricsCache.getOrDefault(serviceName, List.of());
        }

        return samples;
    }

    /**
     * 查询当前副本数。
     *
     * @param serviceName 服务名
     * @return 当前副本数
     */
    public int queryCurrentReplicas(String serviceName) {
        try {
            double replicas = queryInstant(buildReplicaQuery(serviceName));
            return (int) Math.max(1, Math.round(replicas));
        } catch (Exception e) {
            log.warn("查询服务[{}]当前副本数失败，默认返回 1: {}", serviceName, e.getMessage());
            return 1;
        }
    }

    /**
     * 构造 CPU 利用率 PromQL。
     *
     * @param serviceName 服务名
     * @return PromQL
     */
    private String buildCpuQuery(String serviceName) {
        return String.format(
                "avg(rate(container_cpu_usage_seconds_total{pod=~\"%s-.*\"}[5m])) by (pod)",
                serviceName);
    }

    /**
     * 构造内存利用率 PromQL。
     *
     * @param serviceName 服务名
     * @return PromQL
     */
    private String buildMemoryQuery(String serviceName) {
        return String.format(
                "avg(container_memory_working_set_bytes{pod=~\"%s-.*\"} / "
                        + "container_spec_memory_limit_bytes{pod=~\"%s-.*\"}) by (pod)",
                serviceName, serviceName);
    }

    /**
     * 构造 QPS PromQL。
     *
     * @param serviceName 服务名
     * @return PromQL
     */
    private String buildQpsQuery(String serviceName) {
        return String.format(
                "sum(rate(http_server_requests_seconds_count{service=\"%s\"}[5m]))",
                serviceName);
    }

    /**
     * 构造副本数 PromQL。
     *
     * @param serviceName 服务名
     * @return PromQL
     */
    private String buildReplicaQuery(String serviceName) {
        return String.format(
                "count(kube_pod_status_phase{pod=~\"%s-.*\",phase=\"Running\"})",
                serviceName);
    }

    /**
     * 范围查询 Prometheus。
     *
     * @param query  PromQL
     * @param start  开始时间
     * @param end    结束时间
     * @param step   步长
     * @return 数值序列
     */
    private List<Double> queryRange(String query, Instant start, Instant end, Duration step) {
        List<Double> result = new ArrayList<>();
        long startEpoch = start.getEpochSecond();
        long endEpoch = end.getEpochSecond();
        long stepSec = step.getSeconds();

        JsonNode response = webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/query_range")
                        .queryParam("query", query)
                        .queryParam("start", startEpoch)
                        .queryParam("end", endEpoch)
                        .queryParam("step", stepSec)
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(config.getWebClientTimeoutMs()))
                .block();

        if (response != null && response.has("data")) {
            JsonNode resultNode = response.path("data").path("result");
            if (resultNode.isArray() && !resultNode.isEmpty()) {
                JsonNode values = resultNode.get(0).path("values");
                for (JsonNode value : values) {
                    if (value.isArray() && value.size() >= 2) {
                        String strValue = value.get(1).asText();
                        try {
                            result.add(Double.parseDouble(strValue));
                        } catch (NumberFormatException e) {
                            result.add(0.0);
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * 即时查询 Prometheus。
     *
     * @param query PromQL
     * @return 数值
     */
    private double queryInstant(String query) {
        JsonNode response = webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/query")
                        .queryParam("query", query)
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(config.getWebClientTimeoutMs()))
                .block();

        if (response != null && response.has("data")) {
            JsonNode resultNode = response.path("data").path("result");
            if (resultNode.isArray() && !resultNode.isEmpty()) {
                JsonNode value = resultNode.get(0).path("value");
                if (value.isArray() && value.size() >= 2) {
                    try {
                        return Double.parseDouble(value.get(1).asText());
                    } catch (NumberFormatException e) {
                        return 0.0;
                    }
                }
            }
        }
        return 0.0;
    }

    /**
     * 将数值限制在 [min, max] 范围内。
     *
     * @param value 原始值
     * @param min   最小值
     * @param max   最大值
     * @return 限制后的值
     */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}