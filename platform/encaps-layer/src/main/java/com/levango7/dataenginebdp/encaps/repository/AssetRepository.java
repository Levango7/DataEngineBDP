package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.AssetEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // P2-1: 分页查询方法（防 OOM，限制单次查询加载量）
    Page<AssetEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    List<AssetEntity> findByTenantIdAndTypeOrderByCreatedAtDesc(String tenantId, String type);

    Optional<AssetEntity> findByIdAndTenantId(Long id, String tenantId);

    long countByTenantId(String tenantId);
}
