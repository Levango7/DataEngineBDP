package com.shuqing.bigdata.encaps.repository;

import com.shuqing.bigdata.encaps.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 租户持久化仓储。
 *
 * <p>基于 Spring Data JPA 的 {@link JpaRepository}，提供 Tenant 的标准 CRUD 操作。
 * 无需编写实现类，Spring 在运行期自动生成代理实现。</p>
 */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {
}