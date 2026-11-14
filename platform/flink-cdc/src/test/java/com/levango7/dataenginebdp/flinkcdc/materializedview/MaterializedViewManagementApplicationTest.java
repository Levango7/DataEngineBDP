package com.levango7.dataenginebdp.flinkcdc.materializedview;

import com.levango7.dataenginebdp.flinkcdc.materializedview.service.MaterializedViewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物化视图管理面宿主（Phase C1）端到端测试：真实随机端口起 Tomcat，
 * 断言①上下文与安全链装配齐备；②健康端点 /api/v1/health 放行；③管理 API 无凭证被拒。
 *
 * <p>取端口用 {@code Environment.getProperty("local.server.port")}（避开 Boot 各代
 * 测试切片/注解包的迁移面）；HTTP 客户端用 JDK HttpClient。</p>
 */
@SpringBootTest(classes = MaterializedViewManagementApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.jwt.secret=test-mv-management-secret-0123456789abcdef",
                "app.security.jwt.issuer=test-issuer"
        })
class MaterializedViewManagementApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpResponse<String> get(String path) throws Exception {
        int port = Integer.parseInt(environment.getProperty("local.server.port"));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("上下文装配：安全过滤链与物化视图服务 Bean 均在位")
    void contextWiring() {
        assertNotNull(context.getBean(SecurityFilterChain.class), "安全过滤链 Bean 缺失");
        assertNotNull(context.getBean(MaterializedViewService.class), "物化视图服务 Bean 缺失");
    }

    @Test
    @DisplayName("/api/v1/health 放行且返回 UP（JwtAuthFilter 白名单口径；chart 探针默认同口径）")
    void healthEndpointIsPublic() throws Exception {
        HttpResponse<String> health = get("/api/v1/health");
        assertTrue(health.statusCode() == 200, "健康端点应 200，实际 " + health.statusCode());
        assertTrue(health.body().contains("UP"));
    }

    @Test
    @DisplayName("/api/materialized-views 无凭证被拒（JWT 过滤链生效，非匿名可达）")
    void managementApiRequiresAuth() throws Exception {
        HttpResponse<String> response = get("/api/materialized-views");
        int code = response.statusCode();
        assertTrue(code >= 400 && code < 500,
                "无凭证访问管理 API 应为 4xx（401/403），实际 " + code);
    }
}
