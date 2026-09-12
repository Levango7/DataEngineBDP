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

    /** 当前租户上下文（与 common-security 对齐）。
     * 若存在 ThreadLocal TenantContext，则由此注入；null 表示缺失，访问时 fail-closed。
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
            // fail-closed：租户上下文缺失时拒绝无隔离存储访问，禁止降级到公共前缀
            throw new IllegalStateException("缺少租户上下文，拒绝无隔离存储访问");
        }
        validateTenantId(currentTenantId);
        return currentTenantId + "/" + relativeKey;
    }

    /** 将相对对象键前缀（如 "warehouse/"）转换为租户前缀。 */
    public String toStoragePrefix(String relativePrefix) {
        if (relativePrefix == null || relativePrefix.isEmpty() || relativePrefix.equals("/")) {
            if (currentTenantId == null || currentTenantId.isEmpty()) {
                throw new IllegalStateException("缺少租户上下文，拒绝无隔离存储访问");
            }
            return currentTenantId + "/";
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
        if (currentTenantId == null || currentTenantId.isEmpty()) {
            throw new IllegalStateException("缺少租户上下文，拒绝无隔离存储访问");
        }
        String prefix = currentTenantId + "/";
        if (fullKey.startsWith(prefix)) {
            return fullKey.substring(prefix.length());
        }
        // 非本租户键：返回 null（触发权限校验失败，外部调用方应拒绝访问）
        return null;
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
            return null;
        }
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) {
            return null;
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
     * 解析当前租户 ID（与 common-security TenantContext 对齐，忽略 Spring 依赖）。
     * 若 thread local 中存在 tenantId，返回之，否则返回 null（由调用方 fail-closed）。
     */
    private static String resolveCurrentTenant() {
        try {
            // common-security Starter 提供 TenantContext（公共安全统一实现）
            Class<?> tenantContext = Class.forName(
                    "com.levango7.dataenginebdp.common.security.TenantContext");
            Object tenantId = tenantContext.getMethod("getTenantId").invoke(null);
            if (tenantId instanceof String) {
                return (String) tenantId;
            }
        } catch (Exception ignored) {
            // 忽略：不在含 TenantContext 的进程内时返回 null，由调用方 fail-closed
        }
        return null;
    }
}
