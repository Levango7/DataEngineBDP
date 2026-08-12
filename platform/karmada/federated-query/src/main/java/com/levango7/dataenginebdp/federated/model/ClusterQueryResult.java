package com.levango7.dataenginebdp.federated.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单集群查询子结果（路由到某集群执行后返回）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterQueryResult {

    /** 集群名。 */
    private String cluster;

    /** 集群端点 URL。 */
    private String clusterUrl;

    /** 是否成功。 */
    private boolean success;

    /** 列模式：列名 → 类型。 */
    private Map<String, String> schema;

    /** 结果行。 */
    @Builder.Default
    private List<Map<String, Object>> rows = new ArrayList<>();

    /** 行数。 */
    private int rowCount;

    /** 耗时（毫秒）。 */
    private long elapsedMs;

    /** 错误信息（success=false 时）。 */
    private String error;

    /** 是否降级执行（仅查本地表）。 */
    private boolean degraded;
}