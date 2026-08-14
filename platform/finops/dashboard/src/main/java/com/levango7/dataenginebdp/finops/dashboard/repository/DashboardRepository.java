package com.levango7.dataenginebdp.finops.dashboard.repository;

import com.levango7.dataenginebdp.finops.dashboard.model.DashboardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 看板仓储。
 */
@Repository
public interface DashboardRepository extends JpaRepository<DashboardEntity, Long> {

    List<DashboardEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<DashboardEntity> findByIdAndTenantId(Long id, String tenantId);

    long countByTenantId(String tenantId);
}
