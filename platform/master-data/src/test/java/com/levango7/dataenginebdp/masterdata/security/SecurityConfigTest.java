package com.levango7.dataenginebdp.masterdata.security;

import com.levango7.dataenginebdp.common.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecurityConfig 中无需 Servlet 容器即可验证的两个 Bean 工厂方法测试。
 *
 * <ul>
 *   <li>{@code apiKeyAuthFilter(...)}：按配置构造过滤器，且配置的 key / 租户真正生效</li>
 *   <li>{@code corsConfigurationSource(...)}：origin 列表按逗号切分并 trim，
 *       允许的方法 / 头 / 凭据 / 预检缓存均按约定配置，并注册在 {@code /**} 上</li>
 * </ul>
 *
 * <p>过滤链装配（{@code securityFilterChain}）需要完整 Servlet 容器，
 * 由专门的 Spring Boot 上下文测试覆盖。</p>
 */
@DisplayName("SecurityConfig Bean 装配")
class SecurityConfigTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("apiKeyAuthFilter: 按配置构造，配置的 key 与租户在过滤时真正生效")
    void shouldCreateApiKeyAuthFilterWithConfiguredValues() throws Exception {
        ApiKeyAuthFilter filter = securityConfig.apiKeyAuthFilter(true, "key-1,key-2", "tenant-A");

        assertThat(filter).isNotNull();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/master-data");
        request.setServletPath("/api/v1/master-data");
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> tenantInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                tenantInChain.set(TenantContext.getTenantId()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(tenantInChain.get()).isEqualTo("tenant-A");
    }

    @Test
    @DisplayName("corsConfigurationSource: origin 按逗号切分并 trim，注册在 /** 上")
    void shouldParseCorsOrigins() {
        CorsConfigurationSource source =
                securityConfig.corsConfigurationSource("http://a.example, http://b.example , ");

        Map<String, CorsConfiguration> configurations =
                ((UrlBasedCorsConfigurationSource) source).getCorsConfigurations();
        assertThat(configurations).containsKey("/**");
        CorsConfiguration configuration = configurations.get("/**");
        assertThat(configuration.getAllowedOrigins())
                .containsExactly("http://a.example", "http://b.example");
        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.getMaxAge()).isEqualTo(3600L);
    }

    @Test
    @DisplayName("corsConfigurationSource: 允许的方法与请求头包含业务所需项")
    void shouldConfigureCorsMethodsAndHeaders() {
        CorsConfigurationSource source =
                securityConfig.corsConfigurationSource("http://localhost:5173");

        CorsConfiguration configuration =
                ((UrlBasedCorsConfigurationSource) source).getCorsConfigurations().get("/**");
        assertThat(configuration.getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
        assertThat(configuration.getAllowedHeaders())
                .containsExactlyInAnyOrder("Authorization", "Content-Type",
                        "X-Requested-With", "X-API-Key");
    }
}
