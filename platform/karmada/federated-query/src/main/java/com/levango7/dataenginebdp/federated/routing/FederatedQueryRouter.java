package com.levango7.dataenginebdp.federated.routing;

import com.levango7.dataenginebdp.federated.catalog.TableLocationService;
import com.levango7.dataenginebdp.federated.config.FederatedQueryProperties;
import com.levango7.dataenginebdp.federated.model.TableLocation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨集群查询路由器。
 *
 * <p>核心流程：
 * <ol>
 *   <li>接收 SQL 查询</li>
 *   <li>通过 {@link TableLocationService} 解析 SQL 中的表，定位每张表所在集群</li>
 *   <li>生成查询计划：每个集群执行什么 SQL</li>
 *   <li>若所有表在同一集群 → 单集群查询（无需归并）</li>
 *   <li>若表分布在多集群 → 跨集群查询，需归并</li>
 * </ol>
 *
 * <p>SQL 改写策略（简化版）：
 * <ul>
 *   <li>当前实现：每个集群执行原始 SQL（依赖集群侧只返回本地表数据）</li>
 *   <li>生产增强：基于 Calcite 优化器改写 SQL，仅保留该集群拥有的表的子查询</li>
 * </ul>
 */
@Slf4j
@Component
public class FederatedQueryRouter {

    private final TableLocationService tableLocationService;
    private final FederatedQueryProperties props;

    public FederatedQueryRouter(TableLocationService tableLocationService,
                                FederatedQueryProperties props) {
        this.tableLocationService = tableLocationService;
        this.props = props;
    }

    /**
     * 生成查询计划。
     *
     * @param sql       SQL 语句
     * @param database  默认数据库
     * @param mergeStrategy 期望归并策略（null 则用配置默认）
     * @return 查询计划
     */
    public QueryPlan plan(String sql, String database, String mergeStrategy) {
        long start = System.currentTimeMillis();

        // 1. 解析表名
        Set<String> tableNames = tableLocationService.extractTableNames(sql);
        log.debug("Tables in SQL: {}", tableNames);

        // 2. 定位每张表
        Map<String, TableLocation> locations = tableLocationService.locateTables(sql, database);
        if (locations == null) {
            locations = new LinkedHashMap<>();
        }

        // 3. 收集涉及的集群（去重、保序）
        List<String> clusters = new ArrayList<>();
        for (TableLocation loc : locations.values()) {
            if (loc != null && !clusters.contains(loc.getCluster())) {
                clusters.add(loc.getCluster());
            }
        }

        // 4. 是否跨集群
        boolean crossCluster = clusters.size() >= 2;

        // 5. 生成每个集群的 SQL（当前：每个集群执行原始 SQL）
        Map<String, String> clusterSqls = new LinkedHashMap<>();
        for (String cluster : clusters) {
            clusterSqls.put(cluster, sql);
        }

        // 6. 归并策略
        String strategy = mergeStrategy != null ? mergeStrategy : props.getMerge().getStrategy();

        QueryPlan plan = QueryPlan.builder()
                .originalSql(sql)
                .clusterSqls(clusterSqls)
                .clusters(clusters)
                .tableLocations(locations)
                .crossCluster(crossCluster)
                .mergeStrategy(strategy)
                .database(database)
                .createdAt(start)
                .build();

        log.info("Query plan generated: crossCluster={}, clusters={}, tables={}, strategy={}, elapsed={}ms",
                crossCluster, clusters, tableNames, strategy, System.currentTimeMillis() - start);
        return plan;
    }

    /**
     * 生成单集群查询计划（降级时使用）。
     *
     * @param sql        SQL 语句
     * @param database   数据库
     * @param cluster    目标集群
     * @param mergeStrategy 归并策略
     * @return 单集群查询计划
     */
    public QueryPlan planSingleCluster(String sql, String database, String cluster, String mergeStrategy) {
        Map<String, String> clusterSqls = new LinkedHashMap<>();
        clusterSqls.put(cluster, sql);
        String strategy = mergeStrategy != null ? mergeStrategy : props.getMerge().getStrategy();
        return QueryPlan.builder()
                .originalSql(sql)
                .clusterSqls(clusterSqls)
                .clusters(List.of(cluster))
                .tableLocations(new LinkedHashMap<>())
                .crossCluster(false)
                .mergeStrategy(strategy)
                .database(database)
                .createdAt(System.currentTimeMillis())
                .build();
    }
}