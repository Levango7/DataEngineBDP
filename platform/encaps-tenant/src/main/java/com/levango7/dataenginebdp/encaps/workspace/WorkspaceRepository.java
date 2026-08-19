package com.levango7.dataenginebdp.encaps.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Workspace 持久化仓储。
 *
 * <p>基于 Spring Data JPA 的 {@link JpaRepository}，提供 Workspace 的标准 CRUD 操作。
 * 额外提供按租户 ID 查询的派生方法，无需编写实现类。</p>
 */
@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    /**
     * 按租户 ID 查询该租户下全部 Workspace。
     *
     * @param tenantId 租户 ID
     * @return 该租户下的 Workspace 列表（不会返回 null）
     */
    List<Workspace> findByTenantId(Long tenantId);
}