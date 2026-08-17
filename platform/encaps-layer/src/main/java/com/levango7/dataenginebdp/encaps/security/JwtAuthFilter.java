package com.levango7.dataenginebdp.encaps.security;

import com.levango7.dataenginebdp.encaps.crypto.jwt.GmJwtProcessor;
import com.levango7.dataenginebdp.encaps.crypto.jwt.JwtAlgorithm;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM2Provider;
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
import java.util.Map;

/**
 * JWT 认证过滤器。
 *
 * <p>从 {@code Authorization} 头提取 Bearer token，按配置算法验证签名与过期时间，
 * 解析出 {@code tenantId} 与 {@code sub}(userId) claim，写入
 * {@link SecurityContextHolder} 与 {@link TenantContext}。</p>
 *
 * <h3>支持的签名算法</h3>
 * <ul>
 *   <li>{@code HS384}（默认，非信创）：HMAC-SHA 对称签名，使用 {@code app.security.jwt.secret}</li>
 *   <li>{@code SM3withSM2}（信创环境）：SM2 非对称签名，使用 {@code app.security.jwt.sm2-private-key/sm2-public-key}</li>
 * </ul>
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
    private static final String TENANT_HEADER = "X-Tenant-Id";

    /** JWT 算法标识：HS384（HMAC-SHA，默认）/ SM3withSM2（信创环境） */
    private final String algorithm;
    private final SecretKey signingKey;
    private final String issuer;
    private final OidcJwtDecoder oidcJwtDecoder;
    /** SM2withSM3 处理器（仅 algorithm=SM3withSM2 时非 null） */
    private final GmJwtProcessor gmJwtProcessor;
    /** SM2 公钥 Q 值（仅 SM3withSM2 模式使用） */
    private final byte[] sm2PublicKeyQ;

    /**
     * 构造过滤器。
     *
     * <p>支持双算法：
     * <ul>
     *   <li>{@code algorithm=HS384}（默认）：HMAC-SHA 对称签名，使用 {@code secret}</li>
     *   <li>{@code algorithm=SM3withSM2}（信创）：SM2 非对称签名，使用 {@code sm2PrivateKey/sm2PublicKey}</li>
     * </ul>
     *
     * @param algorithm     JWT 签名算法（HS384 / SM3withSM2）
     * @param secret        JWT 签名密钥（HMAC-SHA），至少 256 bit（32 字节）
     * @param issuer        JWT issuer，校验 {@code iss} claim 必须匹配
     * @param sm2PrivateKey SM2 私钥 D 值 hex 串（信创模式；空表示自动生成临时密钥对）
     * @param sm2PublicKey  SM2 公钥 Q 值 hex 串（信创模式；空表示自动生成临时密钥对）
     * @param oidcJwtDecoder OIDC 解码器（Keycloak RS256；未启用时为回退模式）
     */
    public JwtAuthFilter(@Value("${app.security.jwt.algorithm:HS384}") String algorithm,
                         @Value("${app.security.jwt.secret}") String secret,
                         @Value("${app.security.jwt.issuer}") String issuer,
                         @Value("${app.security.jwt.sm2-private-key:}") String sm2PrivateKey,
                         @Value("${app.security.jwt.sm2-public-key:}") String sm2PublicKey,
                         OidcJwtDecoder oidcJwtDecoder) {
        this.algorithm = algorithm == null ? "HS384" : algorithm.trim();
        this.issuer = issuer;
        this.oidcJwtDecoder = oidcJwtDecoder;
        // 信创模式：初始化 SM2withSM3 处理器
        if (JwtAlgorithm.SM3_WITH_SM2.equalsIgnoreCase(this.algorithm)) {
            SM2Provider sm2 = new SM2Provider();
            byte[] pubQ;
            if (sm2PrivateKey != null && !sm2PrivateKey.isBlank()
                    && sm2PublicKey != null && !sm2PublicKey.isBlank()) {
                // 使用配置的密钥对
                pubQ = hexToBytes(sm2PublicKey.trim());
                log.info("JWT 算法: SM3withSM2（使用配置的 SM2 密钥对）");
            } else {
                // 开发环境：自动生成临时密钥对（仅公钥用于验签，私钥由 AuthController 持有）
                SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
                pubQ = kp.getPublicKeyQ();
                log.warn("JWT 算法: SM3withSM2（自动生成临时 SM2 密钥对，仅限开发环境；"
                        + "生产环境必须配置 JWT_SM2_PRIVATE_KEY/JWT_SM2_PUBLIC_KEY）");
            }
            this.sm2PublicKeyQ = pubQ;
            this.gmJwtProcessor = new GmJwtProcessor(issuer);
            this.signingKey = null;
        } else {
            // 国际算法模式：HMAC-SHA
            this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            this.gmJwtProcessor = null;
            this.sm2PublicKeyQ = null;
            log.info("JWT 算法: {}（HMAC-SHA 对称签名）", this.algorithm);
        }
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

            String userId;

            // 双模式验证：OIDC(Keycloak RS256) 优先；未启用或 OIDC 验证失败时回退 HMAC
            if (oidcJwtDecoder != null && oidcJwtDecoder.isEnabled()) {
                try {
                    var jwt = oidcJwtDecoder.decode(token);
                    String jwtTenantId = oidcJwtDecoder.extractTenantId(jwt);
                    userId = jwt.getSubject();
                    log.debug("OIDC 验证通过: sub={}, tenant={}", userId, jwtTenantId);

                    // 多租户隔离校验：X-Tenant-Id header 必须与 JWT claim 一致（若提供）
                    String effectiveTenantId = resolveTenantWithHeaderCheck(request, response, jwtTenantId, userId);
                    if (effectiveTenantId == null) {
                        // header 校验失败已写入 403 响应，直接返回
                        return;
                    }
                    setAuthentication(userId, effectiveTenantId);
                    filterChain.doFilter(request, response);
                    return;
                } catch (org.springframework.security.oauth2.jwt.JwtException e) {
                    log.debug("OIDC 验证失败，回退 HMAC: {}", e.getMessage());
                }
            }

            claims = parseAndVerify(token);

            // 从 JWT 提取的租户ID（用户所属租户）
            String jwtTenantId = claims.get(CLAIM_TENANT_ID, String.class);
            userId = claims.getSubject();

            // 多租户隔离校验：X-Tenant-Id header 必须与 JWT claim 一致（若提供）
            String effectiveTenantId = resolveTenantWithHeaderCheck(request, response, jwtTenantId, userId);
            if (effectiveTenantId == null) {
                // header 校验失败已写入 403 响应，直接返回
                return;
            }
            setAuthentication(userId, effectiveTenantId);

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
     * 解析并验签 JWT，按配置算法分发：
     * <ul>
     *   <li>SM3withSM2：使用 {@link GmJwtProcessor} 验签，返回构造的 Claims</li>
     *   <li>HS384/HS256：使用 jjwt 验签，返回 Claims</li>
     * </ul>
     *
     * @param token JWT 字符串
     * @return Claims（兼容 jjwt API）
     * @throws JwtException 验签或声明校验失败
     */
    private Claims parseAndVerify(String token) {
        if (gmJwtProcessor != null) {
            // SM3withSM2 验签
            Map<String, Object> payload = gmJwtProcessor.verify(token, sm2PublicKeyQ);
            // 将 Map 适配为 jjwt Claims（通过 Jwts.claims() 构造，避免依赖内部 API）
            return Jwts.claims().add(payload).build();
        }
        // HMAC-SHA 验签
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }


    /**
     * hex 字符串转字节数组。
     *
     * @param hex hex 串（长度必须为偶数）
     * @return 字节数组
     */
    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        String s = hex.toLowerCase();
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
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
     * 健康检查与 actuator 端点不走 JWT，直接放行。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 用 getRequestURI 而非 getServletPath：MockMvc 中 getServletPath 返回空串，
        // 会导致 /api/v1/auth/login 等放行路径被误拦截。getRequestURI 在 MockMvc 与
        // 真实容器（无 contextPath）中均返回完整路径，行为一致。
        String path = request.getRequestURI();
        return path != null
                && (path.startsWith("/api/v1/health")
                    || path.startsWith("/api/v1/auth/login")   // 登录端点放行（Keycloak 代理）
                    || path.startsWith("/actuator"));
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}