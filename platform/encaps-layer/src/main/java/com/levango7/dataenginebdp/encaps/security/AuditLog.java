package com.levango7.dataenginebdp.encaps.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志注解。
 *
 * <p>标注于 Controller 方法上，由 {@link AuditLogAspect} 切面拦截并记录
 * 操作上下文（时间/用户/租户/操作/资源/参数/结果/耗时/IP）到独立审计日志。</p>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * @RestController
 * public class TenantController {
 *     @AuditLog(action = "CREATE_TENANT", resource = "tenant")
 *     @PostMapping
 *     public Tenant create(@RequestBody Tenant t) { ... }
 *
 *     @AuditLog(action = "DELETE_TENANT", resource = "tenant")
 *     @DeleteMapping("/{id}")
 *     public void delete(@PathVariable Long id) { ... }
 * }
 * }</pre>
 *
 * <h3>等保对应</h3>
 * <p>对应 GB/T 22239-2019 等保 2.0 安全审计控制项（8.1.4.3）：
 * 记录日期、时间、用户、事件类型、是否成功等。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {
    /**
     * 操作名，如 {@code CREATE_TENANT} / {@code LOGIN} / {@code ENCRYPT}。
     *
     * <p>空字符串时切面将使用 {@code HTTP_METHOD + URI} 作为操作名。</p>
     *
     * @return 操作名
     */
    String action() default "";

    /**
     * 资源类型，如 {@code tenant} / {@code user} / {@code datasource}。
     *
     * <p>空字符串时切面将使用 Controller 类名简化作为资源。</p>
     *
     * @return 资源类型
     */
    String resource() default "";
}