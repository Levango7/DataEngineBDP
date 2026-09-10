package com.levango7.dataenginebdp.encaps.security;

import com.levango7.dataenginebdp.common.security.TenantContext;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JwtAuthFilter OIDC 认证降级修复（P0）专项测试。
 *
 * <p>验证安全修复：当 OIDC 配置启用（{@code app.security.oidc.enabled=true}）时，
 * OIDC 验证失败必须直接返回 401 Unauthorized，禁止回退到 HMAC 验证。
 *
 * <p>测试矩阵：
 * <ul>
 *   <li>OIDC 启用 + OIDC 验证失败 → 401（不回退 HMAC，即使 HMAC token 有效）</li>
 *   <li>OIDC 启用 + OIDC 验证成功 → 认证通过</li>
 *   <li>OIDC 未启用 + HMAC 验证成功 → 认证通过（向后兼容）</li>
 *   <li>OIDC 启用 + 缺少 tenantId → 401</li>
 * </ul>
 *
 * <p>来源：2026-09-10-spring-boot-backend-security-antipatterns（认证降级检测）
 */
class JwtAuthFilterOidcSecurityTest {

    private static final String SECRET = "test-secret-key-for-unit-tests-at-least-256-bits-long";
    private static final String ISSUER = "shuqing-bigdata";
    private static final String OIDC_ISSUER = "https://keycloak/realms/shuqing";

    private SecretKey hmacSigningKey;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        hmacSigningKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构造一个对 HMAC 验证有效的 JWT token。
     * <p>关键：此 token 在 HMAC 验证下会通过，用于验证 OIDC 启用时不会回退到 HMAC。
     */
    private String buildValidHmacToken(String subject, String tenantId) {
        return Jwts.builder()
                .subject(subject)
                .claim("tenantId", tenantId)
                .issuer(ISSUER)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(hmacSigningKey)
                .compact();
    }

    /**
     * 构造一个 OIDC 验证通过的 Jwt 对象（模拟 Keycloak RS256 token）。
     */
    private Jwt buildValidOidcJwt(String subject, String tenantId) {
        return Jwt.withTokenValue("oidc-token-value")
                .header("alg", "RS256")
                .header("typ", "JWT")
                .subject(subject)
                .issuer(OIDC_ISSUER)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("tenant_id", tenantId)
                .claim("realm_access", Map.of("roles", List.of("user", "admin")))
                .build();
    }

    /**
     * 创建 OIDC 启用但 decode 抛异常的 mock（模拟 OIDC 验证失败）。
     */
    private OidcJwtDecoder mockOidcEnabledButFails() {
        OidcJwtDecoder decoder = mock(OidcJwtDecoder.class);
        when(decoder.isEnabled()).thenReturn(true);
        when(decoder.decode(anyString())).thenThrow(
                new JwtException("OIDC 签名验证失败：无效的 RS256 token"));
        return decoder;
    }

    /**
     * 创建 OIDC 启用且 decode 成功的 mock（模拟 OIDC 验证通过）。
     */
    private OidcJwtDecoder mockOidcEnabledAndSucceeds(Jwt jwt, String tenantId) {
        OidcJwtDecoder decoder = mock(OidcJwtDecoder.class);
        when(decoder.isEnabled()).thenReturn(true);
        when(decoder.decode(anyString())).thenReturn(jwt);
        when(decoder.extractTenantId(jwt)).thenReturn(tenantId);
        return decoder;
    }

