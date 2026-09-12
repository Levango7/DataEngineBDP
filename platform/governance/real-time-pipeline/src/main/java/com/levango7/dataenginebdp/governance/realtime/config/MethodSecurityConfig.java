package com.levango7.dataenginebdp.governance.realtime.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * 方法级安全配置（R8 安全修复）。
 *
 * <p>启用 {@link EnableMethodSecurity} 以使 {@code @PreAuthorize} 注解在
 * {@code GovernanceController} 上生效。common-security 的 {@code SecurityConfig}
 * 仅配置过滤链与端点级授权，未开启方法级安全，故在此补充。</p>
 *
 * <p>启用后，{@code @PreAuthorize("isAuthenticated()")}、
 * {@code @PreAuthorize("hasRole('GOVERNANCE_WRITER')")} 等注解将由
 * Spring Security 的 AOP 代理拦截校验。</p>
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
}