package com.levango7.dataenginebdp.common.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

/**
 * 审计日志 AOP 切面（v2.1 审计合规增强）。
 *
 * <p>拦截标注 {@link Auditable} 的方法，自动记录方法级审计日志。
 * 与 {@link AuditLogFilter} 互补，实现 HTTP + 方法级全链路审计覆盖。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 * {@literal @}Auditable(actionType = ActionType.DELETE, action = "删除集群", level = Level.CRITICAL)
 * public void deleteCluster(String clusterId) { ... }
 * </pre>
 */
@Aspect
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer;

    /**
     * 构造切面。
     *
     * @param auditLogService 审计日志服务
     * @param objectMapper    JSON 序列化器
     */
    public AuditLogAspect(AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    }

    /**
     * 环绕通知：拦截 {@link Auditable} 标注的方法。
     *
     * @param joinPoint 连接点
     * @param auditable 审计注解
     * @return 方法返回值
     * @throws Throwable 方法异常
     */
    @Around("@annotation(auditable)")
    public Object auditMethod(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        Instant startTime = Instant.now();
        long startNanos = System.nanoTime();
        String requestId = UUID.randomUUID().toString();

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = method.getDeclaringClass().getSimpleName();
        String methodName = method.getName();

        Object result = null;
        AuditEvent.Result operationResult = AuditEvent.Result.SUCCESS;
        String errorMessage = null;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            operationResult = AuditEvent.Result.ERROR;
            errorMessage = e.getMessage();
            throw e;
        } finally {
            try {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                String params = auditable.logParams() ? extractParams(joinPoint, method) : "";

                AuditEvent event = AuditLogService.builder()
                        .eventId(requestId)
                        .timestamp(startTime)
                        .userId(extractUserId())
                        .tenantId(extractTenantId())
                        .actionType(auditable.actionType())
                        .action(auditable.action().isEmpty()
                                ? className + "." + methodName
                                : auditable.action())
                        .resource(auditable.resource().isEmpty()
                                ? className + "#" + methodName
                                : auditable.resource())
                        .requestMethod("METHOD")
                        .requestPath(className + "." + methodName)
                        .requestParams(params)
                        .responseStatus(operationResult == AuditEvent.Result.SUCCESS ? 200 : 500)
                        .responseTimeMs(durationMs)
                        .result(operationResult)
                        .errorMessage(errorMessage)
                        .level(auditable.level())
                        .category(auditable.category())
                        .build();

                auditLogService.audit(event);
            } catch (Exception e) {
                log.warn("记录方法审计日志失败: {}.{}", className, methodName, e);
            }
        }
    }

    /**
     * 提取方法参数（JSON 格式）。
     *
     * @param joinPoint 连接点
     * @param method    方法
     * @return 参数 JSON
     */
    private String extractParams(ProceedingJoinPoint joinPoint, Method method) {
        try {
            Object[] args = joinPoint.getArgs();
            if (args == null || args.length == 0) {
                return "";
            }
            MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                    joinPoint.getTarget(), method, args, parameterNameDiscoverer);
            return objectMapper.writeValueAsString(context.getVariables());
        } catch (Exception e) {
            return "[params serialization failed: " + e.getMessage() + "]";
        }
    }

    /**
     * 提取当前用户 ID（从 TenantContext 或 SecurityContext）。
     *
     * @return 用户 ID
     */
    private String extractUserId() {
        try {
            return com.levango7.dataenginebdp.common.security.TenantContext.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 提取当前租户 ID。
     *
     * @return 租户 ID
     */
    private String extractTenantId() {
        try {
            return com.levango7.dataenginebdp.common.security.TenantContext.getTenantId();
        } catch (Exception e) {
            return null;
        }
    }
}