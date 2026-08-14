package com.levango7.dataenginebdp.encaps.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Keycloak OIDC JWT 解码器（RS256 + JWKS 轮换）。
 *
 * <p>通过 {@code app.security.oidc.enabled=true} 开启后，
 * {@link JwtAuthFilter} 将优先使用本解码器验证 Keycloak 签发的
 * RS256 token（issuer 校验 + JWKS 公钥签名校验），
 * 未开启时回退到 HMAC 自签验证（开发环境）。
 *
 * <p>tenantId 提取顺序：自定义 claim {@code tenant_id} → {@code tenantId} →
 * JWT audience（单值时）→ 空（由业务层兜底）。
 */
@Component
public class OidcJwtDecoder {

    private static final Logger log = LoggerFactory.getLogger(OidcJwtDecoder.class);

    private final JwtDecoder delegate;
    private final boolean enabled;
    private final String issuerUri;

    /**
     * 构造 OIDC 解码器。
     *
     * @param enabled   是否启用 OIDC（false 时返回 null，调用方回退 HMAC）
     * @param jwksUri   Keycloak JWKS 端点（如 …/protocol/openid-connect/certs）
     * @param issuerUri Keycloak issuer（如 …/realms/shuqing）
     */
    public OidcJwtDecoder(
            @Value("${app.security.oidc.enabled:false}") boolean enabled,
            @Value("${app.security.oidc.jwks-uri:}") String jwksUri,
            @Value("${app.security.oidc.issuer-uri:}") String issuerUri) {
        this.enabled = enabled;
        this.issuerUri = issuerUri;
        if (enabled && jwksUri != null && !jwksUri.isBlank()) {
            this.delegate = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
            log.info("OIDC 解码器已启用: jwks={}, issuer={}", jwksUri, issuerUri);
        } else {
            this.delegate = null;
            log.info("OIDC 解码器未启用（app.security.oidc.enabled=false），回退 HMAC 验证");
        }
    }

    /**
     * 是否启用 OIDC。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 验证 token；失败抛 {@link JwtException}。
     *
     * @param token Bearer token（不含前缀）
     * @return 解码后的 JWT
     */
    public Jwt decode(String token) {
        if (delegate == null) {
            throw new JwtException("OIDC 解码器未启用");
        }
        return delegate.decode(token);
    }

    /**
     * 从 OIDC JWT 提取租户 ID。
     *
     * @param jwt 已解码 JWT
     * @return 租户 ID；无法确定时返回 null
     */
    public String extractTenantId(Jwt jwt) {
        Map<String, Object> claims = jwt.getClaims();
        Object tenantId = claims.getOrDefault("tenant_id", claims.get("tenantId"));
        if (tenantId != null && !tenantId.toString().isBlank()) {
            return tenantId.toString();
        }
        // 单 audience 时以其作为租户标识（Keycloak 常见做法：realm 即租户）
        var aud = jwt.getAudience();
        if (aud != null && aud.size() == 1) {
            return aud.iterator().next();
        }
        return null;
    }

    /**
     * 获取配置的 issuer（供过滤器校验 iss）。
     */
    public String getIssuerUri() {
        return issuerUri;
    }
}
