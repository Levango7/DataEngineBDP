package com.shuqing.bigdata.federated.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 跨集群查询响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FederatedQueryResponse {

    /** 查询 ID。 */
    private String queryId;

    /** 查询状态：SUCCESS / DEGRADED / PARTIAL / FAILED。 */
    private String status;

    /** 列模式：列名 → 类型。 */
    private Map<String, String> schema;

    /** 归并后的结果行（每行为列名 → 值）。 */
    @Builder.Default
    private List<Map<String, Object>> rows = new ArrayList<>();

    /** 总行数。 */
    private int totalRows;

    /** 涉及的集群列表。 */
    @Builder.Default
    private List<String> clusters = new ArrayList<>();

    /** 是否降级。 */
    private boolean degraded;

    /** 降级原因（若 degraded=true）。 */
    private String degradeReason;

    /** 触发的告警列表。 */
    @Builder.Default
    private List<DegradationAlert> alerts = new ArrayList<>();

    /** 执行耗时（毫秒）。 */
    private long elapsedMs;

    /** 服务端时间戳。 */
    private Instant timestamp;

    /** 错误信息（status=FAILED 时）。 */
    private String error;
}