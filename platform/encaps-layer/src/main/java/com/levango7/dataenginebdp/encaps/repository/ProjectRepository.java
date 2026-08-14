package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 项目仓储。
 */
@Repository
public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> {

    List<ProjectEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<ProjectEntity> findByTenantIdAndDomainOrderByCreatedAtDesc(String tenantId, String domain);

    Optional<ProjectEntity> findByIdAndTenantId(Long id, String tenantId);

    long countByTenantId(String tenantId);
}
