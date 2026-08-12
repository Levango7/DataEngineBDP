package com.levango7.dataenginebdp.sqlgateway.rewrite;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 改写规则持久化仓储。
 *
 * <p>基于 Spring Data JPA 的 {@link JpaRepository}，提供 {@link RewriteRule}
 * 的标准 CRUD 操作及按规则名、启用状态查询的自定义方法。</p>
 *
 * @author shuqing-bigdata
 */
@Repository
public interface RewriteRuleRepository extends JpaRepository<RewriteRule, Long> {

    /**
     * 按规则名查找。
     *
     * @param ruleName 规则名
     * @return 改写规则（唯一）
     */
    Optional<RewriteRule> findByRuleName(String ruleName);

    /**
     * 查询所有已启用的改写规则。
     *
     * @return 已启用的改写规则列表
     */
    List<RewriteRule> findByEnabledTrue();

    /**
     * 判断规则名是否已存在。
     *
     * @param ruleName 规则名
     * @return {@code true} 表示已存在
     */
    boolean existsByRuleName(String ruleName);
}