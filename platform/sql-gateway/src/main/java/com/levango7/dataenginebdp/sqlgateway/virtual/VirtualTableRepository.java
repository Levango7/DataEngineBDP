package com.levango7.dataenginebdp.sqlgateway.virtual;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 虚拟表元数据持久化仓储。
 *
 * <p>基于 Spring Data JPA 的 {@link JpaRepository}，提供 VirtualTableDefinition 的标准 CRUD 操作。
 * 额外定义按租户与表名查询的自定义方法，Spring 在运行期自动生成实现。</p>
 *
 * <p>租户隔离通过 {@code tenantId + tableName} 联合唯一约束保证，
 * 同一租户内虚拟表名唯一，不同租户可拥有同名虚拟表。</p>
 *
 * @author shuqing-bigdata
 */
@Repository
public interface VirtualTableRepository extends JpaRepository<VirtualTableDefinition, Long> {

    /**
     * 按租户 ID 列出全部虚拟表。
     *
     * @param tenantId 租户 ID
     * @return 虚拟表定义列表
     */
    List<VirtualTableDefinition> findByTenantId(String tenantId);

    /**
     * 按租户 ID 与虚拟表名查找（唯一）。
     *
     * @param tenantId  租户 ID
     * @param tableName 虚拟表名
     * @return 虚拟表定义（若存在）
     */
    Optional<VirtualTableDefinition> findByTenantIdAndTableName(String tenantId, String tableName);

    /**
     * 按租户 ID 与数据源类型列出虚拟表。
     *
     * @param tenantId       租户 ID
     * @param dataSourceType 数据源类型
     * @return 虚拟表定义列表
     */
    List<VirtualTableDefinition> findByTenantIdAndDataSourceType(String tenantId, DataSourceType dataSourceType);

    /**
     * 按物化策略列出虚拟表（用于定时刷新调度）。
     *
     * @param strategy 物化策略名
     * @return 虚拟表定义列表
     */
    List<VirtualTableDefinition> findByMaterializationStrategy(String strategy);

    /**
     * 按物化策略与启用状态列出虚拟表。
     *
     * @param strategy 物化策略名
     * @param enabled  是否启用
     * @return 虚拟表定义列表
     */
    List<VirtualTableDefinition> findByMaterializationStrategyAndEnabled(String strategy, Boolean enabled);

    /**
     * 判断指定租户下虚拟表名是否已存在。
     *
     * @param tenantId  租户 ID
     * @param tableName 虚拟表名
     * @return {@code true} 表示已存在
     */
    boolean existsByTenantIdAndTableName(String tenantId, String tableName);

    /**
     * 按租户 ID 删除全部虚拟表（用于租户清理）。
     *
     * @param tenantId 租户 ID
     */
    void deleteByTenantId(String tenantId);
}