package com.levango7.dataenginebdp.flinkcdc.materializedview;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DorisHttpSqlExecutor 单测：FE HTTP 协议面断言（路径/认证/语句体）+ 契约语义
 * （成功 true、失败 false 且不抛——ViewRefresher 的执行器契约）。
 */
class DorisHttpSqlExecutorTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile String respBody = "{\"msg\":\"success\",\"code\":0,\"count\":0}";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = respBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
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

    private DorisHttpSqlExecutor executor() {
        return new DorisHttpSqlExecutor("127.0.0.1:" + port, "root", "pwd", "report", 3000L, 5000L);
    }

    @Test
    @DisplayName("成功路径：POST /api/query/{db} + Basic 认证 + 语句体原样，返回 true")
    void successPath() {
        Boolean result = executor().apply("REFRESH MATERIALIZED VIEW mv_sales");
        assertTrue(result);
        assertEquals("POST", lastMethod.get());
        assertEquals("/api/query/report", lastPath.get());
        String decoded = new String(Base64.getDecoder().decode(lastAuth.get().substring("Basic ".length())),
                StandardCharsets.UTF_8);
        assertEquals("root:pwd", decoded);
        assertEquals("REFRESH MATERIALIZED VIEW mv_sales", lastBody.get());
    }

    @Test
    @DisplayName("Doris 返回 code!=0：false 且不抛（刷新结果如实失败）")
    void dorisErrorReturnsFalse() {
        respBody = "{\"msg\":\"errCode = 2, detailMessage = Unknown view\",\"code\":1}";
        assertFalse(executor().apply("REFRESH MATERIALIZED VIEW missing"));
    }

    @Test
    @DisplayName("FE 不可达：false 且不抛")
    void unreachableReturnsFalse() {
        DorisHttpSqlExecutor executor = new DorisHttpSqlExecutor("127.0.0.1:1", "root", "pwd", "report", 500L, 500L);
        assertFalse(executor.apply("SELECT 1"));
    }

    @Test
    @DisplayName("空 SQL：直接 false，不发请求")
    void blankSqlShortCircuits() {
        assertFalse(executor().apply("   "));
        assertFalse(executor().apply(null));
    }

    @Test
    @DisplayName("fe-hosts 多主机取首个（去空白）")
    void firstHostSelection() {
        assertEquals("10.0.0.1:8030", DorisHttpSqlExecutor.firstHost(" 10.0.0.1:8030 , 10.0.0.2:8030 "));
        assertThrows(IllegalArgumentException.class, () -> DorisHttpSqlExecutor.firstHost(" , "));
    }
}
