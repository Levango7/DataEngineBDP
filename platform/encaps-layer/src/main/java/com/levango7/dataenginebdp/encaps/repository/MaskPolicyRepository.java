package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.MaskPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 脱敏策略仓储。
 */
@Repository
public interface MaskPolicyRepository extends JpaRepository<MaskPolicyEntity, Long> {

    List<MaskPolicyEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<MaskPolicyEntity> findByTenantIdAndAssetNameOrderByCreatedAtDesc(String tenantId, String assetName);

    Optional<MaskPolicyEntity> findByIdAndTenantId(Long id, String tenantId);

    long countByTenantId(String tenantId);
}
