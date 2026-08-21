package com.shuqing.bigdata.governance.lineage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

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

    @Value("${app.security.jwt.enabled:false}")
    private boolean jwtEnabled;

    /**
     * 配置过滤链。
     *
     * @param http HttpSecurity
     * @return 过滤链
     * @throws Exception 配置异常
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        if (!jwtEnabled) {
            // 开发态：全放行
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
            return http.build();
        }
        // 生产态：JWT 校验
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
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