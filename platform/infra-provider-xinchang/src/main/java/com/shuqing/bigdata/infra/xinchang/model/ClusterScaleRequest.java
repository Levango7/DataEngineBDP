package com.shuqing.bigdata.infra.xinchang.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 集群扩缩容请求。
 *
 * <p>对应 REST API：{@code POST /api/v1/clusters/xinchang/{clusterId}/scale}。
 * 扩容时填 {@link #addNodes}，缩容时填 {@link #removeHostnames}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterScaleRequest {

    /**
     * 扩容节点规格列表（与创建请求同结构）；缩容时为空。
     */
    @Valid
    private List<XinchangNodeSpec> addNodes;

    /**
     * 缩容节点主机名列表；扩容时为空。
     */
    @NotEmpty
    private List<String> removeHostnames;
}