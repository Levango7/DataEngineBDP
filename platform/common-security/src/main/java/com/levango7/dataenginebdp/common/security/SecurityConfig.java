package com.levango7.dataenginebdp.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
 * Spring Security 公共配置（公共安全 Starter 提供的统一实现）。
 *
 * <p>放行 {@code /api/v1/health}、{@code /api/v1/auth/login} 与 {@code /actuator/**}，
 * 其他端点要求认证。注册 {@link JwtAuthFilter} 于
 * {@link UsernamePasswordAuthenticationFilter} 之前。REST API 无状态会话，禁用 CSRF，启用 CORS。</p>
 *
 * <h3>自动装配与退让策略</h3>
 * <p>本类通过 {@code common-security} Starter 的
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 自动装配。</p>
 *
 * <p>当业务模块已自定义 {@link SecurityFilterChain} Bean 时（例如 encaps-layer 的国密/OIDC 特化
 * {@code SecurityConfig}），本配置自动退让（{@link ConditionalOnMissingBean}），避免过滤链冲突。
 * 同理，{@link JwtAuthFilter} 也在缺失时才由本类提供默认实现，允许模块自行覆盖。</p>
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code app.security.jwt.secret}：JWT 签名密钥（HMAC-SHA，至少 32 字节）</li>
 *   <li>{@code app.security.jwt.issuer}：JWT issuer</li>
 *   <li>{@code app.security.cors.allowed-origins}：CORS 允许的源，逗号分隔</li>
 * </ul>
 */
@AutoConfiguration
@EnableWebSecurity
@ConditionalOnMissingBean(SecurityFilterChain.class)
public class SecurityConfig {

    /**
     * 安全过滤链。
     *
     * <p>注意：{@code jwtAuthFilter} 通过方法参数注入而非构造器字段持有——
     * 若在构造器中注入，而默认 {@link JwtAuthFilter} 的 {@code @Bean} 工厂方法又定义于本类，
     * 将形成"创建 SecurityConfig 需要JwtAuthFilter、创建 JwtAuthFilter 又需要 SecurityConfig"
     * 的循环依赖，导致应用无法启动。</p>
     *
     * @param http          HttpSecurity 构建器
     * @param jwtAuthFilter JWT 认证过滤器（容器中存在则注入，缺失时由本类工厂方法提供）
     * @return 安全过滤链
     * @throws Exception 构建过滤链失败
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                                   CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers("/api/v1/auth/login").permitAll()  // 登录端点放行（Keycloak 代理）
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * CORS 配置源。
     *
     * @param allowedOrigins 允许的源，逗号分隔
     * @return CORS 配置源
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
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * 默认 JwtAuthFilter Bean：当容器中不存在 {@link JwtAuthFilter} 类型时提供通用实现。
     *
     * <p>需要特化扩展（如 SM2 国密、OIDC）的模块应自行声明 {@link JwtAuthFilter} 子类或
     * 同类型 Bean，本方法将自动退让。</p>
     *
     * @param secret JWT 签名密钥
     * @param issuer JWT issuer
     * @return 通用 JwtAuthFilter 实例
     */
    @Bean
    @ConditionalOnMissingBean(JwtAuthFilter.class)
    public JwtAuthFilter jwtAuthFilter(@Value("${app.security.jwt.secret}") String secret,
                                       @Value("${app.security.jwt.issuer}") String issuer) {
        return new JwtAuthFilter(secret, issuer);
    }
}