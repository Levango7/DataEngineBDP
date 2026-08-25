package com.shuqing.bigdata.common.health.security;

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
 * JWT 认证过滤器（公共版本）。
 *
 * <p>从 {@code Authorization} 头提取 Bearer token，使用 HMAC-SHA 验证签名与过期时间，
 * 解析出 {@code tenantId} 与 {@code sub}(userId) claim，写入
 * {@link SecurityContextHolder} 与 {@link TenantContext}，实现租户隔离。</p>
 *
 * <p>放行路径：{@code /api/v1/health} 与 {@code /actuator/**}，由
 * 各模块 {@code SecurityConfig#securityFilterChain} 的 permitAll 规则前置短路，
 * 本过滤器仅在受保护路径生效。</p>
 *
 * <p><b>使用说明</b>：本类由 common-health 共享模块提供，旨在替代散布在
 * bigdata/encaps、finops、infra/orchestrator、ruleengine、encaps、sqlgateway 等
 * 7 个模块 {@code security} 包下的同名副本，消除维护漂移。各模块切换步骤：</p>
 * <ol>
 *   <li>确保模块 {@code build.gradle} 已包含 {@code implementation project(':common-health')}；</li>
 *   <li>在模块 {@code SecurityConfig} 中注入
 *       {@code com.shuqing.bigdata.common.health.security.JwtAuthFilter}（本类）；</li>
 *   <li>将业务代码中对 {@code TenantContext} 的引用改为
 *       {@code com.shuqing.bigdata.common.health.security.TenantContext}；</li>
 *   <li>删除模块本地 {@code security/JwtAuthFilter.java} 与 {@code security/TenantContext.java} 副本。</li>
 * </ol>
 * <p>在未完成全量切换前，本地副本仍可继续工作，二者互不干扰（包名不同）。</p>
 *
 * <p><b>配置项</b>（通过 Spring 属性注入）：</p>
 * <ul>
 *   <li>{@code app.security.jwt.secret} — JWT 签名密钥（HMAC-SHA），至少 256 bit（32 字节）</li>
 *   <li>{@code app.security.jwt.issuer} — JWT issuer，校验 {@code iss} claim 必须匹配</li>
 * </ul>
 *
 * @since 1.1.0
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CLAIM_TENANT_ID = "tenantId";

    private final SecretKey signingKey;
    private final String issuer;

    /**
     * 构造过滤器。
     *
     * @param secret  JWT 签名密钥（HMAC-SHA），至少 256 bit（32 字节）
     * @param issuer  JWT issuer，校验 {@code iss} claim 必须匹配
     */
    public JwtAuthFilter(@Value("${app.security.jwt.secret}") String secret,
                         @Value("${app.security.jwt.issuer}") String issuer) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
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
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String tenantId = claims.get(CLAIM_TENANT_ID, String.class);
            String userId = claims.getSubject();

            // 写入租户上下文（ThreadLocal），供业务层获取。
            TenantContext.setTenantId(tenantId);
            TenantContext.setUserId(userId);

            // 写入 Spring Security 上下文，使 @PreAuthorize 等可用。
            List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                    new SimpleGrantedAuthority("ROLE_USER"));
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

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