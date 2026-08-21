package com.levango7.dataenginebdp.federated.cluster;

import com.levango7.dataenginebdp.federated.governance.FederatedGovernanceView;
import com.levango7.dataenginebdp.federated.governance.FederatedLineageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 真实集群血缘提供者。
 *
 * <p>调用各集群的 lineage-analyzer 服务获取血缘边与节点，将结果映射为
 * {@link FederatedGovernanceView.LineageEdge}。
 *
 * <p>Lineage API 约定（与 platform/lineage-analyzer 对齐）：
 * <pre>
 * GET {lineageUrl}/edges?cluster={clusterId}
 * Response:
 * {
 *   "data": {
 *     "edges": [
 *       {
 *         "edgeId": "e1",
 *         "sourceNodeId": "cluster-a:db.raw",
 *         "targetNodeId": "cluster-a:db.staging",
 *         "edgeType": "DIRECT",
 *         "transformation": "ETL",
 *         "crossCluster": false,
 *         "sourceClusterId": "cluster-a",
 *         "targetClusterId": "cluster-a"
 *       }
 *     ],
 *     "nodes": [
 *       {
 *         "nodeId": "cluster-a:db.raw",
 *         "name": "raw",
 *         "nodeType": "TABLE",
 *         "clusterId": "cluster-a",
 *         "database": "db",
 *         "label": "db.raw"
 *       }
 *     ]
 *   }
 * }
 * </pre>
 *
 * <p>mTLS：复用 {@link com.levango7.dataenginebdp.federated.config.MtlsConfig#clusterWebClient}
 * 构造的 {@link WebClient}。
 */
@Slf4j
public class RealClusterLineageProvider implements FederatedLineageService.ClusterLineageProvider {

    private final WebClient webClient;
    private final FederatedClusterProperties props;

    public RealClusterLineageProvider(WebClient webClient, FederatedClusterProperties props) {
        this.webClient = webClient;
        this.props = props;
    }

    @Override
    public List<FederatedGovernanceView.LineageEdge> fetchLineageEdges(String clusterId) {
        if (clusterId == null || clusterId.isEmpty()) {
            return Collections.emptyList();
        }
        FederatedClusterProperties.ClusterConfig cluster = props.findCluster(clusterId);
        if (cluster == null || cluster.getLineageUrl() == null) {
            log.warn("Cluster {} not configured or lineageUrl missing, return empty", clusterId);
            return Collections.emptyList();
        }
        if (!cluster.isEnabled()) {
            log.info("Cluster {} disabled, skip lineage fetch", clusterId);
            return Collections.emptyList();
        }

        String lineageUrl = cluster.getLineageUrl();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = webClient.get()
                    .uri(lineageUrl + "/edges?cluster={cluster}", clusterId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(props.getResponseTimeout())
                    .block();
            return parseEdges(resp, clusterId);
        } catch (Exception e) {
            log.error("Fetch lineage edges failed: cluster={} url={} err={}",
                    clusterId, lineageUrl, e.getMessage(), e);
            throw new RuntimeException("Fetch lineage edges failed for cluster " + clusterId
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * 拉取指定集群的血缘节点列表。
     *
     * <p>用于在 {@link FederatedLineageService#registerNode} 中注册节点，
     * 以便 BFS 遍历时能正确识别节点所属集群。
     *
     * @param clusterId 集群 ID
     * @return 血缘节点列表
     */
    public List<FederatedGovernanceView.LineageNode> fetchLineageNodes(String clusterId) {
        if (clusterId == null || clusterId.isEmpty()) {
            return Collections.emptyList();
        }
        FederatedClusterProperties.ClusterConfig cluster = props.findCluster(clusterId);
        if (cluster == null || cluster.getLineageUrl() == null || !cluster.isEnabled()) {
            return Collections.emptyList();
        }
        String lineageUrl = cluster.getLineageUrl();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = webClient.get()
                    .uri(lineageUrl + "/nodes?cluster={cluster}", clusterId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(props.getResponseTimeout())
                    .block();
            return parseNodes(resp, clusterId);
        } catch (Exception e) {
            log.error("Fetch lineage nodes failed: cluster={} err={}", clusterId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<FederatedGovernanceView.LineageEdge> parseEdges(Map<String, Object> resp, String clusterId) {
        if (resp == null) {
            return Collections.emptyList();
        }
        Object data = resp.get("data");
        Map<String, Object> dataMap;
        if (data instanceof Map) {
            dataMap = (Map<String, Object>) data;
        } else {
            dataMap = resp;
        }
        Object edgesObj = dataMap.get("edges");
        if (!(edgesObj instanceof List)) {
            return Collections.emptyList();
        }
        List<FederatedGovernanceView.LineageEdge> edges = new ArrayList<>();
        for (Object item : (List<Object>) edgesObj) {
            if (item instanceof Map) {
                edges.add(parseEdge((Map<String, Object>) item, clusterId));
            }
        }
        return edges;
    }

    private FederatedGovernanceView.LineageEdge parseEdge(Map<String, Object> raw, String defaultCluster) {
        String sourceCluster = str(raw.get("sourceClusterId"), defaultCluster);
        String targetCluster = str(raw.get("targetClusterId"), defaultCluster);
        boolean crossCluster = boolValue(raw.get("crossCluster"), false);
        // 若未显式标记 crossCluster，但源/目标集群不同，则推断为跨集群
        if (!crossCluster && sourceCluster != null && !sourceCluster.equals(targetCluster)) {
            crossCluster = true;
        }
        return FederatedGovernanceView.LineageEdge.builder()
                .edgeId(str(raw.getOrDefault("edgeId", "edge:" + System.nanoTime())))
                .sourceNodeId(str(raw.get("sourceNodeId")))
                .targetNodeId(str(raw.get("targetNodeId")))
                .edgeType(str(raw.getOrDefault("edgeType", "DIRECT")))
                .transformation(str(raw.get("transformation")))
                .crossCluster(crossCluster)
                .sourceClusterId(sourceCluster)
                .targetClusterId(targetCluster)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<FederatedGovernanceView.LineageNode> parseNodes(Map<String, Object> resp, String defaultCluster) {
        if (resp == null) {
            return Collections.emptyList();
        }
        Object data = resp.get("data");
        Map<String, Object> dataMap;
        if (data instanceof Map) {
            dataMap = (Map<String, Object>) data;
        } else {
            dataMap = resp;
        }
        Object nodesObj = dataMap.get("nodes");
        if (!(nodesObj instanceof List)) {
            return Collections.emptyList();
        }
        List<FederatedGovernanceView.LineageNode> nodes = new ArrayList<>();
        for (Object item : (List<Object>) nodesObj) {
            if (item instanceof Map) {
                nodes.add(parseNode((Map<String, Object>) item, defaultCluster));
            }
        }
        return nodes;
    }

    private FederatedGovernanceView.LineageNode parseNode(Map<String, Object> raw, String defaultCluster) {
        return FederatedGovernanceView.LineageNode.builder()
                .nodeId(str(raw.get("nodeId")))
                .name(str(raw.get("name")))
                .nodeType(str(raw.getOrDefault("nodeType", "TABLE")))
                .clusterId(str(raw.get("clusterId"), defaultCluster))
                .database(str(raw.get("database")))
                .label(str(raw.get("label")))
                .build();
    }

    // ------------------------------------------------------------------
    // 类型转换工具
    // ------------------------------------------------------------------

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String str(Object o, String def) {
        String s = str(o);
        return (s == null || s.isEmpty()) ? def : s;
    }

    private static boolean boolValue(Object o, boolean def) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o != null) {
            return Boolean.parseBoolean(String.valueOf(o));
        }
        return def;
    }
}