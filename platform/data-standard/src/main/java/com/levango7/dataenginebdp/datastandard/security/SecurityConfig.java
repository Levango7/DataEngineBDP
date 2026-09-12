package com.levango7.dataenginebdp.datastandard.security;

import com.levango7.dataenginebdp.common.security.JwtAuthFilter;
import com.levango7.dataenginebdp.common.security.ratelimit.RateLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 模块级安全配置（覆盖 common-security 默认配置）。
 *
 * <p><b>认证方式分离设计（修复 P2: Bearer token 当 AK 用）</b>：
 * <ul>
 *   <li>{@link ApiKeyAuthFilter} — 处理 {@code X-API-Key} header（API Key 认证）</li>
 *   <li>{@link JwtAuthFilter} — 处理 {@code Authorization: Bearer} header（JWT 认证）</li>
 * </ul>
 *
 * <p>两个过滤器使用不同的 header，互不混淆。
 * 请求可携带任一 header，对应过滤器认证；若均未携带则 401。</p>
 *
 * <p>本配置覆盖 common-security 的 {@code SecurityConfig}（其标注
 * {@code @ConditionalOnMissingBean(SecurityFilterChain)}），避免过滤链冲突。</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 安全过滤链：先速率限制，再 API Key 认证，最后 Bearer JWT 认证。
     *
     * <p>过滤顺序：RateLimit → ApiKeyAuth → JwtAuth → 授权检查。
     * ApiKeyAuth 在 JwtAuth 之前：若 X-API-Key 存在则认证成功并设置上下文，
     * JwtAuth 的 shouldNotFilter 不会重复处理；若 X-API-Key 不存在则跳过，
     * 由 JwtAuth 处理 Bearer token。</p>
     *
     * @param http              HttpSecurity 构建器
     * @param jwtAuthFilter     Bearer JWT 认证过滤器（common-security 提供）
     * @param apiKeyAuthFilter  API Key 认证过滤器（本模块提供）
     * @param rateLimitFilter   速率限制过滤器（common-security 提供）
     * @param corsConfigurationSource CORS 配置源
     * @return 安全过滤链
     * @throws Exception 构建过滤链失败
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthFilter jwtAuthFilter,
                                                   ApiKeyAuthFilter apiKeyAuthFilter,
                                                   RateLimitFilter rateLimitFilter,
                                                   CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * API Key 认证过滤器 Bean。
     */
    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(
            @Value("${app.security.api-key.enabled:false}") boolean enabled,
            @Value("${app.security.api-key.valid-keys:}") String validKeys,
            @Value("${app.security.api-key.tenant-id:api-tenant}") String tenantId) {
        return new ApiKeyAuthFilter(enabled, validKeys, tenantId);
    }

    /**
     * CORS 配置源。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.security.cors.allowed-origins}") String allowedOrigins) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(origins));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization", "Content-Type", "X-Requested-With", "X-API-Key"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}