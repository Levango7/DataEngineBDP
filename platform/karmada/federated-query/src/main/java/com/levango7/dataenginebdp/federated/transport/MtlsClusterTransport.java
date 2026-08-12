package com.levango7.dataenginebdp.federated.transport;

import com.levango7.dataenginebdp.federated.model.ClusterQueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mTLS 跨集群传输实现。
 *
 * <p>使用 {@link WebClient}（由 {@code MtlsConfig} 构造，装载双向证书）向
 * 目标集群的查询端点发起 POST 请求，取回 JSON 结果并归一化为
 * {@link ClusterQueryResult}。
 *
 * <p>请求格式（与 mock-cluster/server.py 对齐）：
 * <pre>
 * POST {clusterUrl}/query
 * Content-Type: application/json
 * {
 *   "sql": "SELECT ...",
 *   "database": "default"
 * }
 * </pre>
 *
 * <p>响应格式：
 * <pre>
 * {
 *   "status": "ok",
 *   "schema": {"col1": "STRING", ...},
 *   "rows": [{"col1": v, ...}, ...],
 *   "rowCount": N
 * }
 * </pre>
 *
 * <p>失败处理：
 * <ul>
 *   <li>连接超时 / 读超时 → 抛 {@link ClusterTransportException}，由上层降级策略捕获</li>
 *   <li>HTTP 4xx/5xx → 同上</li>
 *   <li>网络中断（连接被拒绝）→ 同上</li>
 * </ul>
 */
@Slf4j
@Component
public class MtlsClusterTransport implements ClusterTransport {

    private final WebClient webClient;

    public MtlsClusterTransport(@Qualifier("clusterWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public ClusterQueryResult execute(String clusterName, String clusterUrl, String sql, String database, long timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            return executeReactive(clusterName, clusterUrl, sql, database, timeoutMs).block();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Cluster [{}] query failed after {}ms: {}", clusterName, elapsed, e.getMessage());
            return ClusterQueryResult.builder()
                    .cluster(clusterName)
                    .clusterUrl(clusterUrl)
                    .success(false)
                    .elapsedMs(elapsed)
                    .error(e.getMessage())
                    .build();
        }
    }

    @Override
    public Mono<ClusterQueryResult> executeReactive(String clusterName, String clusterUrl, String sql, String database, long timeoutMs) {
        long start = System.currentTimeMillis();
        String url = normalizeUrl(clusterUrl);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", sql);
        if (database != null && !database.isBlank()) {
            body.put("database", database);
        }

        return webClient.post()
                .uri(url + "/query")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .retryWhen(Retry.max(1).filter(this::isRetriable))
                .map(resp -> parseResponse(clusterName, clusterUrl, resp, System.currentTimeMillis() - start))
                .onErrorResume(e -> {
                    long elapsed = System.currentTimeMillis() - start;
                    log.warn("Cluster [{}] reactive query failed after {}ms: {}", clusterName, elapsed, e.getMessage());
                    return Mono.just(ClusterQueryResult.builder()
                            .cluster(clusterName)
                            .clusterUrl(clusterUrl)
                            .success(false)
                            .elapsedMs(elapsed)
                            .error(e.getMessage())
                            .build());
                });
    }

    @Override
    public boolean isReachable(String clusterName, String clusterUrl) {
        String url = normalizeUrl(clusterUrl);
        try {
            webClient.get().uri(url + "/healthz")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            return true;
        } catch (Exception e) {
            log.debug("Cluster [{}] unreachable: {}", clusterName, e.getMessage());
            return false;
        }
    }

    @Override
    public String protocol() {
        return "mTLS-HTTPS";
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("clusterUrl must not be blank");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @SuppressWarnings("unchecked")
    private ClusterQueryResult parseResponse(String cluster, String clusterUrl, Map resp, long elapsed) {
        String status = String.valueOf(resp.getOrDefault("status", "ok"));
        Map<String, String> schema = (Map<String, String>) resp.getOrDefault("schema", Collections.emptyMap());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getOrDefault("rows", Collections.emptyList());
        int rowCount = rows.size();
        Object reportedCount = resp.get("rowCount");
        if (reportedCount instanceof Number) {
            rowCount = ((Number) reportedCount).intValue();
        }

        boolean success = "ok".equalsIgnoreCase(status);
        return ClusterQueryResult.builder()
                .cluster(cluster)
                .clusterUrl(clusterUrl)
                .success(success)
                .schema(schema)
                .rows(rows)
                .rowCount(rowCount)
                .elapsedMs(elapsed)
                .error(success ? null : "cluster returned status=" + status)
                .build();
    }

    private boolean isRetriable(Throwable e) {
        // 仅对超时与连接异常重试，不对业务错误重试。
        return e instanceof java.util.concurrent.TimeoutException
                || e instanceof java.net.ConnectException
                || (e.getMessage() != null && e.getMessage().contains("connection"));
    }

    /** 传输异常。 */
    public static class ClusterTransportException extends RuntimeException {
        public ClusterTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}