package com.levango7.dataenginebdp.finops.repository;

import com.levango7.dataenginebdp.finops.model.QueryMeteringRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 查询计量仓储。
 */
@Repository
public interface QueryMeteringRepository extends JpaRepository<QueryMeteringRecord, Long> {

    /**
     * 幂等键查询：租户 + 客户端请求 ID 唯一。
     */
    Optional<QueryMeteringRecord> findByTenantIdAndClientRequestId(
            String tenantId, String clientRequestId);

    /**
     * 按租户 + 时间窗口聚合查询（计费用）。
     */
    List<QueryMeteringRecord> findByTenantIdAndCreatedAtBetweenOrderByCreatedAtAsc(
            String tenantId, Instant start, Instant end);

    /**
     * 删除某租户指定时间前的计量（保留期清理）。
     */
    long deleteByTenantIdAndCreatedAtBefore(String tenantId, Instant before);
}