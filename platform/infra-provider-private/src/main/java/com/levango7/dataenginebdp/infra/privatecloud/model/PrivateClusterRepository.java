package com.levango7.dataenginebdp.infra.privatecloud.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 私有云集群信息 Spring Data JPA Repository。
 *
 * <p>提供按租户、provider 维度查询集群的能力。</p>
 *
 * @author shuqing-bigdata
 */
@Repository
public interface PrivateClusterRepository extends JpaRepository<PrivateClusterInfo, Long> {

    /**
     * 按租户 + provider 列出集群。
     *
     * @param tenantId 租户 ID
     * @param provider 云平台类型
     * @return 集群列表
     */
    List<PrivateClusterInfo> findByTenantIdAndProvider(String tenantId, String provider);

    /**
     * 按租户 + provider + 集群名查找（用于幂等创建校验）。
     *
     * @param tenantId    租户 ID
     * @param provider    云平台类型
     * @param clusterName 集群名
     * @return 集群信息（可能为空）
     */
    Optional<PrivateClusterInfo> findByTenantIdAndProviderAndClusterName(String tenantId,
                                                                         String provider,
                                                                         String clusterName);
}