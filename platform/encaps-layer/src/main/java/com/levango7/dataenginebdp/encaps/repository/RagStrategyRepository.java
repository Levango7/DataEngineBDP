package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.RagStrategyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * RAG 策略配置仓储。
 */
@Repository
public interface RagStrategyRepository extends JpaRepository<RagStrategyEntity, Long> {

    /** 按租户查询唯一策略。 */
    Optional<RagStrategyEntity> findByTenantId(String tenantId);
}