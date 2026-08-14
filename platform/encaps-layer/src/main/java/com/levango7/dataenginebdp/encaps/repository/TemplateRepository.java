package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.TemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 行业模板仓储。
 */
@Repository
public interface TemplateRepository extends JpaRepository<TemplateEntity, Long> {

    List<TemplateEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<TemplateEntity> findByTenantIdAndIndustryOrderByCreatedAtDesc(String tenantId, String industry);

    Optional<TemplateEntity> findByIdAndTenantId(Long id, String tenantId);

    long countByTenantId(String tenantId);
}
