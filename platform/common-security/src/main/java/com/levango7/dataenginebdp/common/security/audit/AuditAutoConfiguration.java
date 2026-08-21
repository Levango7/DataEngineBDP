package com.levango7.dataenginebdp.common.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 审计合规自动配置（v2.1 审计合规增强）。
 *
 * <p>当 {@code app.audit.enabled=true} 时自动装配审计相关 Bean：</p>
 * <ul>
 *   <li>{@link AuditConfig} - 审计配置</li>
 *   <li>{@link AuditLogService} - 审计日志服务</li>
 *   <li>{@link AuditLogFilter} - HTTP 请求审计过滤器</li>
 *   <li>{@link AuditLogAspect} - 方法级审计切面</li>
 * </ul>
 */
@Configuration
@EnableAsync
@ConditionalOnProperty(prefix = "app.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuditAutoConfiguration {

    /**
     * 审计配置 Bean。
     *
     * @return AuditConfig
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditConfig auditConfig() {
        return new AuditConfig();
    }

    /**
     * 审计日志服务 Bean。
     *
     * @param auditConfig  审计配置
     * @param objectMapper JSON 序列化器
     * @return AuditLogService
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditLogService auditLogService(AuditConfig auditConfig, ObjectMapper objectMapper) {
        return new AuditLogService(auditConfig, objectMapper);
    }

    /**
     * HTTP 请求审计过滤器 Bean。
     *
     * @param auditLogService 审计日志服务
     * @return AuditLogFilter
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditLogFilter auditLogFilter(AuditLogService auditLogService) {
        return new AuditLogFilter(auditLogService);
    }

    /**
     * 审计日志切面 Bean。
     *
     * @param auditLogService 审计日志服务
     * @param objectMapper    JSON 序列化器
     * @return AuditLogAspect
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditLogAspect auditLogAspect(AuditLogService auditLogService, ObjectMapper objectMapper) {
        return new AuditLogAspect(auditLogService, objectMapper);
    }
}