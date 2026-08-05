package com.shuqing.bigdata.encaps.security;

/**
 * 租户上下文持有器。
 *
 * <p>基于 {@link ThreadLocal} 在当前请求线程内传递租户 ID，
 * 必须在请求结束时通过 {@link #clear()} 清理，避免线程池复用导致的租户串号与内存泄漏。</p>
 *
 * <p>典型用法：在 {@code JwtAuthFilter} 的 {@code doFilterInternal} 中
 * 解析 JWT 后调用 {@link #setTenantId(String)}，并在 finally 块或
 * {@code OncePerRequestFilter#afterCompletion} 中调用 {@link #clear()}。</p>
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 设置当前请求的租户 ID。
     *
     * @param tenantId 租户 ID（来自 JWT claim {@code tenantId}）
     */
    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    /**
     * 获取当前请求的租户 ID。
     *
     * @return 租户 ID；若上下文未设置则返回 {@code null}
     */
    public static String getTenantId() {
        return TENANT_ID.get();
    }

    /**
     * 设置当前请求的用户 ID。
     *
     * @param userId 用户 ID（来自 JWT claim {@code sub}）
     */
    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    /**
     * 获取当前请求的用户 ID。
     *
     * @return 用户 ID；若上下文未设置则返回 {@code null}
     */
    public static String getUserId() {
        return USER_ID.get();
    }

    /**
     * 清理当前线程的租户与用户上下文。
     *
     * <p>必须在请求处理完成后调用，避免线程池复用导致的上下文串号。</p>
     */
    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
    }
}