package com.levango7.dataenginebdp.sqlgateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.sqlgateway.config.BackendProperties;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 后端代理服务。
 *
 * <p>使用 WebFlux {@link WebClient} 将 SQL 请求真实代理到 Trino 或 Doris 后端：</p>
 * <ul>
 *   <li>Trino：POST {@code /v1/statement}，请求体为 SQL 文本，
 *       通过 {@code X-Trino-User} 头传递租户标识；</li>
 *   <li>Doris：POST {@code /api/query}，请求体为 JSON {@code {"sql":"..."}}，
 *       通过 {@code X-Tenant-Id} 头传递租户标识。</li>
 * </ul>
 *
 * <p>内置轻量级熔断器：连续失败达到阈值后熔断一段时间，期间直接返回错误响应，
 * 不再发起实际 HTTP 调用，避免后端不可用时拖垮网关。</p>
 *
 * @author shuqing-bigdata
 */
@Service
public class BackendProxyService {

    private static final Logger log = LoggerFactory.getLogger(BackendProxyService.class);

    /**
     * 兜底响应超时（秒），与 SqlGatewayConfig 中 WebClient 配置保持一致。
     */
    private static final long RESPONSE_TIMEOUT_SECONDS = 30L;

    /**
     * 熔断失败阈值：连续失败达到该值后熔断。
     */
    private static final int FAILURE_THRESHOLD = 5;

    /**
     * 熔断恢复时间（秒）：熔断后经过该时长进入半开状态尝试恢复。
     */
    private static final long RESET_TIMEOUT_SECONDS = 60L;

    private final WebClient trinoClient;
    private final WebClient dorisClient;
    private final ObjectMapper objectMapper;

    private final BackendProperties.BackendConfig trinoConfig;
    private final BackendProperties.BackendConfig dorisConfig;

    /**
     * Trino 熔断器：失败计数。
     */
    private final AtomicInteger trinoFailures = new AtomicInteger(0);
    /**
     * Trino 熔断器：熔断打开的时间戳（毫秒），0 表示未熔断。
     */
    private final AtomicLong trinoOpenSince = new AtomicLong(0L);

    /**
     * Doris 熔断器：失败计数。
     */
    private final AtomicInteger dorisFailures = new AtomicInteger(0);
    /**
     * Doris 熔断器：熔断打开的时间戳（毫秒），0 表示未熔断。
     */
    private final AtomicLong dorisOpenSince = new AtomicLong(0L);

