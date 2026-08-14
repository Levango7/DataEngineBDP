package com.levango7.dataenginebdp.encaps.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>从 {@code Authorization} 头提取 Bearer token，使用 HMAC-SHA 验证签名与过期时间，
 * 解析出 {@code tenantId} 与 {@code sub}(userId) claim，写入
 * {@link SecurityContextHolder} 与 {@link TenantContext}。</p>
 *
 * <p>放行路径：{@code /api/v1/health} 与 {@code /actuator/**}，由
 * {@link SecurityConfig#securityFilterChain} 的 permitAll 规则前置短路，
 * 本过滤器仅在受保护路径生效。</p>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CLAIM_TENANT_ID = "tenantId";

    private final SecretKey signingKey;
    private final String issuer;
    private final OidcJwtDecoder oidcJwtDecoder;

    /**
     * 构造过滤器。
     *
     * @param secret  JWT 签名密钥（HMAC-SHA），至少 256 bit（32 字节）
     * @param issuer  JWT issuer，校验 {@code iss} claim 必须匹配
     * @param oidcJwtDecoder OIDC 解码器（Keycloak RS256；未启用时为回退模式）
     */
    public JwtAuthFilter(@Value("${app.security.jwt.secret}") String secret,
                         @Value("${app.security.jwt.issuer}") String issuer,
                         OidcJwtDecoder oidcJwtDecoder) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.oidcJwtDecoder = oidcJwtDecoder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            sendUnauthorized(response, "missing or non-Bearer Authorization header");
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        try {
            Claims claims;
            String tenantId;
            String userId;

            // 双模式验证：OIDC(Keycloak RS256) 优先；未启用或 OIDC 验证失败时回退 HMAC
            if (oidcJwtDecoder != null && oidcJwtDecoder.isEnabled()) {
                try {
                    var jwt = oidcJwtDecoder.decode(token);
                    tenantId = oidcJwtDecoder.extractTenantId(jwt);
                    userId = jwt.getSubject();
                    log.debug("OIDC 验证通过: sub={}, tenant={}", userId, tenantId);
                    setAuthentication(userId, tenantId);
                    filterChain.doFilter(request, response);
                    return;
                } catch (org.springframework.security.oauth2.jwt.JwtException e) {
                    log.debug("OIDC 验证失败，回退 HMAC: {}", e.getMessage());
                }
            }

            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            tenantId = claims.get(CLAIM_TENANT_ID, String.class);
            userId = claims.getSubject();
            setAuthentication(userId, tenantId);

            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            sendUnauthorized(response, "invalid or expired JWT token");
        } finally {
            // 无论成功失败，请求结束后必须清理 ThreadLocal，避免线程池复用串号。
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 写入租户上下文与 Spring Security 上下文。
     */
    private void setAuthentication(String userId, String tenantId) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(userId);

        List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_USER"));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * 健康检查与 actuator 端点不走 JWT，直接放行。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path != null
                && (path.startsWith("/api/v1/health")
                    || path.startsWith("/actuator"));
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}