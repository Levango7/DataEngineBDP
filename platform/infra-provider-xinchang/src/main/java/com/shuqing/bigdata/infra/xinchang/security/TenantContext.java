package com.shuqing.bigdata.infra.xinchang.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 租户上下文（ThreadLocal + Request 作用域）。
 *
 * <p>由 {@link JwtAuthFilter} 在请求入口注入，业务层通过 {@link #getTenantId()} 获取当前租户。</p>
 */
@Component
public class TenantContext {

    private static final String TENANT_ATTR = "tenantId";
    private static final String USER_ATTR = "userId";

    private TenantContext() {
    }

    /**
     * 设置租户 ID。
     *
     * @param tenantId 租户 ID
     */
    public static void setTenantId(String tenantId) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.setAttribute(TENANT_ATTR, tenantId, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /**
     * 获取当前租户 ID。
     *
     * @return 租户 ID；无上下文返回 {@code null}
     */
    public static String getTenantId() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        return (String) attrs.getAttribute(TENANT_ATTR, RequestAttributes.SCOPE_REQUEST);
    }

    /**
     * 设置用户 ID。
     *
     * @param userId 用户 ID
     */
    public static void setUserId(String userId) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.setAttribute(USER_ATTR, userId, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /**
     * 获取当前用户 ID。
     *
     * @return 用户 ID
     */
    public static String getUserId() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        return (String) attrs.getAttribute(USER_ATTR, RequestAttributes.SCOPE_REQUEST);
    }
}