package com.levango7.dataenginebdp.common.security.audit.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 审计事件 JPA 持久化实体（C2：审计日志可查询）。
 *
 * <p>与 {@code AuditEvent} record 一一对应，字段语义一致；查询索引覆盖
 * 合规审计四类常见检索：按用户、按租户、按时间范围、按动作。</p>
 *
 * <p>仅当使用方配置 {@code app.audit.storage=database} 时由
 * {@code JpaAuditAutoConfiguration} 条件装配（common-security 自身不强制依赖 JPA）。</p>
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_user_time", columnList = "userId, timestamp"),
        @Index(name = "idx_audit_tenant_time", columnList = "tenantId, timestamp"),
        @Index(name = "idx_audit_action_time", columnList = "action, timestamp"),
        @Index(name = "idx_audit_resource", columnList = "resource, resourceId")
})
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 审计事件 ID（UUID，与日志文件中的 eventId 一致，双写对账用）。 */
    @Column(nullable = false, length = 64, unique = true)
    private String eventId;

    /** 事件时间。 */
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    /** 链路追踪 ID。 */
    @Column(length = 64)
    private String traceId;

    /** 操作人。 */
    @Column(length = 64)
    private String userId;

    /** 租户。 */
    @Column(length = 64)
    private String tenantId;

    /** 动作类型枚举名（AUTHENTICATION/DATA_OPERATION/CONFIG_CHANGE/...）。 */
    @Column(length = 32)
    private String actionType;

    /** 动作名（如 LOGIN / CREATE_DATASOURCE）。 */
    @Column(nullable = false, length = 128)
    private String action;

    /** 资源类型（datasource / dag / model）。 */
    @Column(length = 64)
    private String resource;

    /** 资源 ID。 */
    @Column(length = 128)
    private String resourceId;

    /** 来源 IP。 */
    @Column(length = 64)
    private String sourceIp;

    /** User-Agent。 */
    @Column(length = 512)
    private String userAgent;

    /** 请求方法。 */
    @Column(length = 16)
    private String requestMethod;

    /** 请求路径。 */
    @Column(length = 512)
    private String requestPath;

    /** 请求参数（已脱敏+截断的 JSON）。 */
    @Column(length = 4096)
    private String requestParams;

    /** HTTP 响应状态码。 */
    @Column
    private Integer responseStatus;

    /** 响应耗时（ms）。 */
    @Column
    private Long responseTimeMs;

    /** 结果枚举名（SUCCESS/FAILURE）。 */
    @Column(length = 16)
    private String result;

    /** 失败时的错误消息。 */
    @Column(length = 1024)
    private String errorMessage;

    /** 级别枚举名（INFO/WARN/ERROR/CRITICAL）。 */
    @Column(nullable = false, length = 16)
    private String level;

    /** 类别枚举名（DATA_OPERATION/SECURITY/...）。 */
    @Column(length = 32)
    private String category;

    public AuditLogEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public void setSourceIp(String sourceIp) {
        this.sourceIp = sourceIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public void setRequestMethod(String requestMethod) {
        this.requestMethod = requestMethod;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public void setRequestPath(String requestPath) {
        this.requestPath = requestPath;
    }

    public String getRequestParams() {
        return requestParams;
    }

    public void setRequestParams(String requestParams) {
        this.requestParams = requestParams;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public Long getResponseTimeMs() {
        return responseTimeMs;
    }

    public void setResponseTimeMs(Long responseTimeMs) {
        this.responseTimeMs = responseTimeMs;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
}
