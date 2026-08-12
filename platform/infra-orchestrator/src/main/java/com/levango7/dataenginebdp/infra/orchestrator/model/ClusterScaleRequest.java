package com.levango7.dataenginebdp.infra.orchestrator.model;

import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 跨环境统一扩缩容请求。
 *
 * <p>对应 REST API：{@code POST /api/v1/clusters/{environment}/{clusterId}/scale}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterScaleRequest {

    /**
     * 目标节点数。
     */
    @Positive
    private int targetNodeCount;

    /**
     * 目标节点规格（可选，仅扩容时需要）。
     */
    private ClusterCreateRequest.NodeSpec nodeSpec;
}