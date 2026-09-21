package com.levango7.dataenginebdp.governance.lineage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 安全配置：JWT 鉴权。
 *
 * <p>通过 {@code app.security.jwt.enabled} 控制是否启用 JWT 校验：
 * <ul>
 *   <li>{@code false}（默认）：所有接口匿名可访问，便于本地联调与前端开发</li>
 *   <li>{@code true}：使用 HMAC-SHA256 对称密钥校验 JWT，资源服务器模式</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.security.jwt.secret}")
    private String secret;

    @Value("${app.security.jwt.enabled:true}")
    private boolean jwtEnabled;

    /**
     * 租户上下文过滤器：把已认证身份映射为 TenantContext。
     *
     * <p>本模块自带 SecurityFilterChain，common-security 的 SecurityConfig 会整体退让，
     * 没有任何组件写 TenantContext，必须在此补上（详见 TenantContextFilter 类注释）。
     * 注册在 AuthorizationFilter 之前：晚于认证过滤器（能读到 JWT 主体），
     * 早于授权与 Controller（缺租户即可 fail-closed 返 403，而不是拖到 Controller 里 500）。</p>
     *
     * @param jwtEnabled  是否启用 JWT
     * @param devTenantId 开发态兜底租户（空=未配置，fail-closed）
     * @return 租户上下文过滤器
     */
    @Bean
    public TenantContextFilter tenantContextFilter(
            @Value("${app.security.jwt.enabled:true}") boolean jwtEnabled,
            @Value("${app.security.dev.tenant-id:}") String devTenantId) {
        return new TenantContextFilter(jwtEnabled, devTenantId);
    }

    /**
     * 配置过滤链。
     *
     * @param http HttpSecurity
     * @return 过滤链
     * @throws Exception 配置异常
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           TenantContextFilter tenantContextFilter) throws Exception {
        if (!jwtEnabled) {
            // 开发态：全放行（租户由 TenantContextFilter 按 dev 配置兜底；未配置则 403）
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .addFilterBefore(tenantContextFilter, AuthorizationFilter.class);
            return http.build();
        }
        // 生产态：JWT 校验（租户从已认证 JWT 的 tenantId claim 取）
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(tenantContextFilter, AuthorizationFilter.class);
        return http.build();
    }

    /**
     * JWT 解码器：HMAC-SHA256 对称密钥。
     *
     * @return JwtDecoder
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // 至少 256 bit
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }
}