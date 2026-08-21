package com.levango7.dataenginebdp.federated.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨集群血缘统一治理服务。
 *
 * <p>职责：
 * <ul>
 *   <li>聚合多集群的血缘关系</li>
 *   <li>获取跨集群血缘链（{@link #getFederatedLineage(String)}）</li>
 *   <li>上游血缘（数据来源链 {@link #getUpstreamLineage(String)}）</li>
 *   <li>下游血缘（数据消费链 {@link #getDownstreamLineage(String)}）</li>
 *   <li>跨集群血缘连接（集群 A 的表 → 集群 B 的表）</li>
 *   <li>血缘可视化数据结构（节点+边）</li>
 * </ul>
 *
 * <p>存储：内存 {@link ConcurrentHashMap}，生产环境可替换为持久化实现。
 * 集群间血缘获取通过 {@link ClusterLineageProvider} 接口抽象，便于 Mock。
 *
 * <p>验收标准：
 * <ul>
 *   <li>跨集群血缘链能正确连接集群 A 表与集群 B 表</li>
 *   <li>上游/下游遍历支持循环血缘检测，避免无限循环</li>
 *   <li>血缘图节点/边结构可用于前端可视化</li>
 * </ul>
 */
@Slf4j
@Service
public class FederatedLineageService {

    /** edgeId → 血缘边。 */
    private final ConcurrentHashMap<String, FederatedGovernanceView.LineageEdge> edgeStore = new ConcurrentHashMap<>();

    /** nodeId → 血缘节点。 */
    private final ConcurrentHashMap<String, FederatedGovernanceView.LineageNode> nodeStore = new ConcurrentHashMap<>();

    /** targetNodeId → 入边列表（上游）。 */
    private final ConcurrentHashMap<String, List<String>> incomingEdges = new ConcurrentHashMap<>();

    /** sourceNodeId → 出边列表（下游）。 */
    private final ConcurrentHashMap<String, List<String>> outgoingEdges = new ConcurrentHashMap<>();

    /** 跨集群血缘连接（手动注册的跨集群边）。 */
    private final ConcurrentHashMap<String, FederatedGovernanceView.LineageEdge> crossClusterEdges = new ConcurrentHashMap<>();

    private final ClusterLineageProvider lineageProvider;

    /** 遍历最大深度，防止循环血缘导致无限递归。 */
    private static final int MAX_DEPTH = 50;

    public FederatedLineageService(ClusterLineageProvider lineageProvider) {
        this.lineageProvider = lineageProvider;
    }

    /**
     * 同步指定集群的血缘数据。
     *
     * @param clusterId 集群 ID
     * @return 同步的边数量
     */
    public int syncLineage(String clusterId) {
        List<FederatedGovernanceView.LineageEdge> edges = lineageProvider.fetchLineageEdges(clusterId);
        if (edges == null) {
            edges = Collections.emptyList();
        }
        int count = 0;
        for (FederatedGovernanceView.LineageEdge edge : edges) {
            registerEdge(edge);
            count++;
        }
        log.info("Lineage synced: cluster={} edges={}", clusterId, count);
        return count;
    }

    /**
     * 获取跨集群血缘链（完整图，含上游和下游）。
     *
     * @param tableId 表 ID（作为节点 ID）
     * @return 血缘视图
     */
    public FederatedGovernanceView.LineageView getFederatedLineage(String tableId) {
        Instant now = Instant.now();
        FederatedGovernanceView.LineageGraph upstream = buildUpstreamGraph(tableId);
        FederatedGovernanceView.LineageGraph downstream = buildDownstreamGraph(tableId);
        FederatedGovernanceView.LineageGraph full = mergeGraphs(tableId, upstream, downstream);
        return FederatedGovernanceView.LineageView.builder()
                .graph(full)
                .upstream(upstream)
                .downstream(downstream)
                .generatedAt(now)
                .build();
    }

    /**
     * 获取上游血缘（数据来源链）。
     *
     * @param tableId 表 ID
     * @return 上游血缘图
     */
    public FederatedGovernanceView.LineageGraph getUpstreamLineage(String tableId) {
        return buildUpstreamGraph(tableId);
    }

    /**
     * 获取下游血缘（数据消费链）。
     *
     * @param tableId 表 ID
     * @return 下游血缘图
     */
    public FederatedGovernanceView.LineageGraph getDownstreamLineage(String tableId) {
        return buildDownstreamGraph(tableId);
    }

    /**
     * 注册跨集群血缘连接（集群 A 的表 → 集群 B 的表）。
     *
     * @param sourceNodeId 源节点 ID（集群 A 的表）
     * @param targetNodeId 目标节点 ID（集群 B 的表）
     * @param edgeType 边类型
     * @param transformation 转换描述
     * @return 创建的边
     */
    public FederatedGovernanceView.LineageEdge registerCrossClusterLink(
            String sourceNodeId, String targetNodeId, String edgeType, String transformation) {
        FederatedGovernanceView.LineageNode source = nodeStore.get(sourceNodeId);
        FederatedGovernanceView.LineageNode target = nodeStore.get(targetNodeId);
        String sourceCluster = source != null ? source.getClusterId() : null;
        String targetCluster = target != null ? target.getClusterId() : null;

        String edgeId = "cross:" + sourceNodeId + "->" + targetNodeId;
        FederatedGovernanceView.LineageEdge edge = FederatedGovernanceView.LineageEdge.builder()
                .edgeId(edgeId)
                .sourceNodeId(sourceNodeId)
                .targetNodeId(targetNodeId)
                .edgeType(edgeType)
                .transformation(transformation)
                .crossCluster(true)
                .sourceClusterId(sourceCluster)
                .targetClusterId(targetCluster)
                .build();
        crossClusterEdges.put(edgeId, edge);
        registerEdge(edge);
        log.info("Cross-cluster lineage registered: {} -> {} ({} -> {})",
                sourceNodeId, targetNodeId, sourceCluster, targetCluster);
        return edge;
    }

    /**
     * 注册血缘节点。
     */
    public void registerNode(FederatedGovernanceView.LineageNode node) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(node.getNodeId(), "nodeId must not be null");
        nodeStore.put(node.getNodeId(), node);
    }

    /**
     * 注册血缘边。
     */
    public void registerEdge(FederatedGovernanceView.LineageEdge edge) {
        Objects.requireNonNull(edge, "edge must not be null");
        Objects.requireNonNull(edge.getEdgeId(), "edgeId must not be null");
        edgeStore.put(edge.getEdgeId(), edge);
        incomingEdges.computeIfAbsent(edge.getTargetNodeId(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(edge.getEdgeId());
        outgoingEdges.computeIfAbsent(edge.getSourceNodeId(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(edge.getEdgeId());
    }

    /**
     * 构建血缘视图。
     *
     * @return 全部血缘图
     */
    public FederatedGovernanceView.LineageGraph buildLineageGraph() {
        List<FederatedGovernanceView.LineageNode> nodes = new ArrayList<>(nodeStore.values());
        List<FederatedGovernanceView.LineageEdge> edges = new ArrayList<>(edgeStore.values());
        Set<String> clusters = new LinkedHashSet<>();
        boolean crossCluster = false;
        for (FederatedGovernanceView.LineageEdge e : edges) {
            if (e.isCrossCluster()) {
                crossCluster = true;
            }
            if (e.getSourceClusterId() != null) {
                clusters.add(e.getSourceClusterId());
            }
            if (e.getTargetClusterId() != null) {
                clusters.add(e.getTargetClusterId());
            }
        }
        for (FederatedGovernanceView.LineageNode n : nodes) {
            if (n.getClusterId() != null) {
                clusters.add(n.getClusterId());
            }
        }
        return FederatedGovernanceView.LineageGraph.builder()
                .nodes(nodes)
                .edges(edges)
                .hasCrossCluster(crossCluster)
                .clusters(new ArrayList<>(clusters))
                .generatedAt(Instant.now())
                .build();
    }

    /**
     * 获取所有跨集群血缘边。
     */
    public List<FederatedGovernanceView.LineageEdge> getCrossClusterEdges() {
        return new ArrayList<>(crossClusterEdges.values());
    }

    /**
     * 清空所有血缘数据（用于测试）。
     */
    public void clear() {
        edgeStore.clear();
        nodeStore.clear();
        incomingEdges.clear();
        outgoingEdges.clear();
        crossClusterEdges.clear();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 构建上游血缘图（BFS 遍历入边，带循环检测）。
     */
    private FederatedGovernanceView.LineageGraph buildUpstreamGraph(String tableId) {
        Set<String> visitedNodes = new LinkedHashSet<>();
        Set<String> visitedEdges = new LinkedHashSet<>();
        Set<String> clusters = new LinkedHashSet<>();
        boolean[] crossCluster = {false};

        bfsTraversal(tableId, visitedNodes, visitedEdges, clusters, crossCluster, true);

        return buildGraph(visitedNodes, visitedEdges, clusters, crossCluster[0]);
    }

    /**
     * 构建下游血缘图（BFS 遍历出边，带循环检测）。
     */
    private FederatedGovernanceView.LineageGraph buildDownstreamGraph(String tableId) {
        Set<String> visitedNodes = new LinkedHashSet<>();
        Set<String> visitedEdges = new LinkedHashSet<>();
        Set<String> clusters = new LinkedHashSet<>();
        boolean[] crossCluster = {false};

        bfsTraversal(tableId, visitedNodes, visitedEdges, clusters, crossCluster, false);

        return buildGraph(visitedNodes, visitedEdges, clusters, crossCluster[0]);
    }

    /**
     * BFS 遍历血缘图。
     *
     * @param startNodeId 起始节点
     * @param upstream true=遍历上游（入边），false=遍历下游（出边）
     */
    private void bfsTraversal(String startNodeId, Set<String> visitedNodes, Set<String> visitedEdges,
                              Set<String> clusters, boolean[] crossCluster, boolean upstream) {
        // 使用 (nodeId, depth) 队列进行 BFS
        List<java.util.Map.Entry<String, Integer>> queue = new ArrayList<>();
        queue.add(java.util.Map.entry(startNodeId, 0));
        visitedNodes.add(startNodeId);

        FederatedGovernanceView.LineageNode startNode = nodeStore.get(startNodeId);
        if (startNode != null && startNode.getClusterId() != null) {
            clusters.add(startNode.getClusterId());
        }

        while (!queue.isEmpty()) {
            java.util.Map.Entry<String, Integer> current = queue.remove(0);
            String currentNodeId = current.getKey();
            int depth = current.getValue();
            if (depth >= MAX_DEPTH) {
                log.warn("Lineage traversal reached max depth {} at node {}, possible cycle", MAX_DEPTH, currentNodeId);
                continue;
            }

            List<String> edgeIds = upstream
                    ? incomingEdges.getOrDefault(currentNodeId, Collections.emptyList())
                    : outgoingEdges.getOrDefault(currentNodeId, Collections.emptyList());

            for (String edgeId : edgeIds) {
                FederatedGovernanceView.LineageEdge edge = edgeStore.get(edgeId);
                if (edge == null) {
                    continue;
                }
                visitedEdges.add(edgeId);
                if (edge.isCrossCluster()) {
                    crossCluster[0] = true;
                }
                if (edge.getSourceClusterId() != null) {
                    clusters.add(edge.getSourceClusterId());
                }
                if (edge.getTargetClusterId() != null) {
                    clusters.add(edge.getTargetClusterId());
                }

                String nextNodeId = upstream ? edge.getSourceNodeId() : edge.getTargetNodeId();
                if (!visitedNodes.contains(nextNodeId)) {
                    visitedNodes.add(nextNodeId);
                    FederatedGovernanceView.LineageNode nextNode = nodeStore.get(nextNodeId);
                    if (nextNode != null && nextNode.getClusterId() != null) {
                        clusters.add(nextNode.getClusterId());
                    }
                    queue.add(java.util.Map.entry(nextNodeId, depth + 1));
                }
            }
        }
    }

    /**
     * 根据访问的节点和边构建血缘图。
     */
    private FederatedGovernanceView.LineageGraph buildGraph(Set<String> nodeIds, Set<String> edgeIds,
                                                            Set<String> clusters, boolean crossCluster) {
        List<FederatedGovernanceView.LineageNode> nodes = new ArrayList<>();
        for (String id : nodeIds) {
            FederatedGovernanceView.LineageNode node = nodeStore.get(id);
            if (node != null) {
                nodes.add(node);
            } else {
                // 节点未注册，创建占位节点
                nodes.add(FederatedGovernanceView.LineageNode.builder()
                        .nodeId(id)
                        .name(id)
                        .nodeType("TABLE")
                        .label(id)
                        .build());
            }
        }
        List<FederatedGovernanceView.LineageEdge> edges = new ArrayList<>();
        for (String id : edgeIds) {
            FederatedGovernanceView.LineageEdge edge = edgeStore.get(id);
            if (edge != null) {
                edges.add(edge);
            }
        }
        return FederatedGovernanceView.LineageGraph.builder()
                .nodes(nodes)
                .edges(edges)
                .hasCrossCluster(crossCluster)
                .clusters(new ArrayList<>(clusters))
                .generatedAt(Instant.now())
                .build();
    }

    /**
     * 合并上游和下游图为完整图。
     */
    private FederatedGovernanceView.LineageGraph mergeGraphs(String centerNodeId,
                                                            FederatedGovernanceView.LineageGraph upstream,
                                                            FederatedGovernanceView.LineageGraph downstream) {
        Map<String, FederatedGovernanceView.LineageNode> nodeMap = new LinkedHashMap<>();
        for (FederatedGovernanceView.LineageNode n : upstream.getNodes()) {
            nodeMap.put(n.getNodeId(), n);
        }
        for (FederatedGovernanceView.LineageNode n : downstream.getNodes()) {
            nodeMap.put(n.getNodeId(), n);
        }
        Map<String, FederatedGovernanceView.LineageEdge> edgeMap = new LinkedHashMap<>();
        for (FederatedGovernanceView.LineageEdge e : upstream.getEdges()) {
            edgeMap.put(e.getEdgeId(), e);
        }
        for (FederatedGovernanceView.LineageEdge e : downstream.getEdges()) {
            edgeMap.put(e.getEdgeId(), e);
        }
        Set<String> clusters = new LinkedHashSet<>();
        clusters.addAll(upstream.getClusters());
        clusters.addAll(downstream.getClusters());
        return FederatedGovernanceView.LineageGraph.builder()
                .nodes(new ArrayList<>(nodeMap.values()))
                .edges(new ArrayList<>(edgeMap.values()))
                .hasCrossCluster(upstream.isHasCrossCluster() || downstream.isHasCrossCluster())
                .clusters(new ArrayList<>(clusters))
                .generatedAt(Instant.now())
                .build();
    }

    /**
     * 集群血缘提供者接口（抽象集群间血缘获取，便于 Mock）。
     */
    public interface ClusterLineageProvider {
        /**
         * 拉取指定集群的血缘边列表。
         *
         * @param clusterId 集群 ID
         * @return 血缘边列表
         */
        List<FederatedGovernanceView.LineageEdge> fetchLineageEdges(String clusterId);
    }
}