package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.AssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 数据资产仓储。
 */
@Repository
public interface AssetRepository extends JpaRepository<AssetEntity, Long> {

    List<AssetEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<AssetEntity> findByTenantIdAndTypeOrderByCreatedAtDesc(String tenantId, String type);

    Optional<AssetEntity> findByIdAndTenantId(Long id, String tenantId);

    long countByTenantId(String tenantId);
}
