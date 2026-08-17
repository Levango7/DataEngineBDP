package com.levango7.dataenginebdp.encaps.security;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 审计日志 AOP 切面。
 *
 * <p>拦截标注 {@link AuditLog} 的 Controller 方法，记录完整操作上下文
 * 到独立审计 logger {@code security.audit.log}（logback 可配置单独 appender
 * 输出到 {@code audit.log} 文件），同时输出到业务 logger 便于联调。</p>
 *
 * <h3>记录字段</h3>
 * <ul>
 *   <li>{@code timestamp} — 事件时间（UTC，ISO-8601）</li>
 *   <li>{@code user}     — 当前用户 ID（来自 {@link TenantContext}）</li>
 *   <li>{@code tenant}   — 当前租户 ID（来自 {@link TenantContext}）</li>
 *   <li>{@code action}   — 操作名（注解或 HTTP_METHOD+URI）</li>
 *   <li>{@code resource} — 资源类型（注解或 Controller 简名）</li>
 *   <li>{@code params}   — 方法入参（toString，截断 2000 字符）</li>
 *   <li>{@code result}   — 执行结果 SUCCESS / FAILURE</li>
 *   <li>{@code duration} — 耗时（ms）</li>
 *   <li>{@code ip}       — 客户端 IP（X-Forwarded-For 优先）</li>
 *   <li>{@code error}    — 失败时的异常消息（截断 500 字符）</li>
 * </ul>
 *
 * <h3>非阻断设计</h3>
 * <p>采用 {@code @Around} 通知，业务异常透传给调用方，切面仅负责记录，
 * 切面自身异常被捕获并降级为 WARN 日志，绝不阻断业务执行。</p>
 *
 * <h3>等保对应</h3>
 * <p>GB/T 22239-2019 等保 2.0 安全审计控制项（8.1.4.3）：
 * a) 启用审计；b) 记录日期/时间/用户/事件类型/是否成功；
 * c) 保护审计记录避免未预期删除/修改/覆盖；
 * d) 留存期至少 6 个月（由外部日志归档策略保证）。</p>
 */
@Aspect
@Component
public class AuditLogAspect {

    /** 业务 logger（与切面类同名，输出到主日志） */
    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);
    /** 独立审计 logger，可在 logback 中配置单独 appender 归档到 audit.log */
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("security.audit.log");

    /** 参数/异常消息最大记录长度，超过截断 */
    private static final int MAX_FIELD_LEN = 2000;
    private static final int MAX_ERROR_LEN = 500;

    /**
     * 拦截 {@code @AuditLog} 标注的 Controller 方法，记录审计日志。
     *
     * @param pjp     连接点
     * @param auditLog 注解实例
     * @return 业务方法返回值
     * @throws Throwable 业务方法抛出的异常透传
     */
    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        Instant start = Instant.now();
        long startMs = System.currentTimeMillis();

        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String method = signature.toShortString();
        String action = resolveAction(auditLog, signature);
        String resource = resolveResource(auditLog, signature);
        String userId = TenantContext.getUserId();
        String tenantId = TenantContext.getTenantId();
        HttpServletRequest request = currentRequest();
        String ip = resolveClientIp(request);
        String params = truncate(Arrays.toString(pjp.getArgs()), MAX_FIELD_LEN);

        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable ex) {
            error = ex;
            throw ex;
        } finally {
            long durationMs = System.currentTimeMillis() - startMs;
            String resultStatus = error == null ? "SUCCESS" : "FAILURE";
            String errorMsg = error == null ? null
                    : truncate(error.getClass().getSimpleName() + ": " + error.getMessage(), MAX_ERROR_LEN);

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("timestamp", start.toString());
            event.put("user", userId);
            event.put("tenant", tenantId);
            event.put("action", action);
            event.put("resource", resource);
            event.put("method", method);
            event.put("params", params);
            event.put("result", resultStatus);
            event.put("durationMs", durationMs);
            event.put("ip", ip);
            if (errorMsg != null) {
                event.put("error", errorMsg);
            }

            // 独立审计 logger 输出（logback 可路由到 audit.log）
            AUDIT_LOG.info("AUDIT {}", event);
            // 业务 logger 仅在失败或耗时 > 1s 时打 INFO，避免噪音
            if (error != null || durationMs > TimeUnit.SECONDS.toMillis(1)) {
                log.info("AUDIT action={} resource={} result={} durationMs={}ms user={} tenant={}",
                        action, resource, resultStatus, durationMs, userId, tenantId);
            }
        }
    }

    // ===== 解析辅助 =====

    /**
     * 解析操作名：注解非空用注解，否则用 HTTP 方法 + URI。
     */
    private String resolveAction(AuditLog ann, MethodSignature signature) {
        if (!ann.action().isEmpty()) {
            return ann.action();
        }
        HttpServletRequest req = currentRequest();
        if (req != null) {
            return req.getMethod() + " " + req.getRequestURI();
        }
        return signature.getName();
    }

    /**
     * 解析资源类型：注解非空用注解，否则用 Controller 类简名。
     */
    private String resolveResource(AuditLog ann, MethodSignature signature) {
        if (!ann.resource().isEmpty()) {
            return ann.resource();
        }
        String className = signature.getDeclaringType().getSimpleName();
        // 去掉 Controller 后缀
        if (className.endsWith("Controller")) {
            return className.substring(0, className.length() - "Controller".length()).toLowerCase();
        }
        return className.toLowerCase();
    }

    /**
     * 获取当前 HTTP 请求（可空，非 Web 上下文时返回 null）。
     */
    private static HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }

    /**
     * 解析客户端 IP：优先 X-Forwarded-For（经代理），其次 X-Real-IP，最后 remoteAddr。
     */
    private static String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For 可能是链式：client, proxy1, proxy2
            return xff.split(",")[0].trim();
        }
        String xReal = request.getHeader("X-Real-IP");
        if (xReal != null && !xReal.isBlank()) {
            return xReal.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 截断字符串到最大长度，超出追加 {@code ...}。
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }
}