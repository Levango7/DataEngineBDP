package com.levango7.dataenginebdp.streambatch.dag;

import com.levango7.dataenginebdp.streambatch.model.StreamBatchDag;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * DAG 拓扑排序与校验工具。
 *
 * <p>提供：
 * <ul>
 *   <li>{@link #topologicalSort(StreamBatchDag)} — 拓扑排序，返回节点执行顺序</li>
 *   <li>{@link #validateDag(StreamBatchDag)} — DAG 校验（无环、节点存在、依赖完整）</li>
 * </ul>
 */
@Slf4j
public final class DagTopologicalSorter {

    private DagTopologicalSorter() {
    }

    /**
     * 对 DAG 进行拓扑排序（Kahn 算法）。
     *
     * @param dag 流批 DAG
     * @return 拓扑序节点 ID 列表
     * @throws IllegalArgumentException DAG 存在环或节点缺失
     */
    public static List<String> topologicalSort(StreamBatchDag dag) {
        validateDag(dag);

        // 计算入度
        Map<String, Integer> inDegree = new HashMap<>();
        dag.getNodes().forEach(n -> inDegree.put(n.getNodeId(), 0));
        dag.getEdges().forEach(e -> inDegree.merge(e.getTarget(), 1, Integer::sum));

        // 入度为 0 的节点入队
        Queue<String> queue = new LinkedList<>();
        inDegree.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .forEach(e -> queue.add(e.getKey()));

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);
            for (String downstream : dag.downstreamOf(current)) {
                int newDegree = inDegree.merge(downstream, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(downstream);
                }
            }
        }

        if (sorted.size() != dag.getNodes().size()) {
            throw new IllegalArgumentException("DAG 存在环，无法拓扑排序: dagId=" + dag.getDagId());
        }

        log.debug("DAG 拓扑排序完成: dagId={}, order={}", dag.getDagId(), sorted);
        return sorted;
    }

    /**
     * 校验 DAG 合法性。
     *
     * @param dag 流批 DAG
     * @throws IllegalArgumentException DAG 不合法
     */
    public static void validateDag(StreamBatchDag dag) {
        if (dag == null) {
            throw new IllegalArgumentException("DAG 不能为 null");
        }
        if (dag.getDagId() == null || dag.getDagId().isEmpty()) {
            throw new IllegalArgumentException("DAG ID 不能为空");
        }
        if (dag.getNodes() == null || dag.getNodes().isEmpty()) {
            throw new IllegalArgumentException("DAG 节点列表不能为空: dagId=" + dag.getDagId());
        }

        // 节点 ID 唯一性
        Set<String> nodeIds = new HashSet<>();
        for (var node : dag.getNodes()) {
            if (!nodeIds.add(node.getNodeId())) {
                throw new IllegalArgumentException("DAG 节点 ID 重复: " + node.getNodeId());
            }
        }

        // 边引用的节点必须存在
        if (dag.getEdges() != null) {
            for (var edge : dag.getEdges()) {
                if (!nodeIds.contains(edge.getSource())) {
                    throw new IllegalArgumentException(
                            "DAG 边 source 节点不存在: " + edge.getSource());
                }
                if (!nodeIds.contains(edge.getTarget())) {
                    throw new IllegalArgumentException(
                            "DAG 边 target 节点不存在: " + edge.getTarget());
                }
            }
        }

        // 环检测（通过拓扑排序）
        if (dag.getEdges() != null && !dag.getEdges().isEmpty()) {
            detectCycle(dag, nodeIds);
        }
    }

    /**
     * 环检测（DFS 三色标记法）。
     */
    private static void detectCycle(StreamBatchDag dag, Set<String> nodeIds) {
        Map<String, Integer> color = new HashMap<>();
        nodeIds.forEach(n -> color.put(n, 0)); // 0=白, 1=灰, 2=黑

        Map<String, List<String>> adj = new HashMap<>();
        nodeIds.forEach(n -> adj.put(n, new ArrayList<>()));
        dag.getEdges().forEach(e -> adj.get(e.getSource()).add(e.getTarget()));

        for (String node : nodeIds) {
            if (color.get(node) == 0) {
                if (dfsDetectCycle(node, adj, color)) {
                    throw new IllegalArgumentException("DAG 存在环: dagId=" + dag.getDagId());
                }
            }
        }
    }

    private static boolean dfsDetectCycle(
            String node, Map<String, List<String>> adj, Map<String, Integer> color) {
        color.put(node, 1); // 灰
        for (String neighbor : adj.get(node)) {
            if (color.get(neighbor) == 1) {
                return true; // 回边，存在环
            }
            if (color.get(neighbor) == 0 && dfsDetectCycle(neighbor, adj, color)) {
                return true;
            }
        }
        color.put(node, 2); // 黑
        return false;
    }
}