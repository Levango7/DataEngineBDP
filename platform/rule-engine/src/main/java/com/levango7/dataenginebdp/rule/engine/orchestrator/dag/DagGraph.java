package com.levango7.dataenginebdp.rule.engine.orchestrator.dag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * DAG 图模型。
 *
 * <p>有向无环图，由节点集合与边集合构成。提供邻接表维护、入度计算、
 * 拓扑排序等基础能力，是编排引擎的核心数据结构。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>使用 LinkedHashMap/LinkedHashSet 保持插入顺序，便于稳定输出与调试；</li>
 *   <li>id 字段为整图唯一标识，用于在调度器/服务层引用一个完整编排实例；</li>
 *   <li>status 字段反映整图运行时状态，由调度器维护。</li>
 * </ul>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DagGraph {

    /** 图状态常量 */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_STOPPED = "STOPPED";

    /** 图唯一标识 */
    private String id;

    /** 图名称 */
    private String name;

    /** 图描述 */
    private String description;

    /** 节点集合（按 id 索引，保持插入顺序） */
    @Builder.Default
    private Map<String, DagNode> nodes = new LinkedHashMap<>();

    /** 边集合 */
    @Builder.Default
    private List<DagEdge> edges = new ArrayList<>();

    /** 图运行时状态 */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 开始执行时间 */
    private LocalDateTime startedAt;

    /** 结束执行时间 */
    private LocalDateTime finishedAt;

    /**
     * 添加节点。若已存在同 id 节点则覆盖。
     *
     * @param node 待加入节点
     */
    public void addNode(DagNode node) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(node.getId(), "node id must not be null");
        nodes.put(node.getId(), node);
    }

    /**
     * 添加边。自动校验端点节点存在并维护 target 节点入度。
     *
     * @param edge 待加入边
     * @throws IllegalArgumentException 端点节点不存在时抛出
     */
    public void addEdge(DagEdge edge) {
        Objects.requireNonNull(edge, "edge must not be null");
        if (!nodes.containsKey(edge.getSource())) {
            throw new IllegalArgumentException("source node not found: " + edge.getSource());
        }
        if (!nodes.containsKey(edge.getTarget())) {
            throw new IllegalArgumentException("target node not found: " + edge.getTarget());
        }
        edges.add(edge);
        DagNode target = nodes.get(edge.getTarget());
        target.setInDegree(target.getInDegree() + 1);
    }

    /**
     * 获取节点的直接后继（出边目标）。
     *
     * @param nodeId 节点 ID
     * @return 后继节点 ID 列表（保持边插入顺序）
     */
    public List<String> successors(String nodeId) {
        List<String> result = new ArrayList<>();
        for (DagEdge edge : edges) {
            if (edge.getSource().equals(nodeId)) {
                result.add(edge.getTarget());
            }
        }
        return result;
    }

    /**
     * 获取节点的直接前驱（入边源）。
     *
     * @param nodeId 节点 ID
     * @return 前驱节点 ID 列表
     */
    public List<String> predecessors(String nodeId) {
        List<String> result = new ArrayList<>();
        for (DagEdge edge : edges) {
            if (edge.getTarget().equals(nodeId)) {
                result.add(edge.getSource());
            }
        }
        return result;
    }

    /**
     * 获取所有入度为 0 的节点（拓扑排序起点）。
     *
     * @return 入度为 0 的节点 ID 集合
     */
    public Set<String> roots() {
        Set<String> result = new LinkedHashSet<>();
        for (DagNode node : nodes.values()) {
            if (node.getInDegree() == 0) {
                result.add(node.getId());
            }
        }
        return result;
    }

    /**
     * 返回所有节点 ID（只读视图）。
     *
     * @return 节点 ID 集合
     */
    public Set<String> nodeIds() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    /**
     * 返回所有节点（只读视图）。
     *
     * @return 节点集合
     */
    public Collection<DagNode> allNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    /**
     * 获取指定节点。
     *
     * @param id 节点 ID
     * @return 节点；不存在返回 null
     */
    public DagNode node(String id) {
        return nodes.get(id);
    }

    /**
     * 重新计算所有节点入度。在边集合被外部修改后调用以保持一致性。
     */
    public void recomputeInDegrees() {
        for (DagNode node : nodes.values()) {
            node.setInDegree(0);
        }
        for (DagEdge edge : edges) {
            DagNode target = nodes.get(edge.getTarget());
            if (target != null) {
                target.setInDegree(target.getInDegree() + 1);
            }
        }
    }
}