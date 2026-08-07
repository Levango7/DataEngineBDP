package com.shuqing.bigdata.rule.engine.orchestrator.dag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAG 校验器。
 *
 * <p>提供环检测与拓扑排序能力。基于 Kahn 算法（入度消减）实现，
 * 既能判断是否有环，又能输出合法拓扑序。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>使用入度副本进行模拟消减，不修改原图状态；</li>
 *   <li>detectCycle 返回找到的环路径（节点 ID 列表），便于上层报错定位；</li>
 *   <li>topologicalSort 在存在环时抛出 IllegalStateException，调用方可先调用 detectCycle 判断。</li>
 * </ul>
 * </p>
 */
public final class DagValidator {

    private DagValidator() {
    }

    /**
     * 检测图中是否存在环。
     *
     * <p>采用 Kahn 算法：复制入度表，反复将入度为 0 的节点移除并消减其后继入度，
     * 若最终仍有节点未被移除，则存在环。</p>
     *
     * @param graph 待校验图
     * @return 存在环返回 true，否则 false
     */
    public static boolean hasCycle(DagGraph graph) {
        return !detectCycle(graph).isEmpty();
    }

    /**
     * 检测并返回一个环路径。
     *
     * <p>先用 Kahn 算法找出未被消减的节点集合（必在环中或依赖环），
     * 再在该子图上用 DFS 回溯出一个具体环，便于错误诊断。</p>
     *
     * @param graph 待校验图
     * @return 环上的节点 ID 列表（首尾相同形成闭合环）；无环返回空列表
     */
    public static List<String> detectCycle(DagGraph graph) {
        // 1. Kahn 算法找出所有可消减节点
        Map<String, Integer> inDegree = new HashMap<>();
        for (DagNode node : graph.allNodes()) {
            inDegree.put(node.getId(), node.getInDegree());
        }
        Set<String> removed = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
            }
        }
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            removed.add(current);
            for (String succ : graph.successors(current)) {
                int deg = inDegree.merge(succ, -1, Integer::sum);
                if (deg == 0) {
                    queue.add(succ);
                }
            }
        }
        // 2. 未被消减的节点即处于环或依赖环
        Set<String> inCycle = new LinkedHashSet<>();
        for (String id : graph.nodeIds()) {
            if (!removed.contains(id)) {
                inCycle.add(id);
            }
        }
        if (inCycle.isEmpty()) {
            return Collections.emptyList();
        }
        // 3. 在子图上 DFS 找一个具体环
        return findCyclePath(graph, inCycle);
    }

    /**
     * 拓扑排序。
     *
     * <p>基于 Kahn 算法返回一个合法拓扑序。若存在环则抛出异常。</p>
     *
     * @param graph 待排序图
     * @return 拓扑序节点 ID 列表
     * @throws IllegalStateException 图中存在环时抛出，消息包含环路径
     */
    public static List<String> topologicalSort(DagGraph graph) {
        List<String> cycle = detectCycle(graph);
        if (!cycle.isEmpty()) {
            throw new IllegalStateException("graph has cycle: " + cycle);
        }
        Map<String, Integer> inDegree = new HashMap<>();
        for (DagNode node : graph.allNodes()) {
            inDegree.put(node.getId(), node.getInDegree());
        }
        List<String> result = new ArrayList<>();
        List<String> queue = new ArrayList<>(graph.roots());
        while (!queue.isEmpty()) {
            // 取队首保证稳定顺序
            String current = queue.remove(0);
            result.add(current);
            for (String succ : graph.successors(current)) {
                int deg = inDegree.merge(succ, -1, Integer::sum);
                if (deg == 0) {
                    queue.add(succ);
                }
            }
        }
        return result;
    }

    /**
     * 校验图完整性：节点非空、边端点存在、无环。
     *
     * @param graph 待校验图
     * @throws IllegalStateException 图为空或存在环
     * @throws IllegalArgumentException 边端点缺失
     */
    public static void validate(DagGraph graph) {
        if (graph == null || graph.allNodes().isEmpty()) {
            throw new IllegalStateException("graph is empty");
        }
        for (DagEdge edge : graph.getEdges()) {
            if (graph.node(edge.getSource()) == null) {
                throw new IllegalArgumentException("edge source not found: " + edge.getSource());
            }
            if (graph.node(edge.getTarget()) == null) {
                throw new IllegalArgumentException("edge target not found: " + edge.getTarget());
            }
        }
        List<String> cycle = detectCycle(graph);
        if (!cycle.isEmpty()) {
            throw new IllegalStateException("graph has cycle: " + cycle);
        }
    }

    /**
     * 在指定节点子集上用 DFS 回溯出一个环路径。
     *
     * @param graph    原图
     * @param subNodes 子集节点 ID
     * @return 环路径（首尾相同）
     */
    private static List<String> findCyclePath(DagGraph graph, Set<String> subNodes) {
        Map<String, String> parent = new HashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String start : subNodes) {
            if (visited.contains(start)) {
                continue;
            }
            List<String> stack = new ArrayList<>();
            String meet = dfsCycle(graph, subNodes, start, parent, visited, stack);
            if (meet != null) {
                return buildCyclePath(meet, parent);
            }
        }
        // 理论上不会走到这里，兜底返回子集列表
        return new ArrayList<>(subNodes);
    }

    /**
     * DFS 寻找环：返回遇到已在当前栈中的节点 ID（环汇点）。
     */
    private static String dfsCycle(DagGraph graph, Set<String> subNodes, String current,
                                   Map<String, String> parent, Set<String> visited, List<String> stack) {
        visited.add(current);
        stack.add(current);
        for (String succ : graph.successors(current)) {
            if (!subNodes.contains(succ)) {
                continue;
            }
            if (stack.contains(succ)) {
                parent.put(succ, current);
                return succ;
            }
            if (!visited.contains(succ)) {
                parent.put(succ, current);
                String meet = dfsCycle(graph, subNodes, succ, parent, visited, stack);
                if (meet != null) {
                    return meet;
                }
            }
        }
        stack.remove(stack.size() - 1);
        return null;
    }

    /**
     * 由汇点回溯父指针构建环路径（首尾相同）。
     */
    private static List<String> buildCyclePath(String meet, Map<String, String> parent) {
        List<String> path = new ArrayList<>();
        String cursor = meet;
        path.add(cursor);
        String p = parent.get(cursor);
        while (p != null && !p.equals(meet)) {
            path.add(p);
            p = parent.get(p);
        }
        if (p != null) {
            path.add(p);
        }
        Collections.reverse(path);
        return path;
    }
}