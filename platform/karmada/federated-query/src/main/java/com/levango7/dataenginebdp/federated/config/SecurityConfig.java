package com.levango7.dataenginebdp.federated.config;

import lombok.extern.slf4j.Slf4j;
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
 * Spring Security 配置：JWT 鉴权。
 *
 * <p>使用 OAuth2 Resource Server 对 Bearer JWT 进行校验，仅放行
 * {@code /actuator/health} 与 {@code /actuator/info}，其余端点要求认证。
 *
 * <p>JWT 密钥通过 {@code app.security.jwt.secret} 注入（HMAC-SHA256 对称密钥，
 * 至少 32 字节）；本地联调可通过环境变量 {@code JWT_SECRET} 注入。
 *
 * <p><b>安全策略（fail-fast）</b>：JWT 密钥长度不足 32 字节时拒绝启动，
 * 不做 padding 补零——padding 会让弱密钥（如 "secret"）通过校验，造成安全假象。
 * 生产部署必须显式提供 ≥32 字节的强密钥。
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** HMAC-SHA256 最小密钥长度（字节）。 */
    private static final int MIN_JWT_SECRET_BYTES = 32;

    @Value("${app.security.jwt.secret}")
    private String secret;

    /**
     * 安全过滤链：JWT 校验 + 端点授权。
     *
     * @param http HttpSecurity
     * @return 过滤链
     * @throws Exception 配置异常
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * JWT 解码器：HMAC-SHA256 对称密钥。
     *
     * <p><b>fail-fast</b>：密钥长度 &lt; 32 字节时抛 {@link IllegalStateException}
     * 阻止应用启动，不做 padding 补零。
     *
     * @return JwtDecoder
     * @throws IllegalStateException 密钥长度不足 32 字节
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException(
                    "JWT 密钥未配置（app.security.jwt.secret 为空）。"
                            + "请配置至少 " + MIN_JWT_SECRET_BYTES + " 字节的强密钥。");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_JWT_SECRET_BYTES) {
            // fail-fast：拒绝启动，不做 padding
            log.error("JWT 密钥长度不足 {} 字节（当前 {} 字节），拒绝启动。"
                    + "padding 弱密钥会造成安全假象，请配置强密钥。",
                    MIN_JWT_SECRET_BYTES, keyBytes.length);
            throw new IllegalStateException(
                    "JWT 密钥长度不足 " + MIN_JWT_SECRET_BYTES + " 字节（当前 "
                            + keyBytes.length + " 字节）：拒绝启动。"
                            + "请配置至少 " + MIN_JWT_SECRET_BYTES + " 字节的强密钥（HMAC-SHA256 要求）。"
                            + "提示：可用 `openssl rand -base64 32` 生成。");
        }
        log.info("JWT 密钥长度 {} 字节，校验通过。", keyBytes.length);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }
}
