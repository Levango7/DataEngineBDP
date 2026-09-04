package com.levango7.dataenginebdp.common.security.ratelimit;

import com.levango7.dataenginebdp.common.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 速率限制过滤器（C1）：在 JWT 认证之前执行，尽早拒绝超限流量。
 *
 * <p>维度选择策略：
 * <ul>
 *   <li>已登录（TenantContext 有租户）→ 按 {@code tenant:xxx} 限流，多用户共享租户配额</li>
 *   <li>未登录（如 /auth/login）→ 按 {@code ip:xxx} 限流，防匿名爆破</li>
 * </ul>
 *
 * <p>登录接口在 JwtAuthFilter 之前到达本过滤器时 TenantContext 尚未填充，
 * 会自然落入 IP 维度——这正是期望行为（登录爆破按来源 IP 拦截）。</p>
 *
 * <p>超限响应：429 + Retry-After（秒），body 为轻量 JSON，与全局错误体结构一致。</p>
 *
 * <p>注意：本过滤器只做"每分钟稳态速率 + 突发桶容量"的通用限流；
 * 网关大模型调用按 apiKey 的业务级限流由 GatewayController 的 rateLimit 字段另行控制。</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitRegistry registry;

    public RateLimitFilter(RateLimitRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        RateLimitRule rule = registry.matchRule(path);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String tenantId = TenantContext.getTenantId();
        String dimensionKey = tenantId != null && !tenantId.isBlank()
                ? "tenant:" + tenantId
                : "ip:" + clientIp(request);

        if (!registry.tryAcquire(rule, dimensionKey)) {
            log.warn("速率限制触发: path={}, dimension={}, limit={}req/min",
                    path, dimensionKey, rule.ratePerMinute());
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            // Retry-After：下一个令牌的近似等待时间（稳态间隔）
            response.setHeader("Retry-After", String.valueOf(Math.max(1, 60 / rule.ratePerMinute())));
            response.getWriter().write(
                    "{\"code\":429,\"message\":\"rate limit exceeded, retry later\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** 客户端真实 IP：优先 X-Forwarded-For 首段（经反向代理），回退 remoteAddr。 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return request.getRemoteAddr();
    }
}
