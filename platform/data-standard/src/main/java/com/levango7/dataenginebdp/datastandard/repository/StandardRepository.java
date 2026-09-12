package com.levango7.dataenginebdp.datastandard.repository;

import com.levango7.dataenginebdp.datastandard.model.entity.Standard;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 数据标准持久化仓储。
 *
 * <p>基于 Spring Data JPA 的 {@link JpaRepository}，提供 Standard 的标准 CRUD 操作。
 * 多租户隔离查询方法按 tenantId 过滤，避免跨租户数据泄漏。</p>
 */
@Repository
public interface StandardRepository extends JpaRepository<Standard, Long> {

    /**
     * 按租户 ID 分页查询数据标准。
     *
     * @param tenantId 租户 ID
     * @param pageable 分页参数
     * @return 该租户下的数据标准分页结果
     */
    Page<Standard> findByTenantId(String tenantId, Pageable pageable);

    /**
     * 按租户 ID 与分类分页查询数据标准。
     *
     * @param tenantId 租户 ID
     * @param category 标准分类
     * @param pageable 分页参数
     * @return 该租户下指定分类的数据标准分页结果
     */
    Page<Standard> findByTenantIdAndCategory(String tenantId, String category, Pageable pageable);

    /**
     * 按租户 ID 与状态分页查询数据标准。
     *
     * @param tenantId 租户 ID
     * @param status   标准状态
     * @param pageable 分页参数
     * @return 该租户下指定状态的数据标准分页结果
     */
    Page<Standard> findByTenantIdAndStatus(String tenantId, String status, Pageable pageable);

    /**
     * 按名称模糊 + 租户 ID 分页查询数据标准。
     *
     * @param name     名称关键词
     * @param tenantId 租户 ID
     * @param pageable 分页参数
     * @return 名称匹配的数据标准分页结果
     */
    @Query("SELECT s FROM Standard s WHERE s.tenantId = :tenantId AND s.name LIKE %:name%")
    Page<Standard> findByNameContainingAndTenantId(
            @Param("name") String name,
            @Param("tenantId") String tenantId,
            Pageable pageable);

    /**
     * 按标准 ID 与租户 ID 联合查询（租户隔离的详情 / 更新 / 删除前置校验）。
     *
     * @param id       标准 ID
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的标准；否则 {@link Optional#empty()}
     */
    Optional<Standard> findByIdAndTenantId(Long id, String tenantId);
}