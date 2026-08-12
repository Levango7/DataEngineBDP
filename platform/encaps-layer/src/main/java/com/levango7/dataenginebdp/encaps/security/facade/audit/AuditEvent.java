package com.levango7.dataenginebdp.encaps.security.facade.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 审计事件。
 *
 * <p>不可变值对象，记录一次安全相关操作的完整上下文，
 * 供 {@link AuditFacade} 收集与 {@code EvidenceCollector} 归档。</p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code timestamp} — 事件发生时间（UTC，纳秒精度）</li>
 *   <li>{@code level}     — 事件级别</li>
 *   <li>{@code action}    — 操作名，如 {@code LOGIN} / {@code ENCRYPT} / {@code MASK}</li>
 *   <li>{@code tenantId}  — 租户 ID（可空，系统级事件）</li>
 *   <li>{@code userId}    — 用户 ID（可空）</li>
 *   <li>{@code resource}  — 操作目标资源标识，如 {@code /api/v1/tenants/1}</li>
 *   <li>{@code result}    — 操作结果，{@code SUCCESS} / {@code FAILURE} / {@code DENIED}</li>
 *   <li>{@code details}   — 附加键值对（如失败原因、影响行数）</li>
 * </ul>
 */
public final class AuditEvent {

    private final Instant timestamp;
    private final AuditLevel level;
    private final String action;
    private final String tenantId;
    private final String userId;
    private final String resource;
    private final String result;
    private final Map<String, String> details;

    /**
     * 全参构造。
     *
     * @param timestamp 时间戳
     * @param level     级别
     * @param action    操作名
     * @param tenantId  租户 ID
     * @param userId    用户 ID
     * @param resource  资源标识
     * @param result    结果
     * @param details   附加详情（会被拷贝为不可变 Map）
     */
    public AuditEvent(Instant timestamp, AuditLevel level, String action,
                      String tenantId, String userId, String resource,
                      String result, Map<String, String> details) {
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.level = Objects.requireNonNull(level, "level");
        this.action = Objects.requireNonNull(action, "action");
        this.tenantId = tenantId;
        this.userId = userId;
        this.resource = resource;
        this.result = result;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public Instant getTimestamp() { return timestamp; }
    public AuditLevel getLevel() { return level; }
    public String getAction() { return action; }
    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public String getResource() { return resource; }
    public String getResult() { return result; }
    public Map<String, String> getDetails() { return details; }

    /**
     * 转换为有序 Map（便于 JSON 序列化与归档）。
     *
     * @return LinkedHashMap，字段按声明顺序排列
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", timestamp.toString());
        map.put("level", level.name());
        map.put("action", action);
        map.put("tenantId", tenantId);
        map.put("userId", userId);
        map.put("resource", resource);
        map.put("result", result);
        map.put("details", details);
        return map;
    }

    @Override
    public String toString() {
        return "AuditEvent{" + timestamp + ", " + level + ", action='" + action + '\''
                + ", tenant=" + tenantId + ", user=" + userId
                + ", resource='" + resource + "', result='" + result + "', details=" + details + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditEvent that)) return false;
        return Objects.equals(timestamp, that.timestamp)
                && level == that.level
                && Objects.equals(action, that.action)
                && Objects.equals(tenantId, that.tenantId)
                && Objects.equals(userId, that.userId)
                && Objects.equals(resource, that.resource)
                && Objects.equals(result, that.result)
                && Objects.equals(details, that.details);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, level, action, tenantId, userId, resource, result, details);
    }

    /**
     * Builder 模式构造 AuditEvent。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * AuditEvent 构造器。
     */
    public static final class Builder {
        private Instant timestamp;
        private AuditLevel level;
        private String action;
        private String tenantId;
        private String userId;
        private String resource;
        private String result;
        private final Map<String, String> details = new LinkedHashMap<>();

        public Builder timestamp(Instant t) { this.timestamp = t; return this; }
        public Builder level(AuditLevel l) { this.level = l; return this; }
        public Builder action(String a) { this.action = a; return this; }
        public Builder tenantId(String t) { this.tenantId = t; return this; }
        public Builder userId(String u) { this.userId = u; return this; }
        public Builder resource(String r) { this.resource = r; return this; }
        public Builder result(String r) { this.result = r; return this; }
        public Builder detail(String key, String value) { this.details.put(key, value); return this; }

        /**
         * 构建事件，未设置 timestamp 时默认当前时间，level 默认 INFO，result 默认 SUCCESS。
         *
         * @return AuditEvent
         */
        public AuditEvent build() {
            if (timestamp == null) timestamp = Instant.now();
            if (level == null) level = AuditLevel.INFO;
            if (action == null) action = "UNKNOWN";
            if (result == null) result = "SUCCESS";
            return new AuditEvent(timestamp, level, action, tenantId, userId, resource, result, details);
        }
    }
}