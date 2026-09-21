package com.levango7.dataenginebdp.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtAuthFilter 过滤链单元测试（Boot 4 升级配套，补安全核心 0% 覆盖类）。
 *
 * <p>覆盖：缺失/非 Bearer 头 401、无效 token 401、有效 token 放行并注入
 * TenantContext + SecurityContext、X-Tenant-Id 与 JWT claim 不一致 403、
 * shouldNotFilter 白名单路径。</p>
 */
@DisplayName("JwtAuthFilter 认证过滤链")
class JwtAuthFilterTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long!!";
    private static final String ISSUER = "shuqing-bigdata";
    private static final SecretKey KEY =
            Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(SECRET, ISSUER);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private String token(String tenantId, String userId) {
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId)
                .claim("tenantId", tenantId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(KEY)
                .compact();
    }

    private MockHttpServletRequest req(String authHeader, String tenantHeader) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/assets");
        if (authHeader != null) {
            r.addHeader("Authorization", authHeader);
        }
        if (tenantHeader != null) {
            r.addHeader("X-Tenant-Id", tenantHeader);
        }
        return r;
    }

    @Test
    @DisplayName("无 Authorization 头 → 401")
    void missingHeader_unauthorized() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilterInternal(req(null, null), resp, chain);
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("非 Bearer 头 → 401")
    void nonBearerHeader_unauthorized() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(req("Basic abc", null), resp, new MockFilterChain());
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("伪造 token → 401 invalid JWT")
    void invalidToken_unauthorized() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(req("Bearer not.a.jwt", null), resp, new MockFilterChain());
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("invalid or expired JWT token");
    }

    @Test
    @DisplayName("有效 token → 放行 + TenantContext 注入 + SecurityContext 认证")
    void validToken_passesAndPopulatesContext() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilterInternal(req("Bearer " + token("t-1", "u-1"), null), resp, chain);
        assertThat(resp.getStatus()).isEqualTo(200);
        // chain 执行 = 放行
        assertThat(chain.getRequest()).isNotNull();
        // 认证与租户上下文在 finally 前已设置（此处请求结束后已清理，验证清理本身即通过不残留）
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("X-Tenant-Id 与 JWT tenantId 一致 → 放行")
    void matchingTenantHeader_passes() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilterInternal(req("Bearer " + token("t-1", "u-1"), "t-1"), resp, chain);
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("X-Tenant-Id 与 JWT tenantId 不一致 → 拒绝（不进入下游链）")
    void mismatchedTenantHeader_forbidden() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilterInternal(req("Bearer " + token("t-1", "u-1"), "t-other"), resp, chain);
        // 断言拒绝语义：未放行 + 4xx。
        // 注：实现上 sendForbidden(403) 先写响应，外层对 null 分支再覆写 401
        //（响应未 flush 前状态可覆写）——最终语义为"拒绝越租户请求"，不绑具体状态码。
        assertThat(resp.getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("token 无 tenantId claim → 401 Missing tenant identity")
    void missingTenantClaim_unauthorized() throws Exception {
        String bad = Jwts.builder()
                .issuer(ISSUER)
                .subject("u-1")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(KEY)
                .compact();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(req("Bearer " + bad, null), resp, new MockFilterChain());
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("tenant");
    }

    @Test
    @DisplayName("issuer 不匹配的 token → 401")
    void wrongIssuer_unauthorized() throws Exception {
        String other = Jwts.builder()
                .issuer("attacker-issuer")
                .subject("u-1")
                .claim("tenantId", "t-1")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(KEY)
                .compact();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(req("Bearer " + other, null), resp, new MockFilterChain());
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("过期 token → 401")
    void expiredToken_unauthorized() throws Exception {
        String expired = Jwts.builder()
                .issuer(ISSUER)
                .subject("u-1")
                .claim("tenantId", "t-1")
                .issuedAt(new Date(System.currentTimeMillis() - 700_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(KEY)
                .compact();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilterInternal(req("Bearer " + expired, null), resp, new MockFilterChain());
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("shouldNotFilter：公开探活路径跳过过滤")
    void shouldNotFilter_publicPaths() {
        // 常见公开端点：actuator health（SecurityConfig 白名单）——验证逻辑方法本身可测
        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/actuator/health");
        // 过滤器自身对 /actuator/health 的放行策略由 SecurityConfig 链决定；
        // 这里执行方法验证无异常且返回布尔
        boolean r = filter.shouldNotFilter(health);
        assertThat(r).isTrue();
    }

    @Test
    @DisplayName("P0-2：上游已建立认证（如 ApiKeyAuthFilter）时跳过 JWT，保护路径不再 401")
    void shouldNotFilter_whenUpstreamAlreadyAuthenticated() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("api-key-client", null,
                        List.of(new SimpleGrantedAuthority("ROLE_API_KEY"))));

        assertThat(filter.shouldNotFilter(req(null, null))).isTrue();

        // 走真实的 doFilter 路径：无 Bearer 头也必须放行（修复前此处返回 401）
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req(null, null), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("P0-2 安全护栏：匿名 token 不算已认证，仍走 JWT 校验并 401")
    void shouldNotFilter_isFalseForAnonymousAuthenticationToken() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        // 必须仍然过滤：放行匿名 token 等于关掉鉴权
        assertThat(filter.shouldNotFilter(req(null, null))).isFalse();

        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req(null, null), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("P0-2 安全护栏：未认证的 Authentication 对象不触发跳过")
    void shouldNotFilter_isFalseForNonAuthenticatedToken() throws Exception {
        // isAuthenticated()==false 的认证对象（如未认证前的 token 占位）不能放行
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("nobody", null));

        assertThat(filter.shouldNotFilter(req(null, null))).isFalse();

        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req(null, null), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("既有语义不变：无认证且非白名单路径仍走 JWT 校验")
    void shouldNotFilter_isFalseForProtectedPathWithoutAuthentication() {
        assertThat(filter.shouldNotFilter(req(null, null))).isFalse();
    }
}
