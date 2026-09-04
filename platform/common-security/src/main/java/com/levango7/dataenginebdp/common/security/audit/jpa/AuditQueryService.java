package com.levango7.dataenginebdp.common.security.audit.jpa;

import com.levango7.dataenginebdp.common.security.audit.AuditEvent;
import com.levango7.dataenginebdp.common.security.audit.AuditLogService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

/**
 * 审计日志 JPA 查询服务（C2）：动态组合条件检索审计事件。
 *
 * <p>维度：userId / tenantId / action / resource / resourceId / 时间范围，
 * 任意组合、全部可选；由 {@code AuditQueryController} 暴露 REST 查询端点。</p>
 */
public class AuditQueryService {

    private static final Logger log = LoggerFactory.getLogger(AuditQueryService.class);

    private final AuditLogJpaRepository repository;

    public AuditQueryService(AuditLogJpaRepository repository) {
        this.repository = repository;
    }

    /**
     * 组合条件分页查询。
     *
     * @param userId    操作人（可空）
     * @param tenantId  租户（可空；super_admin 跨租户查，普通管理员只看本租户——Controller 层强制）
     * @param action    动作名（可空，如 LOGIN / CREATE_DATASOURCE）
     * @param resource  资源类型（可空）
     * @param resourceId 资源 ID（可空）
     * @param from      起始时间（可空）
     * @param to        结束时间（可空）
     * @param pageable  分页
     * @return 审计事件分页
     */
    public Page<AuditLogEntity> query(String userId, String tenantId, String action,
                                      String resource, String resourceId,
                                      Instant from, Instant to, Pageable pageable) {
        // 空 Specification 起始（避免 Specification.where(null) 重载歧义）
        Specification<AuditLogEntity> spec =
                (root, q, cb) -> cb.conjunction();
        if (userId != null && !userId.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("userId"), userId));
        }
        if (tenantId != null && !tenantId.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("tenantId"), tenantId));
        }
        if (action != null && !action.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("action"), action));
        }
        if (resource != null && !resource.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("resource"), resource));
        }
        if (resourceId != null && !resourceId.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("resourceId"), resourceId));
        }
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("timestamp"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("timestamp"), to));
        }
        return repository.findAll(spec, pageable);
    }

    /** JPA 持久化 Sink：AuditLogService 双写通道的数据库端。 */
    public static class JpaAuditSink implements AuditLogService.AuditSink {

        private final AuditLogJpaRepository repository;
        private volatile boolean tableReady = false;

        public JpaAuditSink(AuditLogJpaRepository repository) {
            this.repository = repository;
        }

        /** 启动自检：表不存在（如 DDL 未跑）时标记降级，避免每事件抛异常刷日志。 */
        @PostConstruct
        void selfCheck() {
            try {
                repository.count();
                tableReady = true;
                log.info("审计 JPA Sink 就绪（audit_log 表可写）");
            } catch (Exception e) {
                log.warn("审计 audit_log 表不可用（Sink 降级，仅写日志文件）: {}", e.getMessage());
            }
        }

        @Override
        public void persist(AuditEvent event) {
            if (!tableReady) {
                return;  // 降级：日志文件通道已保底
            }
            AuditLogEntity entity = new AuditLogEntity();
            entity.setEventId(event.eventId());
            entity.setTimestamp(event.timestamp());
            entity.setTraceId(event.traceId());
            entity.setUserId(event.userId());
            entity.setTenantId(event.tenantId());
            entity.setActionType(event.actionType() != null ? event.actionType().name() : null);
            entity.setAction(event.action());
            entity.setResource(event.resource());
            entity.setResourceId(event.resourceId());
            entity.setSourceIp(event.sourceIp());
            entity.setUserAgent(event.userAgent());
            entity.setRequestMethod(event.requestMethod());
            entity.setRequestPath(event.requestPath());
            entity.setRequestParams(event.requestParams());
            entity.setResponseStatus(event.responseStatus());
            entity.setResponseTimeMs(event.responseTimeMs());
            entity.setResult(event.result() != null ? event.result().name() : null);
            entity.setErrorMessage(event.errorMessage());
            entity.setLevel(event.level() != null ? event.level().name() : "INFO");
            entity.setCategory(event.category() != null ? event.category().name() : null);
            repository.save(entity);
        }
    }
}
