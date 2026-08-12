package com.levango7.dataenginebdp.infra.xinchang.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 集群元数据 Repository。
 */
@Repository
public interface ClusterRepository extends JpaRepository<ClusterEntity, String> {

    /**
     * 按租户 ID 查询全部集群。
     *
     * @param tenantId 租户 ID
     * @return 集群元数据列表
     */
    List<ClusterEntity> findByTenantId(String tenantId);

    /**
     * 按集群名称 + 租户 ID 查询。
     *
     * @param clusterName 集群名称
     * @param tenantId    租户 ID
     * @return 集群元数据
     */
    Optional<ClusterEntity> findByClusterNameAndTenantId(String clusterName, String tenantId);
}