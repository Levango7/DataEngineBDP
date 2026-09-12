package com.levango7.dataenginebdp.masterdata.security;

import com.levango7.dataenginebdp.common.security.TenantContext;
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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * API Key 认证过滤器（与 Bearer JWT 认证明确分离）。
 *
 * <p><b>认证方式分离设计（修复 P2: Bearer token 当 AK 用）</b>：
 * <ul>
 *   <li>Bearer JWT → {@code Authorization: Bearer &lt;jwt&gt;} header，
 *       由 {@link com.levango7.dataenginebdp.common.security.JwtAuthFilter} 处理</li>
 *   <li>API Key → {@code X-API-Key: &lt;key&gt;} header，由本过滤器处理</li>
 * </ul>
 *
 * <p>两种认证方式使用不同的 header，互不混淆。
 * 本过滤器仅识别 {@code X-API-Key} header，绝不读取 {@code Authorization} header，
 * 避免将 Bearer token 误当作 API Key 使用。</p>
 *
 * <p>处理逻辑：
 * <ul>
 *   <li>请求携带 {@code X-API-Key} 且 key 在配置白名单中 → 认证成功，
 *       设置 API Key 关联的 tenantId 与 {@code ROLE_API_KEY} 权限</li>
 *   <li>请求携带 {@code X-API-Key} 但 key 不在白名单中 → 401 Unauthorized</li>
 *   <li>请求未携带 {@code X-API-Key} → 跳过本过滤器，
 *       交由后续 {@link com.levango7.dataenginebdp.common.security.JwtAuthFilter} 处理 Bearer JWT</li>
 * </ul>
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code app.security.api-key.enabled}：是否启用 API Key 认证（默认 false）</li>
 *   <li>{@code app.security.api-key.valid-keys}：合法 API Key 列表，逗号分隔</li>
 *   <li>{@code app.security.api-key.tenant-id}：API Key 关联的租户 ID</li>
 * </ul>
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** API Key 请求头名称（与 Authorization header 明确分离） */
    public static final String API_KEY_HEADER = "X-API-Key";

    private final boolean enabled;
    private final List<String> validKeys;
    private final String tenantId;

    /**
     * 构造 API Key 认证过滤器。
     *
     * @param enabled    是否启用 API Key 认证
     * @param validKeys  合法 API Key 列表，逗号分隔（空表示不允许任何 key）
     * @param tenantId   API Key 关联的租户 ID
     */
    public ApiKeyAuthFilter(@Value("${app.security.api-key.enabled:false}") boolean enabled,
                            @Value("${app.security.api-key.valid-keys:}") String validKeys,
                            @Value("${app.security.api-key.tenant-id:api-tenant}") String tenantId) {
        this.enabled = enabled;
        this.validKeys = Arrays.stream(validKeys.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        this.tenantId = tenantId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        // 仅读取 X-API-Key header，绝不读取 Authorization header（认证方式分离）
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            // 未携带 API Key，跳过本过滤器，交由 JwtAuthFilter 处理 Bearer JWT
            filterChain.doFilter(request, response);
            return;
        }

        // 携带了 X-API-Key，进行 API Key 认证
        if (!validKeys.contains(apiKey)) {
            log.warn("API Key 认证失败: invalid key");
            sendUnauthorized(response, "invalid API key");
            return;
        }

        // API Key 认证成功，设置租户上下文与权限
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId("api-key-client");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        "api-key-client", null,
                        List.of(new SimpleGrantedAuthority("ROLE_API_KEY")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 健康检查、登录与 actuator 端点不走 API Key 认证。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        return path != null
                && (path.equals("/api/v1/health")
                    || path.equals("/api/v1/auth/login")
                    || path.startsWith("/actuator/"));
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}