package com.levango7.dataenginebdp.federated.routing;

import com.levango7.dataenginebdp.federated.model.TableLocation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨集群查询计划。
 *
 * <p>由路由器根据表定位信息生成，描述：
 * <ul>
 *   <li>涉及哪些集群</li>
 *   <li>每个集群执行什么 SQL（可能被改写以仅查询本地表）</li>
 *   <li>表与集群的映射</li>
 *   <li>是否跨集群</li>
 *   <li>归并策略</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryPlan {

    /** 原始 SQL。 */
    private String originalSql;

    /** 涉及的集群 → 该集群执行的 SQL。 */
    @Builder.Default
    private Map<String, String> clusterSqls = new LinkedHashMap<>();

    /** 涉及的集群列表（按顺序）。 */
    @Builder.Default
    private List<String> clusters = new ArrayList<>();

    /** 表全名 → 表定位。 */
    @Builder.Default
    private Map<String, TableLocation> tableLocations = new LinkedHashMap<>();

    /** 是否跨集群查询。 */
    private boolean crossCluster;

    /** 归并策略。 */
    private String mergeStrategy;

    /** 默认数据库。 */
    private String database;

    /** 计划生成时间戳（毫秒）。 */
    private long createdAt;
}