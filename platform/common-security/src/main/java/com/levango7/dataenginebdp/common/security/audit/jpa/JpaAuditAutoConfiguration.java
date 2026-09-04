package com.levango7.dataenginebdp.common.security.audit.jpa;

import com.levango7.dataenginebdp.common.security.audit.AuditLogService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 审计 JPA 持久化自动装配（C2）。
 *
 * <p>激活条件（全部满足才装配，纯 Web 服务零影响）：
 * <ul>
 *   <li>classpath 有 JPA（使用方自带 spring-boot-starter-data-jpa）</li>
 *   <li>{@code app.audit.storage=database}（默认 file 不装配）</li>
 *   <li>{@code app.audit.enabled=true}</li>
 * </ul></p>
 *
 * <p>装配产物：
 * <ul>
 *   <li>{@link AuditLogJpaRepository}（实体扫描由使用方 @EntityScan 或本包默认）</li>
 *   <li>{@link AuditQueryService.JpaAuditSink} → 注入 AuditLogService（双写）</li>
 *   <li>{@link AuditQueryService}（组合查询）</li>
 *   <li>{@link AuditQueryController}（REST 查询端点，仅 super_admin 角色）</li>
 * </ul></p>
 */
@AutoConfiguration
@ConditionalOnClass(jakarta.persistence.EntityManager.class)
@ConditionalOnProperty(name = "app.audit.storage", havingValue = "database")
@EnableJpaRepositories(basePackages = "com.levango7.dataenginebdp.common.security.audit.jpa")
public class JpaAuditAutoConfiguration {

    /** JPA Sink：注入 AuditLogService 形成"日志文件 + 数据库"双写。 */
    @Bean
    @ConditionalOnMissingBean(AuditQueryService.JpaAuditSink.class)
    public AuditQueryService.JpaAuditSink jpaAuditSink(AuditLogJpaRepository repository,
                                                       AuditLogService auditLogService) {
        AuditQueryService.JpaAuditSink sink = new AuditQueryService.JpaAuditSink(repository);
        auditLogService.setPersistenceSink(sink);
        return sink;
    }

    /** 审计查询服务。 */
    @Bean
    @ConditionalOnMissingBean(AuditQueryService.class)
    public AuditQueryService auditQueryService(AuditLogJpaRepository repository) {
        return new AuditQueryService(repository);
    }

    /** 审计查询 REST 端点（仅 super_admin）。 */
    @Bean
    @ConditionalOnMissingBean(AuditQueryController.class)
    public AuditQueryController auditQueryController(AuditQueryService queryService) {
        return new AuditQueryController(queryService);
    }
}
