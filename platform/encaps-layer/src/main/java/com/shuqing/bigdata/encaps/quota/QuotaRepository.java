package com.shuqing.bigdata.encaps.quota;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Quota 持久化仓储。
 *
 * <p>基于 Spring Data JPA 的 {@link JpaRepository}，提供 Quota 的标准 CRUD 操作。
 * 额外提供按租户 ID、Workspace ID 查询的派生方法，无需编写实现类。</p>
 */
@Repository
public interface QuotaRepository extends JpaRepository<Quota, Long> {

    /**
     * 按租户 ID 查询该租户下全部 Quota。
     *
     * @param tenantId 租户 ID
     * @return 该租户下的 Quota 列表（不会返回 null）
     */
    List<Quota> findByTenantId(Long tenantId);

    /**
     * 按 Workspace ID 查询 Quota（同一 Workspace 至多一条活跃 Quota）。
     *
     * @param workspaceId Workspace ID
     * @return Optional 包装的 Quota
     */
    Optional<Quota> findByWorkspaceId(Long workspaceId);

    /**
     * 按 Workspace ID 查询全部历史 Quota 记录（含已删除）。
     *
     * @param workspaceId Workspace ID
     * @return Quota 列表
     */
    List<Quota> findAllByWorkspaceId(Long workspaceId);
}