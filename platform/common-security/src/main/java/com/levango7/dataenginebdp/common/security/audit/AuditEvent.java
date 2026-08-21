package com.levango7.dataenginebdp.common.security.audit;

import java.time.Instant;

/**
 * 操作审计事件（v2.1 审计合规增强）。
 *
 * <p>记录用户操作全链路信息，满足等保三级与金融行业审计要求。
 * 审计事件覆盖：登录/登出、数据访问、配置变更、权限变更、API 调用等。</p>
 *
 * @param eventId        审计事件 ID（唯一标识）
 * @param timestamp      事件时间戳
 * @param traceId        链路追踪 ID（跨服务关联）
 * @param userId         操作用户 ID
 * @param tenantId       租户 ID
 * @param actionType     操作类型
 * @param action         操作动作（如 LOGIN、QUERY、UPDATE、DELETE）
 * @param resource       操作资源（如 /api/v1/clusters、table:users）
 * @param resourceId     资源 ID
 * @param sourceIp       来源 IP
 * @param userAgent      User-Agent
 * @param requestMethod  HTTP 方法
 * @param requestPath    请求路径
 * @param requestParams  请求参数（脱敏后）
 * @param responseStatus 响应状态码
 * @param responseTimeMs 响应耗时（毫秒）
 * @param result         操作结果（SUCCESS/FAILURE/ERROR）
 * @param errorMessage   错误信息（失败时）
 * @param sessionId      会话 ID
 * @param level          审计级别
 * @param category       审计分类
 * @param metadata       附加元数据
 */
public record AuditEvent(
        String eventId,
        Instant timestamp,
        String traceId,
        String userId,
        String tenantId,
        ActionType actionType,
        String action,
        String resource,
        String resourceId,
        String sourceIp,
        String userAgent,
        String requestMethod,
        String requestPath,
        String requestParams,
        int responseStatus,
        long responseTimeMs,
        Result result,
        String errorMessage,
        String sessionId,
        Level level,
        Category category,
        String metadata
) {

    /**
     * 操作类型（等保三级要求覆盖的审计动作）。
     */
    public enum ActionType {
        /** 登录。 */
        LOGIN,
        /** 登出。 */
        LOGOUT,
        /** 查询/读取。 */
        QUERY,
        /** 新增。 */
        CREATE,
        /** 修改。 */
        UPDATE,
        /** 删除。 */
        DELETE,
        /** 权限变更。 */
        PERMISSION_CHANGE,
        /** 配置变更。 */
        CONFIG_CHANGE,
        /** 数据导出。 */
        DATA_EXPORT,
        /** 数据导入。 */
        DATA_IMPORT,
        /** 系统操作。 */
        SYSTEM
    }

    /**
     * 操作结果。
     */
    public enum Result {
        /** 成功。 */
        SUCCESS,
        /** 失败（业务失败）。 */
        FAILURE,
        /** 错误（系统错误）。 */
        ERROR
    }

    /**
     * 审计级别。
     */
    public enum Level {
        /** 普通（INFO）。 */
        INFO,
        /** 重要（WARN，如权限变更）。 */
        IMPORTANT,
        /** 关键（ERROR，如登录失败、敏感数据访问）。 */
        CRITICAL
    }

    /**
     * 审计分类（等保三级要求）。
     */
    public enum Category {
        /** 用户身份认证。 */
        AUTHENTICATION,
        /** 访问控制。 */
        ACCESS_CONTROL,
        /** 数据操作。 */
        DATA_OPERATION,
        /** 系统管理。 */
        SYSTEM_ADMIN,
        /** 安全事件。 */
        SECURITY_EVENT,
        /** 配置管理。 */
        CONFIGURATION
    }
}