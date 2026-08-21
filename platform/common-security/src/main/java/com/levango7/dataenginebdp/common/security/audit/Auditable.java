package com.levango7.dataenginebdp.common.security.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 可审计注解（v2.1 审计合规增强）。
 *
 * <p>标注在方法上，通过 AOP 切面自动记录审计日志。
 * 适用于 Service/Controller 方法级审计。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 * {@literal @}Auditable(
 *     actionType = ActionType.CREATE,
 *     action = "创建集群",
 *     resource = "cluster",
 *     category = Category.SYSTEM_ADMIN
 * )
 * public ClusterInfo createCluster(ClusterCreateRequest request) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /**
     * 操作类型。
     *
     * @return 操作类型
     */
    AuditEvent.ActionType actionType();

    /**
     * 操作动作描述。
     *
     * @return 动作描述
     */
    String action() default "";

    /**
     * 操作资源。
     *
     * @return 资源
     */
    String resource() default "";

    /**
     * 审计分类。
     *
     * @return 分类
     */
    AuditEvent.Category category() default AuditEvent.Category.DATA_OPERATION;

    /**
     * 审计级别。
     *
     * @return 级别
     */
    AuditEvent.Level level() default AuditEvent.Level.INFO;

    /**
     * 是否记录方法参数。
     *
     * @return 是否记录参数
     */
    boolean logParams() default true;
}