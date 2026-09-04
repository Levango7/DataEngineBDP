package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * API 密钥仓储（租户隔离）。
 */
@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {

    /** 列出租户下全部密钥（按创建时间倒序）。 */
    List<ApiKeyEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** 单个详情（租户隔离）。 */
    Optional<ApiKeyEntity> findByIdAndTenantId(Long id, String tenantId);

    /** 按 apiKey 查询（鉴权时使用）。 */
    Optional<ApiKeyEntity> findByApiKey(String apiKey);

    /** 统计租户下活跃密钥数。 */
    long countByTenantIdAndStatus(String tenantId, String status);

    /** A3 幂等性：租户内名称是否已存在（创建预检）。 */
    boolean existsByTenantIdAndName(String tenantId, String name);
}