package com.levango7.dataenginebdp.encaps.security.facade.auth;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * 鉴权结果。
 *
 * <p>不可变值对象，封装一次鉴权决策的完整信息。</p>
 */
public final class AuthResult {

    /** 是否允许 */
    private final boolean allowed;

    /** 拒绝原因（allowed=false 时非空） */
    private final String reason;

    /** 主体标识（用户 ID） */
    private final String principal;

    /** 授予的权限集合 */
    private final Set<String> grantedAuthorities;

    /**
     * 全参构造。
     *
     * @param allowed           是否允许
     * @param reason            拒绝原因
     * @param principal         主体标识
     * @param grantedAuthorities 授予权限
     */
    public AuthResult(boolean allowed, String reason, String principal, Set<String> grantedAuthorities) {
        this.allowed = allowed;
        this.reason = reason;
        this.principal = principal;
        this.grantedAuthorities = grantedAuthorities == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(grantedAuthorities);
    }

    /**
     * 构造允许结果。
     *
     * @param principal         主体
     * @param grantedAuthorities 授予权限
     * @return AuthResult
     */
    public static AuthResult allow(String principal, Set<String> grantedAuthorities) {
        return new AuthResult(true, null, principal, grantedAuthorities);
    }

    /**
     * 构造拒绝结果。
     *
     * @param reason    拒绝原因
     * @param principal 主体（可空）
     * @return AuthResult
     */
    public static AuthResult deny(String reason, String principal) {
        return new AuthResult(false, reason, principal, Collections.emptySet());
    }

    public boolean isAllowed() { return allowed; }
    public String getReason() { return reason; }
    public String getPrincipal() { return principal; }
    public Set<String> getGrantedAuthorities() { return grantedAuthorities; }

    @Override
    public String toString() {
        return "AuthResult{allowed=" + allowed + ", principal='" + principal + '\''
                + (reason != null ? ", reason='" + reason + '\'' : "")
                + ", authorities=" + grantedAuthorities + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuthResult that)) return false;
        return allowed == that.allowed
                && Objects.equals(reason, that.reason)
                && Objects.equals(principal, that.principal)
                && Objects.equals(grantedAuthorities, that.grantedAuthorities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowed, reason, principal, grantedAuthorities);
    }
}