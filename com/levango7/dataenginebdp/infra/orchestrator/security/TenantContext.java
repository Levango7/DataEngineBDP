package com.shuqing.bigdata.infra.orchestrator.security;

import org.springframework.stereotype.Component;

/**
 * 租户上下文 - ThreadLocal 持有当前请求的租户 ID 与用户 ID。
 *
 * <p>由 {@link JwtAuthFilter} 在请求入口写入，供应编排层在调用下游 Provider 时读取，
 * 用于多租户隔离与审计日志。请求结束后由 Filter 清理，避免线程池复用导致的上下文泄漏。</p>
 */
@Component
public final class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 设置当前租户 ID。
     *
     * @param tenantId 租户 ID
     */
    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    /**
     * 获取当前租户 ID。
     *
     * @return 租户 ID；未设置返回 null
     */
    public static String getTenantId() {
        return TENANT_ID.get();
    }

    /**
     * 设置当前用户 ID。
     *
     * @param userId 用户 ID
     */
    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    /**
     * 获取当前用户 ID。
     *
     * @return 用户 ID；未设置返回 null
     */
    public static String getUserId() {
        return USER_ID.get();
    }

    /**
     * 清理当前线程的上下文。
     */
    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
    }
}