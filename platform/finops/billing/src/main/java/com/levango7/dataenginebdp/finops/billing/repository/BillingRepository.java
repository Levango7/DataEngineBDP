package com.levango7.dataenginebdp.finops.billing.repository;

import com.levango7.dataenginebdp.finops.billing.model.BillingModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 账单持久化仓库（JPA）。
 *
 * <p>基于 Spring Data JPA 自动实现，开发环境 H2 文件模式，
 * 生产环境通过环境变量切换 PostgreSQL。</p>
 */
@Repository
public interface BillingRepository extends JpaRepository<BillingModel, String> {

    /**
     * 按租户 ID 查询全部账单（按生成时间倒序）。
     *
     * @param tenantId 租户 ID
     * @return 账单列表
     */
    List<BillingModel> findByTenantIdOrderByGeneratedAtDesc(String tenantId);

    /**
     * 按租户 ID 与账期查询账单（幂等检查：同一租户同一账期是否已生成）。
     *
     * @param tenantId     租户 ID
     * @param billingPeriod 账期
     * @return 账单（若存在）
     */
    Optional<BillingModel> findByTenantIdAndBillingPeriod(String tenantId, String billingPeriod);

    /**
     * 按账期查询全部租户账单。
     *
     * @param billingPeriod 账期
     * @return 账单列表
     */
    List<BillingModel> findByBillingPeriod(String billingPeriod);

    /**
     * 按状态查询账单（如查询所有未结算账单）。
     *
     * @param status 账单状态
     * @return 账单列表
     */
    List<BillingModel> findByStatus(String status);
}