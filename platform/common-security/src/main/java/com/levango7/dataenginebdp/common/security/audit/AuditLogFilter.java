package com.levango7.dataenginebdp.common.security.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * HTTP 请求审计过滤器（v2.1 审计合规增强）。
 *
 * <p>拦截所有 HTTP 请求，记录操作审计日志，实现全链路审计覆盖。</p>
 *
 * <p>审计内容（满足等保三级 8.1.4.3 b) 要求）：</p>
 * <ul>
 *   <li>日期时间</li>
 *   <li>操作用户（从 JWT 解析）</li>
 *   <li>租户 ID</li>
 *   <li>来源 IP</li>
 *   <li>请求方法与路径</li>
 *   <li>请求参数（脱敏后）</li>
 *   <li>响应状态码</li>
 *   <li>响应耗时</li>
 *   <li>操作结果（成功/失败）</li>
 *   <li>链路追踪 ID</li>
 * </ul>
 */
public class AuditLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditLogFilter.class);

    private final AuditLogService auditLogService;

    /**
     * 构造过滤器。
     *
     * @param auditLogService 审计日志服务
     */
    public AuditLogFilter(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Instant startTime = Instant.now();
        long startNanos = System.nanoTime();

        String traceId = generateTraceId();
        String requestId = UUID.randomUUID().toString();

        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                recordAuditEvent(request, response, traceId, requestId, startTime, durationMs);
            } catch (Exception e) {
                log.warn("记录审计日志失败: path={}, error={}", request.getRequestURI(), e.getMessage());
            }
        }
    }

    /**
     * 记审计事件。
     *
     * @param request    HTTP 请求
     * @param response   HTTP 响应
     * @param traceId    链路追踪 ID
     * @param requestId  请求 ID
     * @param startTime  开始时间
     * @param durationMs 耗时
     */
    private void recordAuditEvent(HttpServletRequest request,
                                  HttpServletResponse response,
                                  String traceId,
                                  String requestId,
                                  Instant startTime,
                                  long durationMs) {
        String userId = extractUserId(request);
        String tenantId = extractTenantId(request);
        String sourceIp = extractClientIp(request);
        String requestParams = extractRequestParams(request);

        AuditEvent.ActionType actionType = mapActionType(request.getMethod());
        AuditEvent.Result result = mapResult(response.getStatus());
        AuditEvent.Level level = determineLevel(actionType, result);
        AuditEvent.Category category = determineCategory(request.getRequestURI());

        AuditEvent event = AuditLogService.builder()
                .eventId(requestId)
                .timestamp(startTime)
                .traceId(traceId)
                .userId(userId)
                .tenantId(tenantId)
                .actionType(actionType)
                .action(request.getMethod() + " " + request.getRequestURI())
                .resource(request.getRequestURI())
                .sourceIp(sourceIp)
                .userAgent(request.getHeader("User-Agent"))
                .requestMethod(request.getMethod())
                .requestPath(request.getRequestURI())
                .requestParams(requestParams)
                .responseStatus(response.getStatus())
                .responseTimeMs(durationMs)
                .result(result)
                .sessionId(request.getSession(false) != null ? request.getSession(false).getId() : null)
                .level(level)
                .category(category)
                .build();

        auditLogService.audit(event);
    }

    /**
     * 从请求头提取用户 ID（JWT 解析后由 JwtAuthFilter 设置）。
     *
     * @param request HTTP 请求
     * @return 用户 ID
     */
    private String extractUserId(HttpServletRequest request) {
        return request.getHeader("X-User-Id");
    }

    /**
     * 从请求头提取租户 ID。
     *
     * @param request HTTP 请求
     * @return 租户 ID
     */
    private String extractTenantId(HttpServletRequest request) {
        return request.getHeader("X-Tenant-Id");
    }

    /**
     * 提取客户端真实 IP（支持代理）。
     *
     * @param request HTTP 请求
     * @return 客户端 IP
     */
    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }

    /**
     * 提取请求参数（GET 查询参数）。
     *
     * @param request HTTP 请求
     * @return 请求参数
     */
    private String extractRequestParams(HttpServletRequest request) {
        String queryString = request.getQueryString();
        return queryString != null ? queryString : "";
    }

    /**
     * 生成链路追踪 ID。
     *
     * @return 追踪 ID
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 根据 HTTP 方法映射操作类型。
     *
     * @param method HTTP 方法
     * @return 操作类型
     */
    private AuditEvent.ActionType mapActionType(String method) {
        if (method == null) {
            return AuditEvent.ActionType.SYSTEM;
        }
        return switch (method.toUpperCase()) {
            case "GET" -> AuditEvent.ActionType.QUERY;
            case "POST" -> AuditEvent.ActionType.CREATE;
            case "PUT", "PATCH" -> AuditEvent.ActionType.UPDATE;
            case "DELETE" -> AuditEvent.ActionType.DELETE;
            default -> AuditEvent.ActionType.SYSTEM;
        };
    }

    /**
     * 根据状态码映射操作结果。
     *
     * @param status 状态码
     * @return 操作结果
     */
    private AuditEvent.Result mapResult(int status) {
        if (status >= 200 && status < 400) {
            return AuditEvent.Result.SUCCESS;
        } else if (status >= 400 && status < 500) {
            return AuditEvent.Result.FAILURE;
        } else {
            return AuditEvent.Result.ERROR;
        }
    }

    /**
     * 确定审计级别。
     *
     * @param actionType 操作类型
     * @param result     操作结果
     * @return 审计级别
     */
    private AuditEvent.Level determineLevel(AuditEvent.ActionType actionType, AuditEvent.Result result) {
        if (result == AuditEvent.Result.ERROR) {
            return AuditEvent.Level.CRITICAL;
        }
        if (actionType == AuditEvent.ActionType.PERMISSION_CHANGE
                || actionType == AuditEvent.ActionType.CONFIG_CHANGE) {
            return AuditEvent.Level.IMPORTANT;
        }
        if (actionType == AuditEvent.ActionType.LOGIN && result == AuditEvent.Result.FAILURE) {
            return AuditEvent.Level.CRITICAL;
        }
        return AuditEvent.Level.INFO;
    }

    /**
     * 根据请求路径确定审计分类。
     *
     * @param path 请求路径
     * @return 审计分类
     */
    private AuditEvent.Category determineCategory(String path) {
        if (path == null) {
            return AuditEvent.Category.SYSTEM_ADMIN;
        }
        if (path.contains("/auth/") || path.contains("/login") || path.contains("/logout")) {
            return AuditEvent.Category.AUTHENTICATION;
        }
        if (path.contains("/permission") || path.contains("/role") || path.contains("/user")) {
            return AuditEvent.Category.ACCESS_CONTROL;
        }
        if (path.contains("/config") || path.contains("/setting")) {
            return AuditEvent.Category.CONFIGURATION;
        }
        if (path.contains("/security") || path.contains("/audit")) {
            return AuditEvent.Category.SECURITY_EVENT;
        }
        return AuditEvent.Category.DATA_OPERATION;
    }
}