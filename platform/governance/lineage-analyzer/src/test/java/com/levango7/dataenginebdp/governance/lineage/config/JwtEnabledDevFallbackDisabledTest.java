package com.levango7.dataenginebdp.governance.lineage.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dev 租户回退在 JWT 开启态下必须失效（安全回归测试）。
 *
 * <p>本测试刻意<b>同时</b>把 {@code app.security.jwt.enabled=true} 与
 * {@code app.security.dev.tenant-id=dev-tenant} 配齐——即"最容易触发回退"的组合。
 * 若 {@link TenantContextFilter} 的 dev 回退逻辑写错（比如只判断 dev 租户是否配置、
 * 漏了 jwtEnabled 前置条件），本测试会立刻红。</p>
 *
 * <p>判据：无 token / 无效 token 的请求必须是 <b>401</b>。
 * 一旦 dev 回退被误激活，请求会带上 dev 租户 + dev 主体 → 走到 Controller 返回 200，
 * 与本测试断言直接冲突。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.jwt.enabled=true",
                "app.security.dev.tenant-id=dev-tenant"
        })
@DisplayName("dev 租户回退：JWT 开启态下绝不生效")
class JwtEnabledDevFallbackDisabledTest {

    @LocalServerPort
    private int port;

    private RestClient restClient;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/api/v1/lineage";
        restClient = RestClient.create();
    }

    @Test
    @DisplayName("无 token：401，dev 回退不得放行（否则会变成 200）")
    void noToken_shouldBeUnauthorized() {
        int status = call(upstreamRequest(null));
        assertEquals(401, status,
                "JWT 开启态下无 token 必须 401；返回 200 说明 dev 回退被误激活");
    }

    @Test
    @DisplayName("无效 token：401，dev 回退不得放行")
    void invalidToken_shouldBeUnauthorized() {
        int status = call(upstreamRequest("Bearer not-a-valid-jwt"));
        assertEquals(401, status,
                "无效 token 必须 401；返回 200 说明 dev 回退被误激活");
    }

    /**
     * 发一次 GET /upstream/a 并返回状态码（4xx/5xx 不抛异常，由 onStatus 捕获）。
     */
    private int call(RestClient.RequestHeadersSpec<?> spec) {
        final int[] captured = {0};
        spec.retrieve()
                .onStatus(status -> true,
                        (req, resp) -> captured[0] = resp.getStatusCode().value())
                .toEntity(String.class);
        assertTrue(captured[0] > 0, "应收到 HTTP 响应");
        return captured[0];
    }

    private RestClient.RequestHeadersSpec<?> upstreamRequest(String authorization) {
        RestClient.RequestHeadersUriSpec<?> get = restClient.get();
        RestClient.RequestHeadersSpec<?> spec = get.uri(baseUrl + "/upstream/a");
        return authorization == null ? spec : spec.header("Authorization", authorization);
    }
}
