package com.levango7.dataenginebdp.governance.lineage.config;

import com.levango7.dataenginebdp.common.security.TenantContext;
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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 租户上下文过滤器：把「已认证身份」映射为 {@link TenantContext}。
 *
 * <h3>为什么本模块需要它</h3>
 * <p>全平台只有 4 处生产代码会写 {@link TenantContext}（common-security 与 encaps-layer 的
 * {@code JwtAuthFilter}、data-standard 与 master-data 的 {@code ApiKeyAuthFilter}），
 * 本模块一个都没有：它自己声明了 {@code SecurityFilterChain}，于是 common-security 的
 * {@code SecurityConfig} 按 {@code @ConditionalOnMissingBean(SecurityFilterChain.class)}
 * 整体退让，{@code JwtAuthFilter} 压根不会被注册。结果是
 * {@code LineageController} 的类级 {@code @PreAuthorize("isAuthenticated()")} 与
 * {@code requireTenant()} 双双拿不到身份/租户 → 所有业务端点一律 403。</p>
 *
 * <h3>为什么没有直接复用 common-security 的 JwtAuthFilter</h3>
 * <p>评估结论：<b>不能整体复用</b>，理由是它不是"只取租户"的组件，而是<b>自带一整套认证语义</b>：</p>
 * <ol>
 *   <li>它自行做 HMAC 验签并 {@code requireIssuer(issuer)}，而本模块已用
 *       {@code oauth2ResourceServer(jwt)}（{@code NimbusJwtDecoder}，同一把密钥）完成认证，
 *       再挂一层等于<b>重复校验</b>；</li>
 *   <li>它额外要求 {@code iss} 与 {@code tenantId} 两个 claim 都存在，缺一即 401。
 *       本模块现有的准入条件没有这一条，挂上去会让"以前能过的 token 现在被拒"——
 *       属于生产行为变更，不该由一次 CI 修复顺带引入；</li>
 *   <li>它需要的 {@code app.security.jwt.issuer} 占位符在本模块配置里<b>根本不存在</b>，
 *       要复用就必须改生产配置。</li>
 * </ol>
 * <p>因此这里<b>不复制它的认证逻辑</b>（本过滤器不解析、不验签任何 token），
 * 只做一件事：从 Spring Security 已经认证好的 {@link JwtAuthenticationToken} 里读
 * {@code tenantId} claim 写进 {@link TenantContext}——复用的是认证结果，不是认证实现，
 * 所以不存在"第三份 JWT 过滤器"。</p>
 *
 * <h3>dev 回退（默认关闭）</h3>
 * <p>仅当同时满足以下两个条件时才生效：</p>
 * <ul>
 *   <li>{@code app.security.jwt.enabled=false}（JWT 关闭，即配置注释里的"开发态全放行"）</li>
 *   <li>{@code app.security.dev.tenant-id} <b>被显式配置</b>且非空</li>
 * </ul>
 * <p>任一不满足 → fail-closed，直接 403（不会退回"无租户可用"的状态）。
 * 即：<b>JWT 开启时 dev 回退永不生效</b>；dev 租户<b>没有默认值</b>，不配置就是不通。</p>
 * <p>回退激活时会打印醒目 WARNING，并同时补一个已认证主体——否则类级
 * {@code @PreAuthorize("isAuthenticated()")} 仍会 403。</p>
 */
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    /** JWT claim：租户 ID（优先 camelCase，兼容 snake_case）。 */
    private static final String CLAIM_TENANT_ID = "tenantId";
    private static final String CLAIM_TENANT_ID_SNAKE = "tenant_id";

    /** dev 回退主体的用户名（仅审计/日志用）。 */
    private static final String DEV_PRINCIPAL = "dev-client";

    private final boolean jwtEnabled;
    private final String devTenantId;

    /**
     * 构造租户上下文过滤器。
     *
     * @param jwtEnabled   是否启用 JWT 校验（true 时 dev 回退强制失效）
     * @param devTenantId  开发态兜底租户；空表示未配置（fail-closed）
     */
    public TenantContextFilter(@Value("${app.security.jwt.enabled:true}") boolean jwtEnabled,
                               @Value("${app.security.dev.tenant-id:}") String devTenantId) {
        this.jwtEnabled = jwtEnabled;
        this.devTenantId = devTenantId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String tenantId = resolveTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            // JWT 开启态下「根本没有凭据」与「有凭据但缺租户声明」是两种不同失败：
            // 前者语义是未认证(401)，后者是已认证但无租户(403)。
            // 本过滤器注册在 AuthorizationFilter 之前，若不区分就会把 401 场景统一报成 403，
            // 掩盖真实原因（调用方会以为是租户配置问题，实际是没带 token）。
            if (jwtEnabled && isUnauthenticated()) {
                log.warn("缺少认证凭据，拒绝请求: {}", request.getRequestURI());
                sendUnauthorized(response, "缺少认证凭据：请携带 Bearer JWT");
                return;
            }
            // fail-closed：没有可确定的租户就拒绝，绝不"带着空租户"放行
            log.warn("缺少租户上下文，拒绝请求: {}", request.getRequestURI());
            sendForbidden(response, "缺少租户上下文：请携带含 tenantId 声明的 JWT，"
                    + "或在 app.security.jwt.enabled=false 时显式配置 app.security.dev.tenant-id");
            return;
        }

        TenantContext.setTenantId(tenantId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理 ThreadLocal，避免线程池复用导致请求间串租户
            TenantContext.clear();
        }
    }

    /**
     * 解析当前请求的租户 ID。
     *
     * @return 租户 ID；无法确定时返回 null（调用方按 fail-closed 处理）
     */
    private String resolveTenantId() {
        // 1) 已认证主体（JWT 态）：直接取 claim
        String fromJwt = tenantFromAuthentication();
        if (fromJwt != null && !fromJwt.isBlank()) {
            return fromJwt;
        }
        // 2) dev 回退：仅在 JWT 关闭且显式配置 dev 租户时
        if (!jwtEnabled && devTenantId != null && !devTenantId.isBlank()) {
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            log.warn("!! [DEV-ONLY] 已启用开发态租户回退：所有无租户请求将归属 dev 租户 '{}'。", devTenantId);
            log.warn("!! 该回退仅在 app.security.jwt.enabled=false 且显式配置");
            log.warn("!! app.security.dev.tenant-id 时生效；生产环境严禁开启。");
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            ensureAuthenticatedForDev();
            return devTenantId;
        }
        return null;
    }

    /**
     * 从 Spring Security 已认证的 JWT 主体读取租户 claim。
     *
     * @return 租户 ID；无已认证主体或 claim 缺失时返回 null
     */
    private String tenantFromAuthentication() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) {
            return null;
        }
        String tenantId = jwtAuth.getToken().getClaimAsString(CLAIM_TENANT_ID);
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = jwtAuth.getToken().getClaimAsString(CLAIM_TENANT_ID_SNAKE);
        }
        return tenantId;
    }

    /**
     * dev 回退时补一个已认证主体，否则类级 {@code @PreAuthorize("isAuthenticated()")} 会 403。
     *
     * <p>注意：{@code AnonymousAuthenticationFilter} 在本过滤器<b>之前</b>执行，
     * 所以此时拿到的通常是 AnonymousAuthenticationToken。
     * Spring Security 的 {@code isAuthenticated()} 对匿名 token <b>判定为 false</b>，
     * 因此必须把它替换成真实主体，否则 @PreAuthorize 仍然 403。</p>
     */
    private void ensureAuthenticatedForDev() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null || current instanceof AnonymousAuthenticationToken) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            DEV_PRINCIPAL, null,
                            List.of(new SimpleGrantedAuthority("ROLE_DEV"))));
        }
    }

    /**
     * 健康检查与 actuator 端点没有租户概念，跳过本过滤器。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        return path != null
                && (path.equals("/api/v1/health") || path.startsWith("/actuator/"));
    }

    /**
     * 当前请求是否完全没有认证凭据（匿名或无任何主体）。
     *
     * <p>注意：{@code AnonymousAuthenticationFilter} 在本过滤器之前执行，
     * 未携带 token 时拿到的是 {@link AnonymousAuthenticationToken}，
     * 它不代表"已认证"，只是 Spring Security 填充的占位主体。</p>
     *
     * @return true 表示没有真实凭据
     */
    private boolean isUnauthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth instanceof AnonymousAuthenticationToken;
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    /**
     * 401：未携带凭据。
     *
     * <p>与 403 的区别见 {@link #doFilterInternal} 的说明：401 是"没带 token"，
     * 403 是"带了 token 但没有可用租户"。</p>
     */
    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