    @Test
    @DisplayName("P0修复：OIDC启用 + OIDC验证失败 → 返回401，禁止回退HMAC（即使HMAC token有效）")
    void oidcEnabled_oidcValidationFails_shouldReturn401AndNotFallbackToHmac() throws Exception {
        // 构造一个对 HMAC 验证有效的 token——如果回退到 HMAC，请求会通过
        // 修复后：OIDC 启用时验证失败必须直接返回 401
        String validHmacToken = buildValidHmacToken("user-001", "tenant-001");

        OidcJwtDecoder oidcDecoder = mockOidcEnabledButFails();
        JwtAuthFilter filter = new JwtAuthFilter("HS384", SECRET, ISSUER, "", "", oidcDecoder);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + validHmacToken);
        request.setServletPath("/api/v1/tenants");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        // 必须返回 401，不能因为 HMAC token 有效而放行
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("OIDC token validation failed");
        // 过滤器链不应继续执行（未授权请求被拦截）
        assertThat(filterChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("P0修复：OIDC启用 + OIDC验证成功 → 认证通过，请求放行")
    void oidcEnabled_oidcValidationSucceeds_shouldAuthenticateAndProceed() throws Exception {
        Jwt validOidcJwt = buildValidOidcJwt("user-002", "tenant-002");
        OidcJwtDecoder oidcDecoder = mockOidcEnabledAndSucceeds(validOidcJwt, "tenant-002");
        JwtAuthFilter filter = new JwtAuthFilter("HS384", SECRET, ISSUER, "", "", oidcDecoder);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-oidc-rs256-token");
        request.setServletPath("/api/v1/tenants");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        // 认证通过，不应返回 401
        assertThat(response.getStatus()).isNotEqualTo(401);
        // 过滤器链应继续执行（请求被放行）
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("向后兼容：OIDC未启用 + HMAC验证成功 → 认证通过")
    void oidcDisabled_validHmacToken_shouldAuthenticateViaHmac() throws Exception {
        String validHmacToken = buildValidHmacToken("user-003", "tenant-003");

        // OIDC 未启用（与生产开发环境一致）
        OidcJwtDecoder oidcDecoder = new OidcJwtDecoder(false, "", "");
        JwtAuthFilter filter = new JwtAuthFilter("HS384", SECRET, ISSUER, "", "", oidcDecoder);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + validHmacToken);
        request.setServletPath("/api/v1/tenants");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        // HMAC 验证通过，不应返回 401
        assertThat(response.getStatus()).isNotEqualTo(401);
        // 过滤器链应继续执行
        assertThat(filterChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("P0修复：OIDC启用 + 无效token（OIDC和HMAC都失败）→ 返回401")
    void oidcEnabled_completelyInvalidToken_shouldReturn401() throws Exception {
        OidcJwtDecoder oidcDecoder = mockOidcEnabledButFails();
        JwtAuthFilter filter = new JwtAuthFilter("HS384", SECRET, ISSUER, "", "", oidcDecoder);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer completely-invalid-token");
        request.setServletPath("/api/v1/tenants");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(filterChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("P0修复：OIDC启用 + 验证失败 → 错误信息明确指向OIDC失败而非HMAC")
    void oidcEnabled_oidcValidationFails_errorMessageShouldMentionOidcNotHmac() throws Exception {
        String validHmacToken = buildValidHmacToken("user-004", "tenant-004");

        OidcJwtDecoder oidcDecoder = mockOidcEnabledButFails();
        JwtAuthFilter filter = new JwtAuthFilter("HS384", SECRET, ISSUER, "", "", oidcDecoder);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + validHmacToken);
        request.setServletPath("/api/v1/tenants");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        // 错误信息应明确说明是 OIDC 验证失败，而非通用的 JWT 失败
        assertThat(response.getContentAsString()).contains("OIDC token validation failed");
        // 不应包含"invalid or expired JWT token"（那是 HMAC 失败的信息）
        assertThat(response.getContentAsString()).doesNotContain("invalid or expired JWT token");
    }

    @Test
    @DisplayName("P0修复：OIDC启用 + 验证成功但缺少tenantId → 返回401")
    void oidcEnabled_validOidcTokenButMissingTenantId_shouldReturn401() throws Exception {
        // OIDC 验证通过但 tenantId 为 null
        Jwt jwtWithoutTenant = Jwt.withTokenValue("oidc-token-no-tenant")
                .header("alg", "RS256")
                .subject("user-005")
                .issuer(OIDC_ISSUER)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        OidcJwtDecoder oidcDecoder = mock(OidcJwtDecoder.class);
        when(oidcDecoder.isEnabled()).thenReturn(true);
        when(oidcDecoder.decode(anyString())).thenReturn(jwtWithoutTenant);
        when(oidcDecoder.extractTenantId(jwtWithoutTenant)).thenReturn(null);

        JwtAuthFilter filter = new JwtAuthFilter("HS384", SECRET, ISSUER, "", "", oidcDecoder);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer oidc-token-no-tenant");
        request.setServletPath("/api/v1/tenants");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Missing tenant identity");
    }
}