package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.ApiDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * API 定义仓储。
 */
@Repository
public interface ApiDefinitionRepository extends JpaRepository<ApiDefinitionEntity, Long> {

    List<ApiDefinitionEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<ApiDefinitionEntity> findByTenantIdAndCategoryOrderByCreatedAtDesc(String tenantId, String category);

    Optional<ApiDefinitionEntity> findByIdAndTenantId(Long id, String tenantId);

    long countByTenantId(String tenantId);
}
