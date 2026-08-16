package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.InferenceServiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 推理服务仓储。
 */
@Repository
public interface InferenceServiceRepository extends JpaRepository<InferenceServiceEntity, Long> {

    /** 列出租户下全部推理服务（按部署时间倒序）。 */
    List<InferenceServiceEntity> findByTenantIdOrderByDeployedAtDesc(String tenantId);

    /** 按状态过滤。 */
    List<InferenceServiceEntity> findByTenantIdAndStatusOrderByDeployedAtDesc(
            String tenantId, String status);

    /** 单个详情（租户隔离）。 */
    Optional<InferenceServiceEntity> findByIdAndTenantId(Long id, String tenantId);
}