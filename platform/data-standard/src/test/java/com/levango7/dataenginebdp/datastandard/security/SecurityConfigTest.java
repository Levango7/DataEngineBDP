package com.levango7.dataenginebdp.datastandard.security;

import com.levango7.dataenginebdp.common.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecurityConfig 中两个可独立构造的 Bean 的单元测试。
 *
 * <p>corsConfigurationSource 验证逗号分隔的白名单解析与允许的方法 / 头（含 X-API-Key）；
 * apiKeyAuthFilter 验证配置项被正确注入过滤器（白名单解析与租户绑定生效）。</p>
 *
 * <p>注：securityFilterChain 需要 HttpSecurity 实例，属于 Spring 容器装配范畴，
 * 不在本单元测试覆盖范围内。</p>
 */
@DisplayName("SecurityConfig CORS 与 ApiKeyAuthFilter Bean 测试")
class SecurityConfigTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("corsConfigurationSource — 解析逗号分隔白名单，允许 X-API-Key 头")
    void shouldParseAllowedOrigins() {
        SecurityConfig config = new SecurityConfig();

        CorsConfigurationSource source =
                config.corsConfigurationSource("http://localhost:5173, http://ops.example.com , ");

        CorsConfiguration configuration = source.getCorsConfiguration(new MockHttpServletRequest());
        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins())
                .containsExactly("http://localhost:5173", "http://ops.example.com");
        assertThat(configuration.getAllowedMethods())
                .contains("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
        assertThat(configuration.getAllowedHeaders())
                .contains("Authorization", "Content-Type", "X-Requested-With", "X-API-Key");
        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.getMaxAge()).isEqualTo(3600L);
    }

    @Test
    @DisplayName("apiKeyAuthFilter — 注入的启用开关与白名单在认证时生效")
    void shouldProduceWorkingApiKeyFilter() throws Exception {
        SecurityConfig config = new SecurityConfig();

        ApiKeyAuthFilter filter = config.apiKeyAuthFilter(true, "key-a, key-b", "tenant-b");

        assertThat(filter).isNotNull();
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(apiKeyRequest("key-b"), accepted, new NoopChain());
        assertThat(accepted.getStatus()).isEqualTo(200);
        assertThat(TenantContext.getTenantId()).isNull();

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        NoopChain chain = new NoopChain();
        filter.doFilter(apiKeyRequest("key-c"), rejected, chain);
        assertThat(rejected.getStatus()).isEqualTo(401);
        assertThat(chain.invoked).isFalse();
    }

    @Test
    @DisplayName("apiKeyAuthFilter — 未启用时即使 Key 非法也透传")
    void shouldPassThroughWhenFilterDisabled() throws Exception {
        SecurityConfig config = new SecurityConfig();

        ApiKeyAuthFilter filter = config.apiKeyAuthFilter(false, "key-a", "tenant-b");

        MockHttpServletResponse response = new MockHttpServletResponse();
        NoopChain chain = new NoopChain();
        filter.doFilter(apiKeyRequest("unknown"), response, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest apiKeyRequest(String apiKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/standards");
        request.setServletPath("/api/v1/standards");
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, apiKey);
        return request;
    }

    /** 空实现的过滤链，仅用于断言链路是否被放行。 */
    private static final class NoopChain implements FilterChain {

        private boolean invoked;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                throws IOException, ServletException {
            invoked = true;
        }
    }
}
