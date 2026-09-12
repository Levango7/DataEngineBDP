package com.levango7.dataenginebdp.masterdata.repository;

import com.levango7.dataenginebdp.masterdata.model.entity.MasterData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 主数据记录持久化仓储。
 *
 * <p>基于 Spring Data JPA 的 {@link JpaRepository}，提供 MasterData 的标准 CRUD 操作。
 * 多租户隔离查询方法按 tenantId 过滤，避免跨租户数据泄漏。</p>
 */
@Repository
public interface MasterDataRepository extends JpaRepository<MasterData, Long> {

    /**
     * 按模型编码与租户 ID 查询全部主数据记录。
     *
     * @param modelCode 模型编码
     * @param tenantId  租户 ID
     * @return 该租户下指定模型的全部分主数据记录
     */
    List<MasterData> findByModelCodeAndTenantId(String modelCode, String tenantId);

    /**
     * 按租户 ID 查询全部主数据记录。
     *
     * @param tenantId 租户 ID
     * @return 该租户下的全部主数据记录
     */
    List<MasterData> findByTenantId(String tenantId);

    /**
     * 按记录 ID 与租户 ID 联合查询（租户隔离的详情 / 更新 / 删除前置校验）。
     *
     * @param id       记录 ID
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的记录；否则 {@link Optional#empty()}
     */
    Optional<MasterData> findByIdAndTenantId(Long id, String tenantId);
}