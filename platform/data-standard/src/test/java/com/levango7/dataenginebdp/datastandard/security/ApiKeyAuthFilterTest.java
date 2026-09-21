package com.levango7.dataenginebdp.datastandard.security;

import com.levango7.dataenginebdp.common.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiKeyAuthFilter 单元测试（X-API-Key 认证与 Bearer JWT 明确分离）。
 *
 * <p>验证五种真实行为：未启用时透传、未携带 / 空 API Key 时透传给 JwtAuthFilter、
 * 非法 Key 直接 401 且中断链路、合法 Key 设置租户上下文与 ROLE_API_KEY 并在结束后清理、
 * 健康检查 / 登录 / actuator 端点跳过认证。</p>
 */
@DisplayName("ApiKeyAuthFilter API Key 认证测试")
class ApiKeyAuthFilterTest {

    private static final String VALID_KEY = "test-api-key-1";
    private static final String API_TENANT = "api-tenant";

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("未启用 API Key 认证时直接透传，不做任何认证")
    void shouldPassThroughWhenDisabled() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(false, VALID_KEY, API_TENANT);
        RecordingChain chain = new RecordingChain();
        MockHttpServletRequest request = request("/api/v1/standards", "any-key");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.invoked).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("未携带 X-API-Key 时透传，交由 JwtAuthFilter 处理 Bearer JWT")
    void shouldPassThroughWhenHeaderAbsent() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, VALID_KEY, API_TENANT);
        RecordingChain chain = new RecordingChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/standards");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.invoked).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("X-API-Key 为空白串时视为未携带，同样透传")
    void shouldPassThroughWhenHeaderBlank() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, VALID_KEY, API_TENANT);
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request("/api/v1/standards", "   "), new MockHttpServletResponse(), chain);

        assertThat(chain.invoked).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("非法 API Key → 401 且中断过滤链，绝不透传到下游")
    void shouldRejectInvalidKey() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, VALID_KEY, API_TENANT);
        RecordingChain chain = new RecordingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/api/v1/standards", "wrong-key"), response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"invalid API key\"}");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("合法 API Key → 设置租户上下文与 ROLE_API_KEY，链路结束后清理干净")
    void shouldAuthenticateValidKeyAndCleanUp() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, VALID_KEY + ",other-key", API_TENANT);
        RecordingChain chain = new RecordingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/api/v1/standards", VALID_KEY), response, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.tenantDuringChain).isEqualTo(API_TENANT);
        assertThat(chain.userIdDuringChain).isEqualTo("api-key-client");
        assertThat(chain.authenticationDuringChain).isNotNull();
        assertThat(chain.authenticationDuringChain.getName()).isEqualTo("api-key-client");
        assertThat(chain.authenticationDuringChain.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_API_KEY");
        // finally 块必须清理，避免线程池复用导致租户串号
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("合法 API Key 白名单为空时任何 Key 都被拒绝")
    void shouldRejectWhenWhitelistEmpty() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, "", API_TENANT);
        RecordingChain chain = new RecordingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/api/v1/standards", VALID_KEY), response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("健康检查 / 登录 / actuator 端点跳过 API Key 认证")
    void shouldSkipExemptPaths() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, VALID_KEY, API_TENANT);

        assertThat(filter.shouldNotFilter(servletPathRequest("/api/v1/health"))).isTrue();
        assertThat(filter.shouldNotFilter(servletPathRequest("/api/v1/auth/login"))).isTrue();
        assertThat(filter.shouldNotFilter(servletPathRequest("/actuator/prometheus"))).isTrue();
        assertThat(filter.shouldNotFilter(servletPathRequest("/api/v1/standards"))).isFalse();
    }

    @Test
    @DisplayName("servletPath 为空时回退到 requestURI 判断豁免路径")
    void shouldFallBackToRequestUriWhenServletPathEmpty() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, VALID_KEY, API_TENANT);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        request.setServletPath("");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("servletPath 为 null 时同样回退到 requestURI 判断豁免路径")
    void shouldFallBackToRequestUriWhenServletPathNull() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, VALID_KEY, API_TENANT);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getServletPath()).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/actuator/health");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    private static MockHttpServletRequest request(String uri, String apiKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setServletPath(uri);
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, apiKey);
        return request;
    }

    private static MockHttpServletRequest servletPathRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    /** 记录链路执行期间租户 / 认证上下文的过滤链，用于验证 finally 清理语义。 */
    private static final class RecordingChain implements FilterChain {

        private boolean invoked;
        private String tenantDuringChain;
        private String userIdDuringChain;
        private Authentication authenticationDuringChain;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                throws IOException, ServletException {
            invoked = true;
            tenantDuringChain = TenantContext.getTenantId();
            userIdDuringChain = TenantContext.getUserId();
            authenticationDuringChain = SecurityContextHolder.getContext().getAuthentication();
        }
    }
}
