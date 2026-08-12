package com.levango7.dataenginebdp.infra.cloud.repository;

import com.levango7.dataenginebdp.infra.cloud.model.CloudClusterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 云集群元数据 Repository。
 *
 * <p>基于 Spring Data JPA 自动实现，提供按 provider / workspace 查询能力。</p>
 */
@Repository
public interface CloudClusterRepository extends JpaRepository<CloudClusterEntity, String> {

    /**
     * 按 provider 与工作空间查询集群列表。
     *
     * @param provider    云 provider 标识（huawei / ali / tencent）
     * @param workspaceId 工作空间 ID
     * @return 集群列表
     */
    List<CloudClusterEntity> findByProviderAndWorkspaceId(String provider, String workspaceId);

    /**
     * 按 provider 查询所有集群。
     *
     * @param provider 云 provider 标识
     * @return 集群列表
     */
    List<CloudClusterEntity> findByProvider(String provider);

    /**
     * 按集群名称查询（用于唯一性校验）。
     *
     * @param clusterName 集群名称
     * @return 集群实体（若存在）
     */
    Optional<CloudClusterEntity> findByClusterName(String clusterName);
}