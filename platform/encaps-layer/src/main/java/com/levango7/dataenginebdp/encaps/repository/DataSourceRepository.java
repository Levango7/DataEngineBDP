package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.DataSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 数据源仓储。
 */
@Repository
public interface DataSourceRepository extends JpaRepository<DataSourceEntity, Long> {

    List<DataSourceEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<DataSourceEntity> findByTenantIdAndTypeOrderByCreatedAtDesc(String tenantId, String type);

    Optional<DataSourceEntity> findByIdAndTenantId(Long id, String tenantId);

    long countByTenantId(String tenantId);
}
