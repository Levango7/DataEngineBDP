package com.levango7.dataenginebdp.sqlgateway.service;

import com.levango7.dataenginebdp.sqlgateway.config.BackendProperties;
import com.levango7.dataenginebdp.sqlgateway.model.SqlExecuteResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BackendProxyService.proxyToTrino 真实 HTTP 链路测试（JDK HttpServer mock Trino）。
 *
 * <p>验证：POST /v1/statement → X-Trino-User 头 → Trino JSON 响应解析 → 行数据映射。
 * 模拟 Trino 返回 "SELECT 1" 的查询响应（columns + data）。</p>
 */
class BackendProxyTrinoE2ETest {

    private HttpServer server;
    private BackendProxyService service;
    private final AtomicReference<String> capturedUser = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        // mock Trino：返回列 id + 一行数据
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/statement", exchange -> {
            capturedUser.set(exchange.getRequestHeaders().getFirst("X-Trino-User"));
            byte[] body = exchange.getRequestBody().readAllBytes();
            capturedBody.set(new String(body, StandardCharsets.UTF_8));
            String json = "{\"columns\":[{\"name\":\"id\",\"type\":\"bigint\"}],"
                    + "\"data\":[[42]],\"stats\":{\"processedBytes\":1024}}";
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        BackendProperties props = new BackendProperties();
        BackendProperties.BackendConfig trino = new BackendProperties.BackendConfig();
        trino.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        trino.setTimeout(5);
        props.setTrino(trino);
        props.setDoris(new BackendProperties.BackendConfig());
        service = new BackendProxyService(WebClient.builder(), props);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void proxyToTrino_parsesRowsAndSendsTenantHeader() {
        SqlExecuteResponse resp = service.proxyToTrino("SELECT 1", "tenant-42").block();

        assertThat(resp).isNotNull();
        assertThat(resp.getStatus()).isEqualTo("SUCCESS");
        assertThat(resp.getEngine()).isEqualTo("trino");
        assertThat(resp.getColumns()).contains("id");
        assertThat(resp.getRows()).isNotEmpty();
        assertThat(resp.getRows().get(0)).contains(42);

        // X-Trino-User 头透传租户
        assertThat(capturedUser.get()).isEqualTo("tenant-42");
        // 请求体为原始 SQL
        assertThat(capturedBody.get()).isEqualTo("SELECT 1");
    }

    @Test
    void proxyToTrino_parsesErrorResponse() throws Exception {
        // 覆盖 error 响应场景：mock 服务器返回 error
        server.stop(0);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/statement", exchange -> {
                String json = "{\"error\":{\"message\":\"query failed\"}}";
                byte[] resp = json.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            });
            server.start();
            BackendProperties props = new BackendProperties();
            BackendProperties.BackendConfig trino = new BackendProperties.BackendConfig();
            trino.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
            trino.setTimeout(5);
            props.setTrino(trino);
            props.setDoris(new BackendProperties.BackendConfig());
            BackendProxyService errService = new BackendProxyService(WebClient.builder(), props);

            SqlExecuteResponse resp = errService.proxyToTrino("BAD SQL", "t1").block();
            assertThat(resp).isNotNull();
            assertThat(resp.getStatus()).isEqualTo("FAILED");
        } finally {
            server.stop(0);
        }
    }
}
