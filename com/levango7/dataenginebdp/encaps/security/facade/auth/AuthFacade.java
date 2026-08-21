package com.shuqing.bigdata.encaps.security.facade.auth;

import com.shuqing.bigdata.encaps.security.TenantContext;
import com.shuqing.bigdata.encaps.security.facade.config.SecurityFacadeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 鉴权统一门面（AuthFacade）。
 *
 * <p>委托 Spring Security 的 {@link SecurityContextHolder} 与项目自身的 {@link TenantContext}，
 * 对外提供"检查当前主体是否有权访问指定资源"的简化 API。</p>
 *
 * <h3>设计动机</h3>
 * <ul>
 *   <li>业务方无需直接接触 Spring Security API，降低耦合</li>
 *   <li>统一返回 {@link AuthResult}，便于审计与证据收集</li>
 *   <li>支持租户隔离校验：当 {@code require-tenant=true} 时，无租户上下文即拒绝</li>
 *   <li>fail-closed：未认证主体默认拒绝，避免遗漏鉴权</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>无状态，依赖的 {@link SecurityContextHolder} 与 {@link TenantContext} 均基于 ThreadLocal，
 * 天然线程隔离。</p>
 */
@Component
public class AuthFacade {

    private static final Logger log = LoggerFactory.getLogger(AuthFacade.class);

    private final SecurityFacadeConfig config;

    /**
     * 构造 AuthFacade。
     *
     * @param config SecurityFacade 配置
     */
    public AuthFacade(SecurityFacadeConfig config) {
        this.config = config;
    }

    /**
     * 检查当前主体是否已认证。
     *
     * @return AuthResult
     * @throws IllegalStateException 鉴权能力被禁用
     */
    public AuthResult checkAuthenticated() {
        ensureEnabled();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return AuthResult.deny("not authenticated", null);
        }
        String principal = auth.getName();
        Set<String> authorities = extractAuthorities(auth);
        return AuthResult.allow(principal, authorities);
    }

    /**
     * 检查当前主体是否拥有指定权限。
     *
     * @param requiredAuthority 需要的权限名（如 {@code ROLE_ADMIN}）
     * @return AuthResult
     * @throws IllegalStateException    鉴权能力被禁用
     * @throws IllegalArgumentException requiredAuthority 为空
     */
    public AuthResult checkAuthority(String requiredAuthority) {
        ensureEnabled();
        if (requiredAuthority == null || requiredAuthority.isBlank()) {
            throw new IllegalArgumentException("requiredAuthority must not be blank");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return AuthResult.deny("not authenticated", null);
        }
        Set<String> authorities = extractAuthorities(auth);
        if (authorities.contains(requiredAuthority)) {
            return AuthResult.allow(auth.getName(), authorities);
        }
        return AuthResult.deny("missing authority: " + requiredAuthority, auth.getName());
    }

    /**
     * 检查当前主体是否拥有指定权限之一（任一满足即允许）。
     *
     * @param requiredAuthorities 需要的权限集合（任一匹配即通过）
     * @return AuthResult
     */
    public AuthResult checkAnyAuthority(String... requiredAuthorities) {
        ensureEnabled();
        if (requiredAuthorities == null || requiredAuthorities.length == 0) {
            throw new IllegalArgumentException("requiredAuthorities must not be empty");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return AuthResult.deny("not authenticated", null);
        }
        Set<String> authorities = extractAuthorities(auth);
        for (String required : requiredAuthorities) {
            if (authorities.contains(required)) {
                return AuthResult.allow(auth.getName(), authorities);
            }
        }
        return AuthResult.deny("missing any of: " + String.join(", ", requiredAuthorities), auth.getName());
    }

    /**
     * 校验当前租户上下文。
     *
     * <p>当 {@code app.security.facade.auth.require-tenant=true} 时，
     * 无租户上下文即拒绝；用于强制多租户隔离。</p>
     *
     * @return AuthResult
     */
    public AuthResult checkTenantContext() {
        ensureEnabled();
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            if (config.getAuth().isRequireTenant()) {
                return AuthResult.deny("tenant context required but absent", TenantContext.getUserId());
            }
            // 不强制租户时允许，但记录 DEBUG
            log.debug("Tenant context absent but not required (require-tenant=false)");
            return AuthResult.allow(TenantContext.getUserId(), Set.of());
        }
        return AuthResult.allow(TenantContext.getUserId(), Set.of("TENANT:" + tenantId));
    }

    /**
     * 综合校验：已认证 + 租户上下文。
     *
     * @return AuthResult，任一不满足即拒绝
     */
    public AuthResult checkFullAccess() {
        AuthResult authResult = checkAuthenticated();
        if (!authResult.isAllowed()) {
            return authResult;
        }
        AuthResult tenantResult = checkTenantContext();
        if (!tenantResult.isAllowed()) {
            return tenantResult;
        }
        // 合并权限
        Set<String> merged = new HashSet<>(authResult.getGrantedAuthorities());
        merged.addAll(tenantResult.getGrantedAuthorities());
        return AuthResult.allow(authResult.getPrincipal(), merged);
    }

    /**
     * 获取当前主体名。
     *
     * @return 主体名；未认证返回 null
     */
    public String currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : null;
    }

    /**
     * 获取当前租户 ID。
     *
     * @return 租户 ID；未设置返回 null
     */
    public String currentTenant() {
        return TenantContext.getTenantId();
    }

    private void ensureEnabled() {
        if (!config.isEnabled() || !config.getAuth().isEnabled()) {
            throw new IllegalStateException("AuthFacade is disabled (app.security.facade.enabled="
                    + config.isEnabled() + ", auth.enabled=" + config.getAuth().isEnabled() + ")");
        }
    }

    private Set<String> extractAuthorities(Authentication auth) {
        Set<String> set = new HashSet<>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            set.add(ga.getAuthority());
        }
        return set;
    }
}