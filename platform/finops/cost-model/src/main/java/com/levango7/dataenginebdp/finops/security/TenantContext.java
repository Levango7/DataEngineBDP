package com.levango7.dataenginebdp.finops.security;

/**
 * 租户上下文持有器。
 *
 * <p>基于 {@link ThreadLocal} 在当前请求线程内传递租户 ID，
 * 必须在请求结束时通过 {@link #clear()} 清理，避免线程池复用导致的租户串号与内存泄漏。</p>
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static String getTenantId() {
        return TENANT_ID.get();
    }

    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
    }
}