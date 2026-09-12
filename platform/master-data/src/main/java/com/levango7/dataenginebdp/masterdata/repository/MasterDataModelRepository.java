package com.levango7.dataenginebdp.masterdata.repository;

import com.levango7.dataenginebdp.masterdata.model.entity.MasterDataModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 主数据模型持久化仓储。
 *
 * <p>基于 Spring Data JPA 的 {@link JpaRepository}，提供 MasterDataModel 的标准 CRUD 操作。
 * 多租户隔离查询方法按 tenantId 过滤，避免跨租户数据泄漏。</p>
 */
@Repository
public interface MasterDataModelRepository extends JpaRepository<MasterDataModel, Long> {

    /**
     * 按租户 ID 查询全部主数据模型。
     *
     * @param tenantId 租户 ID
     * @return 该租户下的全部主数据模型
     */
    List<MasterDataModel> findByTenantId(String tenantId);

    /**
     * 按模型编码与租户 ID 联合查询。
     *
     * @param code     模型编码
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的模型；否则 {@link Optional#empty()}
     */
    Optional<MasterDataModel> findByCodeAndTenantId(String code, String tenantId);

    /**
     * 按模型 ID 与租户 ID 联合查询（租户隔离的详情 / 更新 / 删除前置校验）。
     *
     * @param id       模型 ID
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的模型；否则 {@link Optional#empty()}
     */
    Optional<MasterDataModel> findByIdAndTenantId(Long id, String tenantId);
}