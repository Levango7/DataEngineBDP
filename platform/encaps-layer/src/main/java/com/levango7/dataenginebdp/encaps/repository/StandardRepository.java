package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.StandardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 主数据标准仓储。
 */
@Repository
public interface StandardRepository extends JpaRepository<StandardEntity, Long> {

    List<StandardEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<StandardEntity> findByTenantIdAndTypeOrderByCreatedAtDesc(String tenantId, String type);

    Optional<StandardEntity> findByIdAndTenantId(Long id, String tenantId);

    long countByTenantId(String tenantId);
}
