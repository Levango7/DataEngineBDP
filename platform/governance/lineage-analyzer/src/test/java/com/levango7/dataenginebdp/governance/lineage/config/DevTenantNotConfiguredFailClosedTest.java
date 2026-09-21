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
 * dev 租户<b>未配置</b>时必须 fail-closed 403（安全回归测试）。
 *
 * <p>这里把 {@code app.security.dev.tenant-id} 显式置空，模拟"开发态但没配兜底租户"。
 * {@link TenantContextFilter} 对 dev 租户<b>不设默认值</b>，所以此时任何业务端点
 * 都拿不到租户 → 403，且错误体要说明原因（而不是放行或返回 500）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.jwt.enabled=false",
                "app.security.dev.tenant-id="
        })
@DisplayName("dev 租户未配置：fail-closed 403")
class DevTenantNotConfiguredFailClosedTest {

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
    @DisplayName("未配置 dev 租户：业务端点 403，且错误体说明缺少租户上下文")
    void withoutDevTenant_shouldBeForbidden() {
        final int[] captured = {0};
        final String[] body = {""};
        restClient.get()
                .uri(baseUrl + "/upstream/a")
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {
                    captured[0] = resp.getStatusCode().value();
                    body[0] = new String(resp.getBody().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                })
                // 不能用 toEntity(String)：onStatus 回调里已经读过 body，
                // 再让 RestClient 解析一次会因流已关闭抛 StreamClosedException。
                .toBodilessEntity();

        assertEquals(403, captured[0], "未配置 dev 租户时必须 fail-closed 403");
        assertTrue(body[0].contains("缺少租户上下文"),
                "403 响应体应说明原因，实际: " + body[0]);
    }
}
