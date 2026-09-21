package com.levango7.dataenginebdp.finops.billing.collector;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PrometheusClient 集成测试。
 *
 * <p>在 127.0.0.1 的随机端口上起一个轻量 HTTP 服务模拟 Prometheus 的
 * {@code /-/healthy} 与 {@code /api/v1/query_range} 端点，验证：</p>
 * <ul>
 *   <li>rangeQuery 能把 PromQL / 起止时间 / 步长正确拼到查询串上，并解析响应体</li>
 *   <li>Prometheus 健康时 isAvailable 返回 true</li>
 *   <li>Prometheus 返回错误状态时 isAvailable 返回 false（不抛异常，供上层降级）</li>
 *   <li>getPrometheusUrl 返回配置的地址</li>
 * </ul>
 */
class PrometheusClientTest {

    private static final String HEALTHY_BODY = "{\"status\":\"ok\"}";
    private static final String RANGE_BODY =
            "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}";

    private HttpServer server;
    private String baseUrl;
    private final List<String> receivedUris = new ArrayList<>();

    @BeforeEach
    void startPrometheusStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/-/healthy", exchange -> respond(exchange, 200, HEALTHY_BODY));
        server.createContext("/api/v1/query_range", exchange -> {
            receivedUris.add(exchange.getRequestURI().toString());
            respond(exchange, 200, RANGE_BODY);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopPrometheusStub() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    @Test
    void rangeQuery_sendsPromqlAndTimeRange_andParsesResponse() {
        PrometheusClient client = new PrometheusClient(baseUrl);

        Map<String, Object> response = client.rangeQuery(
                "sum by (tenant)(rate(container_cpu_usage_seconds_total[1m]))",
                1700000000L, 1700003600L, Duration.ofMinutes(1));

        assertThat(response).containsEntry("status", "success");
        assertThat(response.get("data")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data).containsEntry("resultType", "matrix");

        assertThat(receivedUris).hasSize(1);
        String uri = receivedUris.get(0);
        assertThat(uri).startsWith("/api/v1/query_range?");
        assertThat(uri).contains("query=")
                .contains("start=1700000000")
                .contains("end=1700003600")
                .contains("step=60s");
    }

    @Test
    void isAvailable_returnsTrue_whenPrometheusHealthy() {
        PrometheusClient client = new PrometheusClient(baseUrl);

        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_returnsFalse_whenPrometheusRespondsError() {
        // 指向不存在的路径前缀：/-/healthy 命中 404 → 视为不可用
        PrometheusClient client = new PrometheusClient(baseUrl + "/not-prometheus");

        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void rangeQuery_propagatesErrorStatus_asException() {
        PrometheusClient client = new PrometheusClient(baseUrl + "/not-prometheus");

        assertThatThrownBy(() -> client.rangeQuery("up", 1L, 2L, Duration.ofMinutes(1)))
                .isInstanceOf(RestClientResponseException.class);
    }

    @Test
    void getPrometheusUrl_returnsConfiguredBaseUrl() {
        PrometheusClient client = new PrometheusClient(baseUrl);

        assertThat(client.getPrometheusUrl()).isEqualTo(baseUrl);
    }
}
