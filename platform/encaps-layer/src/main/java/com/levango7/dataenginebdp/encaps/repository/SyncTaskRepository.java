package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.SyncTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 数据集成任务仓储。
 */
@Repository
public interface SyncTaskRepository extends JpaRepository<SyncTaskEntity, Long> {

    List<SyncTaskEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<SyncTaskEntity> findByIdAndTenantId(Long id, String tenantId);

    long countByTenantId(String tenantId);
}
