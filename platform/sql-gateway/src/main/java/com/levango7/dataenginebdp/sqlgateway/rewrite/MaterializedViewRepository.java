package com.levango7.dataenginebdp.sqlgateway.rewrite;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 物化视图定义持久化仓储。
 *
 * <p>基于 Spring Data JPA 的 {@link JpaRepository}，提供 {@link MaterializedViewDefinition}
 * 的标准 CRUD 操作及按视图名、启用状态查询的自定义方法。</p>
 *
 * <p>无需编写实现类，Spring 在运行期自动生成代理实现。</p>
 *
 * @author shuqing-bigdata
 */
@Repository
public interface MaterializedViewRepository extends JpaRepository<MaterializedViewDefinition, Long> {

    /**
     * 按视图名查找。
     *
     * @param viewName 视图名
     * @return 物化视图定义（唯一）
     */
    Optional<MaterializedViewDefinition> findByViewName(String viewName);

    /**
     * 查询所有已启用的物化视图定义。
     *
     * @return 已启用的物化视图列表
     */
    List<MaterializedViewDefinition> findByEnabledTrue();

    /**
     * 按源表名查找已启用的物化视图。
     *
     * @param sourceTable 源表名
     * @return 物化视图列表
     */
    List<MaterializedViewDefinition> findBySourceTableAndEnabledTrue(String sourceTable);

    /**
     * 判断视图名是否已存在。
     *
     * @param viewName 视图名
     * @return {@code true} 表示已存在
     */
    boolean existsByViewName(String viewName);
}