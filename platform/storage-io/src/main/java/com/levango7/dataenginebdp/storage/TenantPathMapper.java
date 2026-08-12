package com.levango7.dataenginebdp.storage;

import java.util.Set;

/**
 * 租户路径映射：将相对对象键转换为租户隔离后的完整存储键。
 *
 * <p>规则：
 * <ul>
 *   <li>默认引用不使用租户前缀（如公共资源）</li>
 *   <li>租户写库：key = {tenantId}/{key}</li>
 *   <li>租户读库：key = {tenantId}/{key}</li>
 *   <li>系统根路径（不属于任何租户）：key = {systemPrefix}/{key}</li>
 * </ul>
 */
public class TenantPathMapper {

    /** 租户ID 白名单（防止/绕过路径逃逸）。 */
    private static final Set<String> RESERVED_TENANTS = Set.of("system", "internal");

    /** 系统根路径前缀（平台内部使用，不归属租户）。 */
    private static final String SYSTEM_PREFIX = "_system";

    /** 当前租户上下文（与 encaps-layer 对齐）。
     * 若存在 ThreadLocal TenantContext，则由此注入；否则默认 system。
     */
    private final String currentTenantId;

    public TenantPathMapper() {
        this(resolveCurrentTenant());
    }

    public TenantPathMapper(String currentTenantId) {
        this.currentTenantId = sanitizeTenantId(currentTenantId);
    }

    /** 将相对对象键转换为完整存储键。 */
    public String toStorageKey(String relativeKey) {
        if (relativeKey == null || relativeKey.isBlank()) {
            throw new IllegalArgumentException("relativeKey must not be blank");
        }
        if (isSystemKey(relativeKey)) {
            return relativeKey;
        }
        if (currentTenantId == null || currentTenantId.isEmpty()) {
            // 租户上下文缺失：降级到公共前缀（不隔离）
            return relativeKey;
        }
        validateTenantId(currentTenantId);
        return currentTenantId + "/" + relativeKey;
    }

    /** 将相对对象键前缀（如 "warehouse/"）转换为租户前缀。 */
    public String toStoragePrefix(String relativePrefix) {
        if (relativePrefix == null || relativePrefix.isEmpty() || relativePrefix.equals("/")) {
            return currentTenantId != null && !currentTenantId.isEmpty() ? currentTenantId + "/" : "/";
        }
        return toStorageKey(relativePrefix);
    }

    /** 从完整存储键剥离租户前缀，返回相对对象键。 */
    public String stripTenantPrefix(String fullKey) {
        if (fullKey == null) {
            return null;
        }
        if (isSystemKey(fullKey)) {
            return fullKey;
        }
        if (currentTenantId != null && !currentTenantId.isEmpty()) {
            String prefix = currentTenantId + "/";
            if (fullKey.startsWith(prefix)) {
                return fullKey.substring(prefix.length());
            }
            // 非本租户键：返回 null（触发权限校验失败，外部调用方应拒绝访问）
            return null;
        }
        return fullKey;
    }

    /** 获取当前绑定租户 ID。 */
    public String getCurrentTenantId() {
        return currentTenantId;
    }

    private static boolean isSystemKey(String key) {
        return key.startsWith(SYSTEM_PREFIX + "/");
    }

    private static String sanitizeTenantId(String raw) {
        if (raw == null) {
            return "system";
        }
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) {
            return "system";
        }
        // 防止路径逃逸（如 ../）
        if (s.contains("..") || s.contains("/") || s.contains("\\")) {
            throw new IllegalArgumentException("非法租户 ID: " + raw);
        }
        return s;
    }

    private static void validateTenantId(String tenantId) {
        if (RESERVED_TENANTS.contains(tenantId)) {
            throw new IllegalArgumentException("保留租户 ID: " + tenantId);
        }
    }

    /**
     * 解析当前租户 ID（与 encaps-layer TenantContext 对齐，忽略 Spring 依赖）。
     * 若 thread local 中存在 tenantId，返回之，否则返回 null。
     */
    private static String resolveCurrentTenant() {
        try {
            // 封装层已在运行时注入 TenantContext（若是同一进程）
            Class<?> tenantContext = Class.forName(
                    "com.levango7.dataenginebdp.encaps.security.TenantContext");
            Object tenantId = tenantContext.getMethod("getCurrentTenantId").invoke(null);
            if (tenantId instanceof String) {
                return (String) tenantId;
            }
        } catch (Exception ignored) {
            // 忽略：不在 encaps-layer 进程内时使用默认 system
        }
        return null;
    }
}
