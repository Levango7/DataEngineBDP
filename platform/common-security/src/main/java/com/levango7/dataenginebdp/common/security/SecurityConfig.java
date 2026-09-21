package com.levango7.dataenginebdp.common.security;

import com.levango7.dataenginebdp.common.security.ratelimit.RateLimitFilter;
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
 * <p>放行 {@code /api/v1/health}、{@code /api/v1/auth/login} 与 {@code /actuator/health}、
 * {@code /actuator/info}，其他端点（含其余 actuator 敏感端点）要求认证。注册 {@link JwtAuthFilter} 于
 * {@link UsernamePasswordAuthenticationFilter} 之前。REST API 无状态会话，禁用 CSRF，启用 CORS。</p>
 *
 * <h3>自动装配与退让策略</h3>
 * <p>本类通过 {@code common-security} Starter 的
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 自动装配。</p>
 *
 * <p>当业务模块已自定义 {@link SecurityFilterChain} Bean 时（例如 encaps-layer 的国密/OIDC 特化
 * {@code SecurityConfig}），本配置自动退让（{@link ConditionalOnMissingBean}），避免过滤链冲突。</p>
 *
 * <p><b>默认 {@link JwtAuthFilter} Bean 不在本类中</b>：它已抽出到
 * {@link JwtAuthFilterAutoConfiguration}。本类的类级守卫会让"整个类"退让，若默认
 * JwtAuthFilter 仍定义在此，业务模块自定义过滤链后就会拿不到它——而这些模块的过滤链恰恰要注入
 * 该 Bean，结果是 {@code No qualifying bean of type 'JwtAuthFilter'}、应用无法启动（P0）。
 * 默认 Bean 工厂与过滤链必须解耦。</p>
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code app.security.jwt.secret}：JWT 签名密钥（HMAC-SHA，至少 32 字节）</li>
 *   <li>{@code app.security.jwt.issuer}：JWT issuer</li>
 *   <li>{@code app.security.cors.allowed-origins}：CORS 允许的源，逗号分隔</li>
 * </ul>
 */
// Spring Boot 4.x：security servlet 自动配置移出 spring-boot-autoconfigure 主包
// （原 org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
// 的排序锚点不再随主包传递），改为无锚点 @AutoConfiguration —— 本配置自带
// @ConditionalOnMissingBean(SecurityFilterChain) 守卫，加载顺序不构成约束。
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
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   RateLimitFilter rateLimitFilter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers("/api/v1/auth/login").permitAll()  // 登录端点放行（Keycloak 代理）
                        // actuator 仅放行健康检查子集，敏感端点（env/heapdump/threaddump/loggers 等）要求认证
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        .anyRequest().authenticated())
                // 速率限制（C1）先于认证执行：匿名爆破按 IP 拦截，无需等 JWT 解析
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
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
}