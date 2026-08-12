package com.levango7.dataenginebdp.sqlgateway.repository;

import com.levango7.dataenginebdp.sqlgateway.model.RouteRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 路由规则持久化仓储。
 *
 * <p>基于 Spring Data JPA 的 {@link JpaRepository}，提供 RouteRule 的标准 CRUD 操作。
 * 无需编写实现类，Spring 在运行期自动生成代理实现。</p>
 *
 * @author shuqing-bigdata
 */
@Repository
public interface RouteRuleRepository extends JpaRepository<RouteRule, Long> {
}