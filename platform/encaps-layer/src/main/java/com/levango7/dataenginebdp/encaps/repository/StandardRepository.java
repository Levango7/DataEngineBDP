package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.StandardEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // P2-1: 分页查询方法（防 OOM，限制单次查询加载量）
    Page<StandardEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    List<StandardEntity> findByTenantIdAndTypeOrderByCreatedAtDesc(String tenantId, String type);

    Optional<StandardEntity> findByIdAndTenantId(Long id, String tenantId);

    long countByTenantId(String tenantId);
}
