package com.levango7.dataenginebdp.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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

    /**
     * 允许的签名算法白名单（显式拒绝 none / RSA-PSS / 其它密钥体系）。
     *
     * <p>HMAC 三档都要列：jjwt 的 {@code signWith(SecretKey)} 按密钥位宽自动选 alg，
     * HS384/HS512 是合法且常见的形态（见 {@link #parseSignedClaims} 注释里的实测记录）。</p>
     */
    private static final String ALG_HS256 = "HS256";
    private static final String ALG_HS384 = "HS384";
    private static final String ALG_HS512 = "HS512";
    private static final String ALG_RS256 = "RS256";

    /** header 解析用（只读 alg/kid，不涉密） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CLAIM_TENANT_ID = "tenantId";
    private static final String TENANT_HEADER = "X-Tenant-Id";

    private final SecretKey signingKey;
    private final String issuer;

    /**
     * Keycloak RS256 公钥提供器（可选）。
     *
     * <p>为 {@code null} 时只验自签 HS256 token（与本次改动前完全一致）；非 {@code null} 时
     * 按 token header 的 {@code alg} 自动分流：{@code HS256} 走 {@link #signingKey}，
     * {@code RS256} 走本提供器（按 {@code kid} 拉 Keycloak JWKS 公钥）。</p>
     */
    private final JwksPublicKeyProvider jwksProvider;

    /**
     * 构造过滤器（仅 HS256 自签，行为与历史版本一致）。
     *
     * @param secret  JWT 签名密钥（HMAC-SHA），至少 256 bit（32 字节）
     * @param issuer  JWT issuer，校验 {@code iss} claim 必须匹配
     */
    public JwtAuthFilter(@Value("${app.security.jwt.secret}") String secret,
                         @Value("${app.security.jwt.issuer}") String issuer) {
        this(secret, issuer, null);
    }

    /**
     * 构造过滤器（支持 Keycloak RS256 双模式）。
     *
     * @param secret        HS256 签名密钥（自签通道，必须配置）
     * @param issuer        JWT issuer 校验值（HS256 通道用；RS256 通道用 token 自带 iss）
     * @param jwksProvider  Keycloak JWKS 提供器；{@code null} 表示不启用 RS256 通道
     */
    public JwtAuthFilter(@Value("${app.security.jwt.secret}") String secret,
                         @Value("${app.security.jwt.issuer}") String issuer,
                         JwksPublicKeyProvider jwksProvider) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.jwksProvider = jwksProvider;
    }

    /**
     * 按 token 的 {@code alg} 分流验签。
     *
     * <ul>
     *   <li>{@code HS256}：用自签密钥（{@link #signingKey}）+ 本地配置 issuer 校验；
     *       这是历史唯一通道，行为保持不变。</li>
     *   <li>{@code RS256}（Keycloak 签发）：仅当 {@link #jwksProvider} 已注入时可用，
     *       按 {@code kid} 拉公钥；issuer 由 Keycloak 自己保证（token 自带 iss），
     *       此处不拿本地 issuer 去比（本地 issuer 是"自签通道"的约定）。</li>
     *   <li>其余 alg（{@code none}/RSA-PSS/…）：一律拒——显式白名单比"默认只支持某一种"更安全。</li>
     *   <li>{@code HS384}/{@code HS512}：与 HS256 同族（同强度 HMAC，只是摘要位宽不同），
     *       同样走自签密钥分支——jjwt 按密钥长度自动选出它们，拒掉会误伤合法部署。</li>
     * </ul>
     *
     * <p>HS256 token 在未启用 JWKS 的模块上仍可验；反过来 RS256 token 在没配 JWKS 的
     * 模块上会被拒（不再静默按 HS256 试一次——那只会得到无意义的验签失败）。</p>
     */
    private Jws<Claims> parseSignedClaims(String token) {
        TokenHeader header = readHeader(token);

        // 自签 HMAC 通道：HS256/HS384/HS512 同族同强度，全部放行走同一验签分支。
        //
        // 为什么必须三个都放行（2026-10-13 CI 实测教训）：jjwt 的
        // `signWith(SecretKey)` **按密钥位宽自动推 alg**（javap 实证：
        // 384≤bit<512 → HmacSHA384、≥512 → HmacSHA512），并不是固定 HS256。
        // 因此一个 53 字节（424 bit）密钥签出的 token 就是 {"alg":"HS384"}；
        // 若白名单只写 HS256，它会被 401 拒掉——这不是"异常算法"，而是合法且常见的形态。
        // 真实生产风险同源：AuthController 用 hmacShaKeyFor(jwtSecret) 签发，
        // 意图算法即 HS384，凡部署环境 JWT_SECRET ≥48 字节都会签出 HS384。
        //
        // 安全目标不变：这里拒的是 none / RSA-PSS / 以及任何"换了密钥体系"的 alg。
        if (ALG_HS256.equals(header.alg)
                || ALG_HS384.equals(header.alg)
                || ALG_HS512.equals(header.alg)) {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token);
        }
        if (ALG_RS256.equals(header.alg)) {
            if (jwksProvider == null) {
                throw new JwtException("RS256 token 不被接受：未配置 JWKS 公钥提供器");
            }
            return Jwts.parser()
                    .verifyWith(jwksProvider.resolve(header.kid))
                    .build()
                    .parseSignedClaims(token);
        }
        throw new JwtException("不支持的签名算法: " + header.alg);
    }

    /**
     * 读 token header 的 {@code alg} 与 {@code kid}（不验签）。
     *
     * <p>为什么自己解而不是用 jjwt：jjwt 0.13 的 {@code Header} 只暴露
     * {@code getAlgorithm()}，没有 kid 读取方法；而 {@code parseUnsecuredClaims}
     * 只对"非 JWS 结构"（两段式）生效，遇到正常三段 JWS 直接抛异常——两条路都走不通。
     * header 段（{@code base64url(JSON)}）本身不含签名信息、也不含秘密，先解析它只为了
     * <b>选验签通道</b>；真正的验签与过期校验仍由后续 {@code parseSignedClaims} 完成，
     * 因此不存在"读 header 即信任"的风险。</p>
     */
    private static TokenHeader readHeader(String token) {
        int firstDot = token.indexOf('.');
        if (firstDot <= 0) {
            throw new JwtException("JWT 结构不合法");
        }
        String headerB64 = token.substring(0, firstDot);
        JsonNode header;
        try {
            header = OBJECT_MAPPER.readTree(Base64.getUrlDecoder().decode(headerB64));
        } catch (IOException | IllegalArgumentException e) {
            throw new JwtException("JWT header 解析失败: " + e.getMessage());
        }
        String alg = header.path("alg").asText(null);
        String kid = header.path("kid").asText(null);
        return new TokenHeader(alg, kid);
    }

    /** header 的关键字段（仅用于选择验签通道）。 */
    private record TokenHeader(String alg, String kid) {
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
            Jws<Claims> parsed = parseSignedClaims(token);
            Claims claims = parsed.getPayload();

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
        } catch (JwksPublicKeyProvider.JwtKeyException e) {
            // 取不到验签公钥（JWKS 端点故障 / kid 不存在）是**基础设施故障**，不是"token 不合法"：
            // 返回 503 让调用方区分"稍后重试"与"拿有效 token 来"，绝不能 fail-open 放行，
            // 也不该穿透成 500（那会被当成服务 bug 而不是上游故障）。
            log.warn("JWT 验签公钥不可用: {}", e.getMessage());
            sendServiceUnavailable(response, "token verification key unavailable");
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
     * 以下三类请求不走 JWT 校验，直接放行：
     * <ol>
     *   <li><b>已由前置过滤器完成认证</b>的请求（如 {@code ApiKeyAuthFilter} 通过
     *       {@code X-API-Key} 建立的认证）——否则本过滤器只看 {@code Authorization} 头，
     *       会把合法的 API Key 请求全部 401，使 API Key 认证形同虚设（P0-2）。
     *       <b>匿名 token 不算已认证</b>：{@link AnonymousAuthenticationToken} 必须排除，
     *       放行它等于关掉鉴权。</li>
     *   <li>健康检查 {@code /api/v1/health}</li>
     *   <li>登录 {@code /api/v1/auth/login} 与 actuator {@code /actuator/**}</li>
     * </ol>
     *
     * <p>注：本方法为 {@code public} 以便跨模块单元测试直接调用验证（OncePerRequestFilter
     * 父类声明为 protected，子类覆盖时放宽访问控制符合 Java 规范）。</p>
     */
    @Override
    public boolean shouldNotFilter(HttpServletRequest request) {
        if (hasEstablishedAuthentication()) {
            return true;
        }
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        return path != null
                && (path.equals("/api/v1/health")
                    || path.equals("/api/v1/auth/login")   // 登录端点放行（Keycloak 代理）
                    || path.startsWith("/actuator/"));
    }

    /**
     * 判断当前请求是否已在过滤链上游完成真实认证。
     *
     * <p>判定为真的条件：SecurityContext 中存在认证对象、{@code isAuthenticated()} 为真、
     * 且<b>不是</b> {@link AnonymousAuthenticationToken}——匿名 token 的
     * {@code isAuthenticated()} 也返回 true，但放行它会绕过 JWT 鉴权。</p>
     *
     * @return 上游已建立可信认证时返回 {@code true}
     */
    private boolean hasEstablishedAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
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
     * 503：验签公钥不可用（JWKS 故障 / kid 缺失）。
     *
     * <p>与 401 区分：401 = token 无效（换 token 也没用）；
     * 503 = 服务端验签基础设施故障（重试可能恢复）。调用方可以据此决定是否退避重试。</p>
     */
    private void sendServiceUnavailable(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
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