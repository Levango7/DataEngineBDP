package com.shuqing.bigdata.encaps.security.facade.auth;

import com.shuqing.bigdata.encaps.security.TenantContext;
import com.shuqing.bigdata.encaps.security.facade.config.SecurityFacadeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AuthFacade} 单元测试。
 *
 * <p>覆盖认证检查、权限检查、租户上下文校验、禁用异常等。
 * 使用 Spring Security 的 {@link SecurityContextHolder} 与项目 {@link TenantContext}。</p>
 */
class AuthFacadeTest {

    private AuthFacade authFacade;
    private SecurityFacadeConfig config;

    @BeforeEach
    void setUp() {
        config = new SecurityFacadeConfig();
        authFacade = new AuthFacade(config);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    // ===== 认证检查 =====

    @Test
    @DisplayName("checkAuthenticated — 未认证返回 deny")
    void checkAuthenticated_notAuthenticated_shouldDeny() {
        AuthResult result = authFacade.checkAuthenticated();

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("not authenticated");
    }

    @Test
    @DisplayName("checkAuthenticated — 已认证返回 allow")
    void checkAuthenticated_authenticated_shouldAllow() {
        setAuthentication("user1", "ROLE_USER");

        AuthResult result = authFacade.checkAuthenticated();

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getPrincipal()).isEqualTo("user1");
        assertThat(result.getGrantedAuthorities()).contains("ROLE_USER");
    }

    // ===== 权限检查 =====

    @Test
    @DisplayName("checkAuthority — 拥有权限返回 allow")
    void checkAuthority_hasAuthority_shouldAllow() {
        setAuthentication("user1", "ROLE_ADMIN");

        AuthResult result = authFacade.checkAuthority("ROLE_ADMIN");

        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("checkAuthority — 无权限返回 deny")
    void checkAuthority_missingAuthority_shouldDeny() {
        setAuthentication("user1", "ROLE_USER");

        AuthResult result = authFacade.checkAuthority("ROLE_ADMIN");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("missing authority");
    }

    @Test
    @DisplayName("checkAnyAuthority — 任一权限满足即 allow")
    void checkAnyAuthority_anyMatch_shouldAllow() {
        setAuthentication("user1", "ROLE_USER");

        AuthResult result = authFacade.checkAnyAuthority("ROLE_ADMIN", "ROLE_USER");

        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("checkAnyAuthority — 全不满足返回 deny")
    void checkAnyAuthority_noneMatch_shouldDeny() {
        setAuthentication("user1", "ROLE_USER");

        AuthResult result = authFacade.checkAnyAuthority("ROLE_ADMIN", "ROLE_SUPER");

        assertThat(result.isAllowed()).isFalse();
    }

    @Test
    @DisplayName("checkAuthority — 空权限名抛 IllegalArgumentException")
    void checkAuthority_blank_shouldThrow() {
        assertThatThrownBy(() -> authFacade.checkAuthority(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 租户上下文 =====

    @Test
    @DisplayName("checkTenantContext — 有租户返回 allow")
    void checkTenantContext_withTenant_shouldAllow() {
        TenantContext.setTenantId("t1");
        TenantContext.setUserId("u1");

        AuthResult result = authFacade.checkTenantContext();

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getGrantedAuthorities()).contains("TENANT:t1");
    }

    @Test
    @DisplayName("checkTenantContext — 无租户且 requireTenant=true 返回 deny")
    void checkTenantContext_noTenant_required_shouldDeny() {
        AuthResult result = authFacade.checkTenantContext();

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("tenant context required");
    }

    @Test
    @DisplayName("checkTenantContext — 无租户且 requireTenant=false 返回 allow")
    void checkTenantContext_noTenant_notRequired_shouldAllow() {
        config.getAuth().setRequireTenant(false);

        AuthResult result = authFacade.checkTenantContext();

        assertThat(result.isAllowed()).isTrue();
    }

    // ===== 综合校验 =====

    @Test
    @DisplayName("checkFullAccess — 已认证+有租户返回 allow")
    void checkFullAccess_authenticatedWithTenant_shouldAllow() {
        setAuthentication("user1", "ROLE_USER");
        TenantContext.setTenantId("t1");

        AuthResult result = authFacade.checkFullAccess();

        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("checkFullAccess — 未认证返回 deny")
    void checkFullAccess_notAuthenticated_shouldDeny() {
        AuthResult result = authFacade.checkFullAccess();

        assertThat(result.isAllowed()).isFalse();
    }

    @Test
    @DisplayName("checkFullAccess — 已认证但无租户返回 deny")
    void checkFullAccess_authenticatedNoTenant_shouldDeny() {
        setAuthentication("user1", "ROLE_USER");

        AuthResult result = authFacade.checkFullAccess();

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getReason()).contains("tenant");
    }

    // ===== 当前主体查询 =====

    @Test
    @DisplayName("currentPrincipal — 已认证返回用户名")
    void currentPrincipal_authenticated_shouldReturnName() {
        setAuthentication("user1", "ROLE_USER");
        assertThat(authFacade.currentPrincipal()).isEqualTo("user1");
    }

    @Test
    @DisplayName("currentPrincipal — 未认证返回 null")
    void currentPrincipal_notAuthenticated_shouldReturnNull() {
        assertThat(authFacade.currentPrincipal()).isNull();
    }

    @Test
    @DisplayName("currentTenant — 返回 TenantContext 中的租户")
    void currentTenant_shouldReturnFromContext() {
        TenantContext.setTenantId("t1");
        assertThat(authFacade.currentTenant()).isEqualTo("t1");
    }

    // ===== 禁用 =====

    @Test
    @DisplayName("禁用后抛 IllegalStateException")
    void disabled_shouldThrow() {
        config.setEnabled(false);
        assertThatThrownBy(() -> authFacade.checkAuthenticated())
                .isInstanceOf(IllegalStateException.class);
    }

    // ===== 辅助 =====

    private void setAuthentication(String principal, String... authorities) {
        List<SimpleGrantedAuthority> authList = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, authList);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}