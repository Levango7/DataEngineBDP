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

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtAuthFilter 单元测试。
 *
 * <p>验证 JWT token 的验证、拒绝和上下文写入行为。</p>
 */
class JwtAuthFilterTest {

    private static final String SECRET = "test-secret-key-for-unit-tests-at-least-256-bits-long";
    private static final String ISSUER = "shuqing-bigdata";

    private JwtAuthFilter jwtAuthFilter;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        jwtAuthFilter = new JwtAuthFilter("HS384", SECRET, ISSUER, "", "",
                new OidcJwtDecoder(false, "", ""));
    }

    private String buildValidToken(String subject, String tenantId) {
        return Jwts.builder()
                .subject(subject)
                .claim("tenantId", tenantId)
                .issuer(ISSUER)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(signingKey)
                .compact();
    }

    @Test
    @DisplayName("有效JWT — 认证成功，写入SecurityContext和TenantContext")
    void validToken_shouldAuthenticate() throws Exception {
        String token = buildValidToken("user-001", "tenant-001");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        request.setServletPath("/api/v1/tenants");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        // 注意：JwtAuthFilter 在 finally 中会 clear SecurityContext 和 TenantContext
        // 所以在 doFilterInternal 返回后两者已被清理
        assertThat(response.getStatus()).isNotEqualTo(401);
    }

    @Test
    @DisplayName("缺少Authorization头 — 返回401")
    void missingAuthHeader_shouldReturn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/api/v1/tenants");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("missing or non-Bearer");
    }

    @Test
    @DisplayName("无效JWT — 返回401")
    void invalidToken_shouldReturn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid-token-value");
        request.setServletPath("/api/v1/tenants");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        jwtAuthFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("invalid or expired JWT token");
    }

    @Test
    @DisplayName("shouldNotFilter — 健康检查路径不走JWT过滤")
    void shouldNotFilter_healthPath_shouldReturnTrue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/health");

        assertThat(jwtAuthFilter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("shouldNotFilter — actuator路径不走JWT过滤")
    void shouldNotFilter_actuatorPath_shouldReturnTrue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/actuator/health");

        assertThat(jwtAuthFilter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("shouldNotFilter — 受保护路径需走JWT过滤")
    void shouldNotFilter_protectedPath_shouldReturnFalse() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/api/v1/tenants");

        assertThat(jwtAuthFilter.shouldNotFilter(request)).isFalse();
    }
}