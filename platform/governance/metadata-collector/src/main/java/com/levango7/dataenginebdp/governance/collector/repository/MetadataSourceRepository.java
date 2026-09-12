package com.levango7.dataenginebdp.governance.collector.repository;

import com.levango7.dataenginebdp.governance.collector.model.MetadataSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 元数据采集源 Repository。
 *
 * <p>Spring Data JPA 自动生成实现，提供按名称查询等便捷方法。</p>
 *
 * <p>多租户隔离查询方法（{@code findByTenantId} / {@code findByIdAndTenantId}）
 * 供 {@link com.levango7.dataenginebdp.governance.collector.controller.CollectorController}
 * 按 tenantId 过滤，避免跨租户数据泄漏（R10 安全修复）。</p>
 */
@Repository
public interface MetadataSourceRepository extends JpaRepository<MetadataSource, Long> {

    /**
     * 按名称查找数据源。
     *
     * @param name 数据源名称
     * @return 数据源 Optional
     */
    Optional<MetadataSource> findByName(String name);

    /**
     * 按租户 ID 查询全部数据源。
     *
     * @param tenantId 租户 ID
     * @return 该租户下的数据源列表
     */
    List<MetadataSource> findByTenantId(String tenantId);

    /**
     * 按数据源 ID 与租户 ID 联合查询（租户隔离的详情/更新/删除前置校验）。
     *
     * @param id       数据源 ID
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的数据源；否则 {@link Optional#empty()}
     */
    Optional<MetadataSource> findByIdAndTenantId(Long id, String tenantId);
}
