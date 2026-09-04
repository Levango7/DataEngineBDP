package com.levango7.dataenginebdp.common.security.ratelimit;

import com.levango7.dataenginebdp.common.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RateLimitFilter 集成行为测试：真实 Filter + Mock Servlet 对象。
 *
 * <p>验证 429 语义、Retry-After 头、维度选择（租户/IP）与放行路径。</p>
 */
class RateLimitFilterTest {

    private static final List<RateLimitRule> RULES = List.of(
            new RateLimitRule("/api/v1/auth", 3, 1.0),
            new RateLimitRule("/api/", 600, 1.0));

    private RateLimitFilter filter;
    private final AtomicInteger chainInvocations = new AtomicInteger();

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        chainInvocations.set(0);
        filter = new RateLimitFilter(new RateLimitRegistry(RULES, 600, 60));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockHttpServletResponse doRequest(String method, String path, String clientIp) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (clientIp != null) {
            request.setRemoteAddr(clientIp);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainInvocations.incrementAndGet());
        return response;
    }

    @Test
    @DisplayName("非 API 路径直接放行不计数")
    void nonApiPathPassesThrough() throws Exception {
        MockHttpServletResponse res = doRequest("GET", "/actuator/health", "1.1.1.1");
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chainInvocations.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("登录路径超限返回 429 + Retry-After，业务链不再执行")
    void loginExceedsLimitReturns429() throws Exception {
        // 容量 3：前 3 次放行
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse res = doRequest("POST", "/api/v1/auth/login", "9.9.9.9");
            assertThat(res.getStatus()).as("第 %d 次应放行", i + 1).isEqualTo(200);
        }
        // 第 4 次超限
        MockHttpServletResponse blocked = doRequest("POST", "/api/v1/auth/login", "9.9.9.9");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
        assertThat(blocked.getContentAsString()).contains("rate limit");
        assertThat(chainInvocations.get()).as("超限请求不应到达业务链").isEqualTo(3);
    }

    @Test
    @DisplayName("匿名按 IP 维度：不同来源 IP 配额独立")
    void anonymousLimitedPerIp() throws Exception {
        // 同一 IP 打满
        for (int i = 0; i < 3; i++) {
            doRequest("POST", "/api/v1/auth/login", "9.9.9.9");
        }
        assertThat(doRequest("POST", "/api/v1/auth/login", "9.9.9.9").getStatus()).isEqualTo(429);
        // 另一 IP 独立配额
        assertThat(doRequest("POST", "/api/v1/auth/login", "8.8.8.8").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("X-Forwarded-For 优先作为限流 IP（反向代理场景）")
    void xffUsedAsClientIp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "7.7.7.7, 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainInvocations.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(200);

        // 打满 7.7.7.7 的配额（共用 XFF）
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            req.setRemoteAddr("10.0.0.1");
            req.addHeader("X-Forwarded-For", "7.7.7.7");
            filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> chainInvocations.incrementAndGet());
        }
        // 第 4 次（XFF=7.7.7.7）应超限
        MockHttpServletRequest blocked = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        blocked.setRemoteAddr("10.0.0.1");
        blocked.addHeader("X-Forwarded-For", "7.7.7.7");
        MockHttpServletResponse blockedRes = new MockHttpServletResponse();
        filter.doFilter(blocked, blockedRes, (r, s) -> chainInvocations.incrementAndGet());
        assertThat(blockedRes.getStatus()).isEqualTo(429);
    }
}
