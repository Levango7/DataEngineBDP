package com.levango7.dataenginebdp.masterdata.repository;

import com.levango7.dataenginebdp.masterdata.model.entity.MasterData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 主数据记录持久化仓储。
 *
 * <p>基于 Spring Data JPA 的 {@link JpaRepository}，提供 MasterData 的标准 CRUD 操作。
 * 多租户隔离查询方法按 tenantId 过滤，避免跨租户数据泄漏。</p>
 *
 * <p>同时提供分页查询方法（{@link Pageable}）与全量查询方法（{@link List}），
 * 分页方法用于 query 端点避免大数据量 OOM，全量方法保留给内部批处理场景。</p>
 */
@Repository
public interface MasterDataRepository extends JpaRepository<MasterData, Long> {

    /**
     * 按模型编码与租户 ID 查询全部主数据记录（全量，仅供内部批处理使用）。
     *
     * @param modelCode 模型编码
     * @param tenantId  租户 ID
     * @return 该租户下指定模型的全部分主数据记录
     */
    List<MasterData> findByModelCodeAndTenantId(String modelCode, String tenantId);

    /**
     * 按租户 ID 查询全部主数据记录（全量，仅供内部批处理使用）。
     *
     * @param tenantId 租户 ID
     * @return 该租户下的全部主数据记录
     */
    List<MasterData> findByTenantId(String tenantId);

    /**
     * 按模型编码与租户 ID 分页查询主数据记录（query 端点使用，避免 OOM）。
     *
     * @param modelCode 模型编码
     * @param tenantId  租户 ID
     * @param pageable  分页参数
     * @return 该租户下指定模型的主数据记录分页结果
     */
    Page<MasterData> findByModelCodeAndTenantId(String modelCode, String tenantId, Pageable pageable);

    /**
     * 按租户 ID 分页查询主数据记录（query 端点使用，避免 OOM）。
     *
     * @param tenantId 租户 ID
     * @param pageable 分页参数
     * @return 该租户下的主数据记录分页结果
     */
    Page<MasterData> findByTenantId(String tenantId, Pageable pageable);

    /**
     * 按记录 ID 与租户 ID 联合查询（租户隔离的详情 / 更新 / 删除前置校验）。
     *
     * @param id       记录 ID
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的记录；否则 {@link Optional#empty()}
     */
    Optional<MasterData> findByIdAndTenantId(Long id, String tenantId);
}