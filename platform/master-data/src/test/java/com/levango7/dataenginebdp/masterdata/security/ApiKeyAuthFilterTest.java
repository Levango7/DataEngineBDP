package com.levango7.dataenginebdp.masterdata.security;

import com.levango7.dataenginebdp.common.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiKeyAuthFilter 认证行为测试。
 *
 * <p>验证 X-API-Key 认证的三条路径与白名单跳过逻辑：</p>
 * <ul>
 *   <li>功能关闭（enabled=false）→ 直接放行，不设置任何认证上下文</li>
 *   <li>未携带 / 携带空白 X-API-Key → 放行，交由 Bearer JWT 过滤器处理</li>
 *   <li>携带非法 key → 401 + JSON 错误体，且不继续过滤链</li>
 *   <li>携带合法 key → 写入租户上下文与 ROLE_API_KEY 权限，过滤链结束后清理上下文</li>
 *   <li>{@code /api/v1/health}、{@code /api/v1/auth/login}、{@code /actuator/**} 不走本过滤器</li>
 * </ul>
 *
 * <p>本测试与生产代码同包，可直接调用 protected 的 {@code shouldNotFilter}。</p>
 */
@DisplayName("ApiKeyAuthFilter API Key 认证")
class ApiKeyAuthFilterTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("功能关闭时直接放行且不设置认证上下文")
    void shouldPassThroughWhenDisabled() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(false, "key-1", "tenant-A");
        MockHttpServletRequest request = requestWithApiKey("/api/v1/master-data", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, recordingChain(chainCalled, null, null));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("未携带 X-API-Key 时放行，交给 Bearer JWT 过滤器处理")
    void shouldPassThroughWhenHeaderAbsent() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, "key-1", "tenant-A");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/master-data");
        request.setServletPath("/api/v1/master-data");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, recordingChain(chainCalled, null, null));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("携带空白 X-API-Key 时等同于未携带，直接放行")
    void shouldPassThroughWhenHeaderBlank() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, "key-1", "tenant-A");
        MockHttpServletRequest request = requestWithApiKey("/api/v1/master-data", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, recordingChain(chainCalled, null, null));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("非法 key 返回 401 且不继续过滤链")
    void shouldReturn401WhenKeyInvalid() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, "key-1,key-2", "tenant-A");
        MockHttpServletRequest request = requestWithApiKey("/api/v1/master-data", "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, recordingChain(chainCalled, null, null));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).contains("invalid API key");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("合法 key 写入租户上下文与 ROLE_API_KEY 权限，并在过滤链结束后清理")
    void shouldAuthenticateAndClearContextAfterChain() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, "key-1,key-2", "tenant-A");
        MockHttpServletRequest request = requestWithApiKey("/api/v1/master-data", "key-2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        AtomicReference<String> tenantInChain = new AtomicReference<>();
        AtomicReference<String> userInChain = new AtomicReference<>();
        AtomicReference<Authentication> authInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            chainCalled.set(true);
            tenantInChain.set(TenantContext.getTenantId());
            userInChain.set(TenantContext.getUserId());
            authInChain.set(SecurityContextHolder.getContext().getAuthentication());
        });

        assertThat(chainCalled).isTrue();
        assertThat(tenantInChain.get()).isEqualTo("tenant-A");
        assertThat(userInChain.get()).isEqualTo("api-key-client");
        assertThat(authInChain.get()).isNotNull();
        assertThat(authInChain.get().getName()).isEqualTo("api-key-client");
        assertThat(authInChain.get().getAuthorities())
                .extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
                .containsExactly("ROLE_API_KEY");
        // 过滤链结束后必须清理，避免线程池复用导致租户串号
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("合法 key 列表按逗号分隔并 trim，空格不影响匹配")
    void shouldTrimConfiguredKeys() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, " key-1 , key-2 ", "tenant-B");
        MockHttpServletRequest request = requestWithApiKey("/api/v1/master-data", "key-2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        AtomicReference<String> tenantInChain = new AtomicReference<>();
        AtomicReference<Authentication> authInChain = new AtomicReference<>();

        filter.doFilter(request, response,
                recordingChain(chainCalled, tenantInChain, authInChain));

        assertThat(chainCalled).isTrue();
        assertThat(tenantInChain.get()).isEqualTo("tenant-B");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("空 key 配置时任何 key 都被拒绝")
    void shouldRejectAnyKeyWhenNoValidKeyConfigured() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, "", "tenant-A");
        MockHttpServletRequest request = requestWithApiKey("/api/v1/master-data", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, recordingChain(chainCalled, null, null));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("健康检查 / 登录 / actuator 端点跳过本过滤器")
    void shouldSkipWhitelistedPaths() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, "key-1", "tenant-A");

        assertThat(filter.shouldNotFilter(pathRequest("/api/v1/health"))).isTrue();
        assertThat(filter.shouldNotFilter(pathRequest("/api/v1/auth/login"))).isTrue();
        assertThat(filter.shouldNotFilter(pathRequest("/actuator/health"))).isTrue();
        assertThat(filter.shouldNotFilter(pathRequest("/actuator/prometheus"))).isTrue();
        assertThat(filter.shouldNotFilter(pathRequest("/api/v1/master-data"))).isFalse();
    }

    @Test
    @DisplayName("请求路径为空（servletPath 与 requestURI 均为 null）时不跳过过滤器")
    void shouldNotSkipWhenPathUnavailable() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(true, "key-1", "tenant-A");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getServletPath()).thenReturn(null);
        when(request.getRequestURI()).thenReturn(null);

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    /**
     * 构造携带 X-API-Key 的请求。
     */
    private MockHttpServletRequest requestWithApiKey(String path, String apiKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, apiKey);
        return request;
    }

    /**
     * 构造仅有路径信息的请求。
     */
    private MockHttpServletRequest pathRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    /**
     * 构造记录过滤链内部可见状态的 FilterChain。
     */
    private FilterChain recordingChain(AtomicBoolean chainCalled,
                                       AtomicReference<String> tenantInChain,
                                       AtomicReference<Authentication> authInChain) {
        return (request, response) -> {
            chainCalled.set(true);
            if (tenantInChain != null) {
                tenantInChain.set(TenantContext.getTenantId());
            }
            if (authInChain != null) {
                authInChain.set(SecurityContextHolder.getContext().getAuthentication());
            }
        };
    }
}
