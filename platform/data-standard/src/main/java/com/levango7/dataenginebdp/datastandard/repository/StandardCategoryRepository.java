package com.levango7.dataenginebdp.datastandard.repository;

import com.levango7.dataenginebdp.datastandard.model.entity.StandardCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 数据标准分类持久化仓储。
 *
 * <p>基于 Spring Data JPA 的 {@link JpaRepository}，提供 StandardCategory 的标准 CRUD 操作。
 * 多租户隔离查询方法按 tenantId 过滤，避免跨租户数据泄漏。</p>
 */
@Repository
public interface StandardCategoryRepository extends JpaRepository<StandardCategory, Long> {

    /**
     * 按租户 ID 查询全部分类。
     *
     * @param tenantId 租户 ID
     * @return 该租户下的全部分类
     */
    List<StandardCategory> findByTenantId(String tenantId);

    /**
     * 按分类编码与租户 ID 联合查询。
     *
     * @param code     分类编码
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的分类；否则 {@link Optional#empty()}
     */
    Optional<StandardCategory> findByCodeAndTenantId(String code, String tenantId);

    /**
     * 按分类 ID 与租户 ID 联合查询（租户隔离的详情 / 更新 / 删除前置校验）。
     *
     * @param id       分类 ID
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的分类；否则 {@link Optional#empty()}
     */
    Optional<StandardCategory> findByIdAndTenantId(Long id, String tenantId);
}