package com.shuqing.bigdata.governance.collector.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link JwtAuthFilter} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    private static final String SECRET = "test-secret-key-for-unit-tests-at-least-256-bits-long";
    private static final String ISSUER = "shuqing-bigdata";

    private JwtAuthFilter filter;
    private SecretKey signingKey;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(SECRET, ISSUER);
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("shouldNotFilter 对 /api/v1/health 应返回 true")
    void shouldNotFilter_healthPath() {
        when(request.getServletPath()).thenReturn("/api/v1/health");
        assertTrue(filter.shouldNotFilter(request));
    }

    @Test
    @DisplayName("shouldNotFilter 对 /actuator/prometheus 应返回 true")
    void shouldNotFilter_actuatorPath() {
        when(request.getServletPath()).thenReturn("/actuator/prometheus");
        assertTrue(filter.shouldNotFilter(request));
    }

    @Test
    @DisplayName("shouldNotFilter 对受保护路径应返回 false")
    void shouldNotFilter_protectedPath() {
        when(request.getServletPath()).thenReturn("/api/v1/metadata/sources");
        assertFalse(filter.shouldNotFilter(request));
    }

    @Test
    @DisplayName("有效 JWT 应放行并设置上下文")
    void doFilterInternal_validJwt() throws Exception {
        String token = Jwts.builder()
                .subject("user-1")
                .issuer(ISSUER)
                .claim("tenantId", "tenant-1")
                .signWith(signingKey)
                .compact();

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);


        // 在 filterChain.doFilter 执行时验证 authentication 已设置
        doAnswer(invocation -> {
            assertNotNull(SecurityContextHolder.getContext().getAuthentication());
            assertEquals("user-1", SecurityContextHolder.getContext().getAuthentication().getName());
            assertEquals("tenant-1", TenantContext.getTenantId());
            assertEquals("user-1", TenantContext.getUserId());
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        // finally 块执行后上下文应被清理
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("缺失 Authorization 头应返回 401")
    void doFilterInternal_missingAuthHeader() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(any(), any());
        assertTrue(sw.toString().contains("missing"));
    }

    @Test
    @DisplayName("非 Bearer 前缀应返回 401")
    void doFilterInternal_nonBearerPrefix() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Basic abc123");
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("无效 JWT 应返回 401")
    void doFilterInternal_invalidJwt() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid.token.here");
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(any(), any());
        assertTrue(sw.toString().contains("invalid"));
    }

    @Test
    @DisplayName("错误 issuer 应返回 401")
    void doFilterInternal_wrongIssuer() throws Exception {
        String token = Jwts.builder()
                .subject("user-1")
                .issuer("wrong-issuer")
                .claim("tenantId", "tenant-1")
                .signWith(signingKey)
                .compact();

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(any(), any());
    }
}