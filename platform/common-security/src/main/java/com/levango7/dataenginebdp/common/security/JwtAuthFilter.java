package com.levango7.dataenginebdp.common.security;

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

import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * JWT 认证过滤器（公共安全 Starter 提供的统一实现）。
 *
 * <p>从 {@code Authorization} 头提取 Bearer token，使用 HMAC-SHA 验证签名与过期时间，
 * 解析出 {@code tenantId} 与 {@code sub}(userId) claim，写入
 * {@link SecurityContextHolder} 与 {@link TenantContext}。</p>
 *
 * <h3>多租户隔离校验</h3>
 * <p>读取 {@code X-Tenant-Id} 请求头并与 JWT claim 中的 {@code tenantId} 比对：
 * <ul>
 *   <li>header 存在且与 JWT claim 一致 → 使用 header 值（即 JWT 值）</li>
 *   <li>header 存在但与 JWT claim 不一致 → 越权，返回 403</li>
 *   <li>header 不存在 → 向后兼容，使用 JWT claim 中的 tenantId</li>
 * </ul>
 *
 * <p>放行路径：{@code /api/v1/health}、{@code /api/v1/auth/login} 与 {@code /actuator/**}，
 * 由 {@link SecurityConfig#securityFilterChain} 的 permitAll 规则前置短路，
 * 本过滤器仅在受保护路径生效。</p>
 *
 * <p>本类由 {@code common-security} Starter 自动装配，各业务模块无需再各自复制。
 * 需要特化扩展（如 SM2 国密、OIDC）的模块可在本类基础上自行实现并覆盖 Bean。</p>
 */

public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CLAIM_TENANT_ID = "tenantId";
    private static final String TENANT_HEADER = "X-Tenant-Id";

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
    public void doFilterInternal(HttpServletRequest request,
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

            // 从 JWT 提取的租户ID（用户所属租户）
            String jwtTenantId = claims.get(CLAIM_TENANT_ID, String.class);
            String userId = claims.getSubject();

            // 多租户隔离校验：X-Tenant-Id header 必须与 JWT claim 一致（若提供）
            String effectiveTenantId = resolveTenantWithHeaderCheck(request, response, jwtTenantId, userId);
            if (effectiveTenantId == null) {
                // 如果response未提交（header校验未写403），说明是jwtTenantId为null
                if (!response.isCommitted()) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"Missing tenant identity\"}");
                }
                return;
            }
            setAuthentication(userId, effectiveTenantId, extractAuthorities(claims));

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
    private void setAuthentication(String userId, String tenantId, List<SimpleGrantedAuthority> authorities) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(userId);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * 从 JWT claims 读取角色信息（realm_access.roles），构造 Spring Security 权限列表。
     *
     * <p>修复 M-F-14：不再硬编码 ROLE_USER，而是从 Keycloak 标准 claim
     * {@code realm_access.roles} 读取实际角色。若无角色信息则回退 ROLE_USER。</p>
     *
     * @param claims JWT claims（Map 形式）
     * @return 权限列表（至少包含一个角色）
     */
    private List<SimpleGrantedAuthority> extractAuthorities(Map<String, Object> claims) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        Object realmAccess = claims.get("realm_access");
        if (realmAccess instanceof Map<?, ?> map) {
            Object roles = map.get("roles");
            if (roles instanceof List<?> roleList) {
                for (Object role : roleList) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toString()));
                }
            }
        }
        if (authorities.isEmpty()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        }
        return authorities;
    }

    /**
     * 多租户隔离校验：读取 {@code X-Tenant-Id} header 并与 JWT claim 中的 tenantId 比对。
     *
     * <p>规则（修复 M-F-05）：
     * <ul>
     *   <li>header 存在且与 JWT claim 一致 → 使用 header 值（即 JWT 值）</li>
     *   <li>header 存在但与 JWT claim 不一致 → 越权，返回 null 并写入 403 响应</li>
     *   <li>header 不存在 → 向后兼容，使用 JWT claim 中的 tenantId</li>
     * </ul>
     *
     * @param request      HTTP 请求（读取 header）
     * @param response     HTTP 响应（校验失败时写入 403）
     * @param jwtTenantId  JWT claim 中的 tenantId（用户所属租户）
     * @param userId       用户 ID（仅用于审计日志）
     * @return 生效的 tenantId；若 header 校验失败返回 null（调用方应直接 return）
     */
    private String resolveTenantWithHeaderCheck(HttpServletRequest request,
                                                HttpServletResponse response,
                                                String jwtTenantId,
                                                String userId) throws IOException {
        String headerTenantId = request.getHeader(TENANT_HEADER);
        if (headerTenantId != null && !headerTenantId.isBlank()) {
            if (!headerTenantId.equals(jwtTenantId)) {
                log.warn("租户隔离校验失败: jwtTenant={}, headerTenant={}, user={}",
                        jwtTenantId, headerTenantId, userId);
                sendForbidden(response, "tenant mismatch: X-Tenant-Id does not match JWT claim");
                return null;
            }
            return headerTenantId;
        }
        // header 不存在：向后兼容，使用 JWT 中的 tenantId
        return jwtTenantId;
    }

    /**
     * 健康检查、登录与 actuator 端点不走 JWT，直接放行。
     *
     * <p>注：本方法为 {@code public} 以便跨模块单元测试直接调用验证（OncePerRequestFilter
     * 父类声明为 protected，子类覆盖时放宽访问控制符合 Java 规范）。</p>
     */
    @Override
    public boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path != null
                && (path.equals("/api/v1/health")
                    || path.equals("/api/v1/auth/login")   // 登录端点放行（Keycloak 代理）
                    || path.startsWith("/actuator/"));
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + escapeJson(message) + "\"}");
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + escapeJson(message) + "\"}");
    }

    /**
     * 转义JSON字符串中的特殊字符，防止JSON注入。
     */
    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}