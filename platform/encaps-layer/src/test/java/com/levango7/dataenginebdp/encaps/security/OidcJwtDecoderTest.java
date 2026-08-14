package com.levango7.dataenginebdp.encaps.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OidcJwtDecoder 单元测试。
 *
 * <p>验证未启用时回退、tenant 提取逻辑；真实 JWKS 验证依赖 Keycloak 实例（集成测试）。</p>
 */
class OidcJwtDecoderTest {

    @Test
    void disabledByDefault_returnsNullDelegate() {
        OidcJwtDecoder decoder = new OidcJwtDecoder(false, "", "");
        assertThat(decoder.isEnabled()).isFalse();
        assertThatThrownBy(() -> decoder.decode("any.token.here"))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    void extractTenantId_prefersTenantIdClaim() {
        OidcJwtDecoder decoder = new OidcJwtDecoder(false, "", "");
        Jwt jwt = jwtWithClaims(Map.of("tenantId", "tenant_a"));
        assertThat(decoder.extractTenantId(jwt)).isEqualTo("tenant_a");
    }

    @Test
    void extractTenantId_fallsBackToTenantUnderscoreClaim() {
        OidcJwtDecoder decoder = new OidcJwtDecoder(false, "", "");
        Jwt jwt = jwtWithClaims(Map.of("tenant_id", "tenant_b"));
        assertThat(decoder.extractTenantId(jwt)).isEqualTo("tenant_b");
    }

    @Test
    void extractTenantId_fallsBackToSingleAudience() {
        OidcJwtDecoder decoder = new OidcJwtDecoder(false, "", "");
        Jwt jwt = jwtWithAudience(List.of("realm-tenant-c"));
        assertThat(decoder.extractTenantId(jwt)).isEqualTo("realm-tenant-c");
    }

    @Test
    void extractTenantId_returnsNullWhenUnknown() {
        OidcJwtDecoder decoder = new OidcJwtDecoder(false, "", "");
        Jwt jwt = jwtWithAudience(List.of("aud1", "aud2")); // 多 audience 无法确定
        assertThat(decoder.extractTenantId(jwt)).isNull();
    }

    private Jwt jwtWithClaims(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-1")
                .issuer("https://keycloak/realms/shuqing")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claims(c -> c.putAll(claims))
                .build();
    }

    private Jwt jwtWithAudience(List<String> aud) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-1")
                .issuer("https://keycloak/realms/shuqing")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .audience(aud)
                .build();
    }
}
