package com.levango7.dataenginebdp.ruleengine.repository;

import com.levango7.dataenginebdp.ruleengine.model.Rule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 规则持久化仓储。
 *
 * <p>基于 Spring Data JPA 的 {@link JpaRepository}，提供 Rule 的标准 CRUD 操作。
 * 无需编写实现类，Spring 在运行期自动生成代理实现。</p>
 *
 * <p>多租户隔离查询方法（{@code findByTenantId} / {@code findByIdAndTenantId}）
 * 供 {@link com.levango7.dataenginebdp.ruleengine.controller.QualityRuleController}
 * 按 tenantId 过滤，避免跨租户数据泄漏。</p>
 */
@Repository
public interface RuleRepository extends JpaRepository<Rule, Long> {

    /**
     * 按租户 ID 查询全部规则。
     *
     * @param tenantId 租户 ID
     * @return 该租户下的所有规则
     */
    List<Rule> findByTenantId(String tenantId);

    /**
     * 按规则 ID 与租户 ID 联合查询（租户隔离的详情/更新/删除前置校验）。
     *
     * @param id       规则 ID
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的规则；否则 {@link Optional#empty()}
     */
    Optional<Rule> findByIdAndTenantId(Long id, String tenantId);
}