    public BackendProxyService(WebClient.Builder webClientBuilder,
                               BackendProperties backendProperties) {
        this.trinoConfig = backendProperties.getTrino();
        this.dorisConfig = backendProperties.getDoris();
        this.objectMapper = new ObjectMapper();

        String trinoUrl = trinoConfig == null || trinoConfig.getUrl() == null
                ? "http://trino-service:8080" : trinoConfig.getUrl();
        String dorisUrl = dorisConfig == null || dorisConfig.getUrl() == null
                ? "http://doris-fe-service:9030" : dorisConfig.getUrl();

        this.trinoClient = webClientBuilder
                .baseUrl(trinoUrl)
                .defaultHeader("X-Trino-User", "sql-gateway")
                .defaultHeader("Content-Type", "text/plain")
                .build();

        this.dorisClient = webClientBuilder
                .baseUrl(dorisUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();

        log.info("BackendProxyService 初始化完成: trinoUrl={} dorisUrl={}", trinoUrl, dorisUrl);
    }

    /**
     * 代理 SQL 到 Trino 后端。
     *
     * <p>调用 Trino Statement API：{@code POST /v1/statement}，请求体为 SQL 文本。
     * 通过 {@code X-Trino-User} 头传递租户 ID 用于审计与资源隔离。</p>
     *
     * <p>Trino 响应为分页结构（含 {@code nextUri}），本实现取首页结果返回；
     * 如需完整结果集，可循环拉取 {@code nextUri} 直到为空。</p>
     *
     * @param sql      待执行的 SQL
     * @param tenantId 租户 ID（写入 X-Trino-User 头）
     * @return 后端执行响应（异步）
     */
    public Mono<SqlExecuteResponse> proxyToTrino(String sql, String tenantId) {
        String queryId = UUID.randomUUID().toString();

        // 熔断检查
        if (isCircuitOpen(trinoOpenSince, "trino")) {
            return Mono.just(errorResponse(queryId, "trino",
                    "Trino 后端熔断中，请稍后重试"));
        }

        long start = System.currentTimeMillis();
        return trinoClient.post()
                .uri("/v1/statement")
                .header("X-Trino-User", tenantId == null ? "sql-gateway" : tenantId)
                .bodyValue(sql)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
                .map(json -> parseTrinoResponse(json, queryId, System.currentTimeMillis() - start))
                .onErrorResume(e -> {
                    recordFailure(trinoFailures, trinoOpenSince, "trino");
                    log.error("proxyToTrino 失败 queryId={} err={}", queryId, e.toString());
                    return Mono.just(errorResponse(queryId, "trino",
                            "Trino 调用失败: " + e.getMessage()));
                });
    }

    /**
     * 代理 SQL 到 Doris 后端。
     *
     * <p>调用 Doris FE HTTP API：{@code POST /api/query}，
     * 请求体为 JSON {@code {"sql":"..."}}，通过 {@code X-Tenant-Id} 头传递租户标识。</p>
     *
     * @param sql      待执行的 SQL
     * @param tenantId 租户 ID（写入 X-Tenant-Id 头）
     * @return 后端执行响应（异步）
     */
    public Mono<SqlExecuteResponse> proxyToDoris(String sql, String tenantId) {
        String queryId = UUID.randomUUID().toString();

        // 熔断检查
        if (isCircuitOpen(dorisOpenSince, "doris")) {
            return Mono.just(errorResponse(queryId, "doris",
                    "Doris 后端熔断中，请稍后重试"));
        }

        long start = System.currentTimeMillis();
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("sql", sql);
        if (tenantId != null) {
            requestBody.put("tenant", tenantId);
        }

        return dorisClient.post()
                .uri("/api/query")
                .header("X-Tenant-Id", tenantId == null ? "default" : tenantId)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
                .map(json -> parseDorisResponse(json, queryId, System.currentTimeMillis() - start))
                .onErrorResume(e -> {
                    recordFailure(dorisFailures, dorisOpenSince, "doris");
                    log.error("proxyToDoris 失败 queryId={} err={}", queryId, e.toString());
                    return Mono.just(errorResponse(queryId, "doris",
                            "Doris 调用失败: " + e.getMessage()));
                });
    }

    /**
     * 解析 Trino Statement API 响应。
     *
     * <p>Trino 响应结构示例：</p>
     * <pre>
     * {
     *   "id": "20240101_xxx",
     *   "columns": [{"name":"col1","type":"varchar"}, ...],
     *   "data": [["v1","v2"], ...],
     *   "error": null,
     *   "nextUri": "..."
     * }
     * </pre>
     *
     * @param json       Trino 返回的 JSON 文本
     * @param queryId    网关生成的查询 ID
     * @param durationMs 已耗时（毫秒）
     * @return 解析后的 SqlExecuteResponse
     */
    private SqlExecuteResponse parseTrinoResponse(String json, String queryId, long durationMs) {
        try {
            JsonNode root = objectMapper.readTree(json);

            // Trino 返回 error 字段时表示执行失败
            JsonNode errorNode = root.get("error");
            if (errorNode != null && !errorNode.isNull()) {
                String msg = errorNode.has("message")
                        ? errorNode.get("message").asText()
                        : "Trino 执行错误";
                return SqlExecuteResponse.builder()
                        .queryId(queryId)
                        .status("FAILED")
                        .columns(List.of())
                        .rows(List.of())
                        .durationMs(durationMs)
                        .engine("trino")
                        .build();
            }

            // 解析列名
            List<String> columns = new ArrayList<>();
            JsonNode columnsNode = root.get("columns");
            if (columnsNode != null && columnsNode.isArray()) {
                for (JsonNode col : columnsNode) {
                    JsonNode nameNode = col.get("name");
                    columns.add(nameNode == null ? "" : nameNode.asText());
                }
            }

            // 解析数据行
            List<List<Object>> rows = new ArrayList<>();
            JsonNode dataNode = root.get("data");
            if (dataNode != null && dataNode.isArray()) {
                for (JsonNode row : dataNode) {
                    List<Object> rowValues = new ArrayList<>();
                    if (row.isArray()) {
                        for (JsonNode cell : row) {
                            rowValues.add(jsonNodeToObject(cell));
                        }
                    }
                    rows.add(rowValues);
                }
            }

            // 成功时重置熔断计数
            resetFailures(trinoFailures, trinoOpenSince);

            return SqlExecuteResponse.builder()
                    .queryId(queryId)
                    .status("SUCCESS")
                    .columns(columns)
                    .rows(rows)
                    .durationMs(durationMs)
                    .engine("trino")
                    .build();
        } catch (JsonProcessingException e) {
            log.error("解析 Trino 响应失败 queryId={} err={}", queryId, e.getMessage());
            return SqlExecuteResponse.builder()
                    .queryId(queryId)
                    .status("FAILED")
                    .columns(List.of())
                    .rows(List.of())
                    .durationMs(durationMs)
                    .engine("trino")
                    .build();
        }
    }

    /**
     * 解析 Doris FE HTTP API 响应。
     *
     * <p>预期 Doris 返回 JSON 结构：</p>
     * <pre>
     * {
     *   "code": 0,
     *   "msg": "OK",
     *   "data": {
     *     "columns": ["col1","col2"],
     *     "rows": [["v1","v2"], ...]
     *   }
     * }
     * </pre>
     * <p>若实际 Doris 返回结构不同，可在此处适配。</p>
     *
     * @param json       Doris 返回的 JSON 文本
     * @param queryId    网关生成的查询 ID
     * @param durationMs 已耗时（毫秒）
     * @return 解析后的 SqlExecuteResponse
     */
    private SqlExecuteResponse parseDorisResponse(String json, String queryId, long durationMs) {
        try {
            JsonNode root = objectMapper.readTree(json);

            // Doris 错误码判断（code != 0 视为失败）
            JsonNode codeNode = root.get("code");
            if (codeNode != null && codeNode.asInt(0) != 0) {
                String msg = root.has("msg") ? root.get("msg").asText() : "Doris 执行错误";
                log.warn("Doris 返回错误码 queryId={} code={} msg={}", queryId, codeNode.asInt(), msg);
                return SqlExecuteResponse.builder()
                        .queryId(queryId)
                        .status("FAILED")
                        .columns(List.of())
                        .rows(List.of())
                        .durationMs(durationMs)
                        .engine("doris")
                        .build();
            }

            JsonNode dataNode = root.get("data");
            List<String> columns = new ArrayList<>();
            List<List<Object>> rows = new ArrayList<>();

            if (dataNode != null) {
                // 解析列名
                JsonNode columnsNode = dataNode.get("columns");
                if (columnsNode != null && columnsNode.isArray()) {
                    for (JsonNode col : columnsNode) {
                        columns.add(col.isTextual() ? col.asText() : col.toString());
                    }
                }

                // 解析数据行
                JsonNode rowsNode = dataNode.get("rows");
                if (rowsNode != null && rowsNode.isArray()) {
                    for (JsonNode row : rowsNode) {
                        List<Object> rowValues = new ArrayList<>();
                        if (row.isArray()) {
                            for (JsonNode cell : row) {
                                rowValues.add(jsonNodeToObject(cell));
                            }
                        }
                        rows.add(rowValues);
                    }
                }
            }

            // 成功时重置熔断计数
            resetFailures(dorisFailures, dorisOpenSince);

            return SqlExecuteResponse.builder()
                    .queryId(queryId)
                    .status("SUCCESS")
                    .columns(columns)
                    .rows(rows)
                    .durationMs(durationMs)
                    .engine("doris")
                    .build();
        } catch (JsonProcessingException e) {
            log.error("解析 Doris 响应失败 queryId={} err={}", queryId, e.getMessage());
            return SqlExecuteResponse.builder()
                    .queryId(queryId)
                    .status("FAILED")
                    .columns(List.of())
                    .rows(List.of())
                    .durationMs(durationMs)
                    .engine("doris")
                    .build();
        }
    }

    /**
     * 构造错误响应。
     *
     * @param queryId  查询 ID
     * @param engine   目标引擎
     * @param message  错误信息
     * @return 错误响应
     */
    private SqlExecuteResponse errorResponse(String queryId, String engine, String message) {
        log.warn("errorResponse queryId={} engine={} msg={}", queryId, engine, message);
        return SqlExecuteResponse.builder()
                .queryId(queryId)
                .status("DEGRADED")
                .columns(List.of())
                .rows(List.of())
                .durationMs(0L)
                .engine(engine)
                .build();
    }

    /**
     * 判断熔断器是否处于打开状态。
     *
     * <p>若距打开时间已超过 {@link #RESET_TIMEOUT_SECONDS}，则自动进入半开状态
     * （重置打开时间戳，允许一次试探请求）。</p>
     *
     * @param openSince 熔断打开时间戳原子变量
     * @param backend   后端名称（用于日志）
     * @return true 表示熔断打开（应快速失败）
     */
    private boolean isCircuitOpen(AtomicLong openSince, String backend) {
        long openedAt = openSince.get();
        if (openedAt == 0L) {
            return false;
        }
        long elapsed = (System.currentTimeMillis() - openedAt) / 1000L;
        if (elapsed >= RESET_TIMEOUT_SECONDS) {
            // 半开：尝试恢复
            log.info("[熔断器-{}] 半开状态，尝试恢复", backend);
            openSince.set(0L);
            return false;
        }
        log.warn("[熔断器-{}] 打开中，剩余 {}s", backend, RESET_TIMEOUT_SECONDS - elapsed);
        return true;
    }

    /**
     * 记录一次失败，达到阈值时打开熔断器。
     *
     * @param failures  失败计数原子变量
     * @param openSince 熔断打开时间戳原子变量
     * @param backend   后端名称
     */
    private void recordFailure(AtomicInteger failures, AtomicLong openSince, String backend) {
        int count = failures.incrementAndGet();
        if (count >= FAILURE_THRESHOLD && openSince.compareAndSet(0L, System.currentTimeMillis())) {
            log.error("[熔断器-{}] 连续失败 {} 次，熔断打开 {}s", backend, count, RESET_TIMEOUT_SECONDS);
        }
    }

    /**
     * 成功调用后重置失败计数与熔断状态。
     *
     * @param failures  失败计数原子变量
     * @param openSince 熔断打开时间戳原子变量
     */
    private void resetFailures(AtomicInteger failures, AtomicLong openSince) {
        failures.set(0);
        openSince.set(0L);
    }

    /**
     * 将 Jackson JsonNode 转为 Java 对象，保留原始类型。
     *
     * @param node JSON 节点
     * @return Java 对象（String/Number/Boolean/null）
     */
    private Object jsonNodeToObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isLong()) {
            return node.asLong();
        }
        if (node.isDouble() || node.isFloat()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        // 复杂类型退化为字符串
        return node.toString();
    }

    /**
     * 获取 Trino 后端 URL（用于诊断/健康检查）。
     *
     * @return Trino 后端 URL
     */
    public String getTrinoUrl() {
        return trinoConfig == null ? null : trinoConfig.getUrl();
    }

    /**
     * 获取 Doris 后端 URL（用于诊断/健康检查）。
     *
     * @return Doris 后端 URL
     */
    public String getDorisUrl() {
        return dorisConfig == null ? null : dorisConfig.getUrl();
    }
}
