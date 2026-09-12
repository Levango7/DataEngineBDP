package com.levango7.dataenginebdp.streambatch.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 作业仓储。
 */
@Repository
public interface JobRepository extends JpaRepository<JobEntity, Long> {

    Page<JobEntity> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId, Pageable pageable);

    /**
     * 按租户 + 工作空间分页查询（按创建时间倒序）。
     *
     * @param tenantId    租户 ID
     * @param workspaceId 工作空间 ID
     * @param pageable    分页参数
     * @return 作业分页
     */
    Page<JobEntity> findByTenantIdAndWorkspaceIdOrderByCreatedAtDesc(
            String tenantId, String workspaceId, Pageable pageable);

    /**
     * 按租户分页查询（按创建时间倒序）。
     *
     * @param tenantId 租户 ID
     * @param pageable 分页参数
     * @return 作业分页
     */
    Page<JobEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    /**
     * 按租户 + 作业 ID 查询（租户隔离的详情/更新/删除前置）。
     *
     * @param tenantId 租户 ID
     * @param id       作业 ID
     * @return 作业 Optional
     */
    java.util.Optional<JobEntity> findByTenantIdAndId(String tenantId, Long id);

    /**
     * 按租户 + 作业 ID 判断存在性（租户隔离的删除前置）。
     *
     * @param tenantId 租户 ID
     * @param id       作业 ID
     * @return 是否存在
     */
    boolean existsByTenantIdAndId(String tenantId, Long id);
}
