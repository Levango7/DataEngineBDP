package com.levango7.dataenginebdp.common.security.audit.jpa;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * C2 审计 JPA 测试专用配置（common-security 无主应用类，
 * 供 @DataJpaTest 的 @ContextConfiguration 显式指定）。
 *
 * <p>仅装配 JPA 自动配置 + 本包 Repository 扫描，等价于
 * JpaAuditAutoConfiguration 的 repository 部分（Service/Controller 由测试 @Import）。</p>
 */
@Configuration
@EnableAutoConfiguration
@EnableJpaRepositories(basePackageClasses = AuditLogJpaRepository.class)
public class AuditJpaTestConfig {
}
