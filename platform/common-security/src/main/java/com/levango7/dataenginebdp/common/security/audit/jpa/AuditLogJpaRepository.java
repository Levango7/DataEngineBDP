package com.levango7.dataenginebdp.common.security.audit.jpa;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;

/**
 * 审计日志查询仓储（C2）。
 *
 * <p>Specification 驱动的动态组合查询：按用户/租户/动作/资源/时间范围
 * 任意组合过滤，供审计查询 API 使用；索引见 {@link AuditLogEntity}。</p>
 */
public interface AuditLogJpaRepository
        extends JpaRepository<AuditLogEntity, Long>, JpaSpecificationExecutor<AuditLogEntity> {

    /** 按事件 ID 精确查（双写对账用）。 */
    AuditLogEntity findByEventId(String eventId);

    /**
     * 时间范围内总量（合规报表：某时间段内审计事件数）。
     */
    long countByTimestampBetween(Instant from, Instant to);
}
