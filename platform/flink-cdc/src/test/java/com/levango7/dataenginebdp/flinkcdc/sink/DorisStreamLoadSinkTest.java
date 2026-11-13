package com.levango7.dataenginebdp.flinkcdc.sink;

import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord;
import org.apache.flink.api.connector.sink2.Sink.InitContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DorisStreamLoadSink 单测：以 JDK 内置 HttpServer 模拟 Doris FE，
 * 断言 Stream Load 协议面（方法/路径/认证/JSON 数组体）与失败语义（抛异常交作业重试）。
 */
class DorisStreamLoadSinkTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int httpStatus = 200;
    private volatile String respBody = "{\"Status\":\"Success\",\"NumberLoadedRows\":2,\"Message\":\"\"}";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = respBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(httpStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ChangeRecord record(String id) {
        ChangeRecord record = new ChangeRecord();
        record.setOp("c");
        record.setAfter(Map.of("id", id));
        return record;
    }

    private DorisStreamLoadSink sink(int batchSize) {
        return DorisStreamLoadSink.builder()
                .host("127.0.0.1")
                .port(port)
                .database("report")
                .table("mv_sales")
                .username("root")
                .password("pwd")
                .batchSize(batchSize)
                .build();
    }

    @Test
    @DisplayName("达到批量阈值触发一次 Stream Load：PUT/路径/认证/JSON 数组体逐项正确")
    void flushOnBatchThreshold() throws Exception {
        var writer = sink(2).createWriter((InitContext) null);
        writer.write(record("1"), null);
        assertNull(lastPath.get(), "未达批量阈值不应发起请求");
        writer.write(record("2"), null);

        assertEquals("PUT", lastMethod.get());
        assertEquals("/api/report/mv_sales/_stream_load", lastPath.get());
        assertNotNull(lastAuth.get());
        assertTrue(lastAuth.get().startsWith("Basic "));
        String decoded = new String(Base64.getDecoder().decode(lastAuth.get().substring("Basic ".length())),
                StandardCharsets.UTF_8);
        assertEquals("root:pwd", decoded);
        assertTrue(lastBody.get().startsWith("["), "strip_outer_array 形态应为 JSON 数组");
        assertTrue(lastBody.get().contains("\"id\":\"2\""));
        writer.close();
    }

    @Test
    @DisplayName("收尾 flush：不足一批的记录在 close 时仍发起 Stream Load（不静默丢弃）")
    void flushOnClose() throws Exception {
        var writer = sink(500).createWriter((InitContext) null);
        writer.write(record("9"), null);
        assertNull(lastPath.get());
        writer.close();
        assertEquals("/api/report/mv_sales/_stream_load", lastPath.get());
        assertTrue(lastBody.get().contains("\"id\":\"9\""));
    }

    @Test
    @DisplayName("Doris 返回非成功状态：抛 IllegalStateException（交由 Flink 按重启策略重试）")
    void failureThrowsForRetry() throws Exception {
        respBody = "{\"Status\":\"Fail\",\"Message\":\"table not found\"}";
        var writer = sink(1).createWriter((InitContext) null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> writer.write(record("1"), null));
        assertTrue(ex.getMessage().contains("Doris Stream Load 失败"));
        assertTrue(ex.getMessage().contains("table not found"));
    }

    @Test
    @DisplayName("响应非 JSON（如 FE 5xx HTML）：抛异常并带状态码")
    void nonJsonResponseThrows() throws Exception {
        httpStatus = 502;
        respBody = "<html>bad gateway</html>";
        var writer = sink(1).createWriter((InitContext) null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> writer.write(record("1"), null));
        assertTrue(ex.getMessage().contains("502"));
    }

    @Test
    @DisplayName("构建期必填校验：缺 database 或 table 立即失败")
    void builderValidatesRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> DorisStreamLoadSink.builder().host("h").table("t").build());
        assertThrows(IllegalArgumentException.class,
                () -> DorisStreamLoadSink.builder().host("h").database("d").build());
        assertThrows(IllegalArgumentException.class,
                () -> DorisStreamLoadSink.builder().database("d").table("t").build());
    }
}
