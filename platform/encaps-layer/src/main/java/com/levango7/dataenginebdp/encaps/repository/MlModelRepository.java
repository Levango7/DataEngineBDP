package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.MlModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 机器学习模型仓储。
 */
@Repository
public interface MlModelRepository extends JpaRepository<MlModelEntity, Long> {

    /** 列出租户下全部模型（按注册时间倒序）。 */
    List<MlModelEntity> findByTenantIdOrderByRegisteredAtDesc(String tenantId);

    /** 按算法过滤。 */
    List<MlModelEntity> findByTenantIdAndAlgorithmOrderByRegisteredAtDesc(String tenantId, String algorithm);

    /** 按模型名查全部版本。 */
    List<MlModelEntity> findByTenantIdAndNameOrderByRegisteredAtDesc(String tenantId, String name);

    /** 按关键字模糊匹配模型名。 */
    List<MlModelEntity> findByTenantIdAndNameContainingIgnoreCaseOrderByRegisteredAtDesc(
            String tenantId, String keyword);

    /** 按算法 + 关键字模糊匹配。 */
    List<MlModelEntity> findByTenantIdAndAlgorithmAndNameContainingIgnoreCaseOrderByRegisteredAtDesc(
            String tenantId, String algorithm, String keyword);

    /** 单个详情（租户隔离）。 */
    Optional<MlModelEntity> findByIdAndTenantId(Long id, String tenantId);
}