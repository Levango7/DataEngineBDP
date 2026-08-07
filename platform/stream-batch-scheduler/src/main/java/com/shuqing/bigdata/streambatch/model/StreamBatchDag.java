package com.shuqing.bigdata.streambatch.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流批统一 DAG（扩展 DolphinScheduler DAG 模型）。
 *
 * <p>一个 DAG 可同时包含 Spark 批节点与 Flink 流节点，由
 * {@link com.shuqing.bigdata.streambatch.dag.StreamBatchDagOrchestrator}
 * 统一编排执行。DAG 内节点通过 {@link DagEdge} 表达依赖关系，
 * 编排器按拓扑序调度，批节点读固定 snapshot、流节点读最新 snapshot，
 * 通过 Iceberg snapshot 隔离保证数据一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamBatchDag {

    /** DAG ID（全局唯一）。 */
    @NotBlank
    private String dagId;

    /** DAG 名称。 */
    @NotBlank
    private String name;

    /** DAG 描述。 */
    private String description;

    /** 节点列表。 */
    @NotEmpty
    @Valid
    @Builder.Default
    private List<DagNode> nodes = new ArrayList<>();

    /** 边列表（依赖关系）。 */
    @Valid
    @Builder.Default
    private List<DagEdge> edges = new ArrayList<>();

    /** DAG 自定义配置（透传给编排器）。 */
    @Builder.Default
    private Map<String, String> dagConfig = new HashMap<>();

    /**
     * 根据节点 ID 查找节点。
     *
     * @param nodeId 节点 ID
     * @return 节点；未找到返回 {@code null}
     */
    public DagNode findNode(String nodeId) {
        return nodes.stream()
                .filter(n -> n.getNodeId().equals(nodeId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取指定节点的所有上游节点 ID。
     *
     * @param nodeId 节点 ID
     * @return 上游节点 ID 列表
     */
    public List<String> upstreamOf(String nodeId) {
        return edges.stream()
                .filter(e -> e.getTarget().equals(nodeId))
                .map(DagEdge::getSource)
                .toList();
    }

    /**
     * 获取指定节点的所有下游节点 ID。
     *
     * @param nodeId 节点 ID
     * @return 下游节点 ID 列表
     */
    public List<String> downstreamOf(String nodeId) {
        return edges.stream()
                .filter(e -> e.getSource().equals(nodeId))
                .map(DagEdge::getTarget)
                .toList();
    }
}