package com.levango7.dataenginebdp.federated.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FederatedLineageService} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>血缘链构建（上游/下游）</li>
 *   <li>跨集群血缘连接</li>
 *   <li>循环血缘检测（不无限递归）</li>
 *   <li>边界条件（无血缘/不存在的表）</li>
 *   <li>血缘图可视化结构</li>
 * </ul>
 */
class FederatedLineageServiceTest {

    private FederatedLineageService.ClusterLineageProvider provider;
    private FederatedLineageService service;

    @BeforeEach
    void setUp() {
        provider = mock(FederatedLineageService.ClusterLineageProvider.class);
        service = new FederatedLineageService(provider);
    }

    @Test
    void getUpstreamLineage_shouldTraceDataSources() {
        // raw -> staging -> final
        registerNode("raw", "cluster-a");
        registerNode("staging", "cluster-a");
        registerNode("final", "cluster-a");
        registerEdge("e1", "raw", "staging", false);
        registerEdge("e2", "staging", "final", false);

        FederatedGovernanceView.LineageGraph upstream = service.getUpstreamLineage("final");
        assertThat(upstream.getNodes()).hasSize(3);
        assertThat(upstream.getEdges()).hasSize(2);
        assertThat(upstream.getNodes()).extracting(FederatedGovernanceView.LineageNode::getNodeId)
                .containsExactlyInAnyOrder("raw", "staging", "final");
    }

    @Test
    void getDownstreamLineage_shouldTraceDataConsumers() {
        // raw -> staging -> final -> report
        registerNode("raw", "cluster-a");
        registerNode("staging", "cluster-a");
        registerNode("final", "cluster-a");
        registerNode("report", "cluster-a");
        registerEdge("e1", "raw", "staging", false);
        registerEdge("e2", "staging", "final", false);
        registerEdge("e3", "final", "report", false);

        FederatedGovernanceView.LineageGraph downstream = service.getDownstreamLineage("raw");
        assertThat(downstream.getNodes()).hasSize(4);
        assertThat(downstream.getEdges()).hasSize(3);
    }

    @Test
    void getFederatedLineage_shouldMergeUpstreamAndDownstream() {
        // upstream -> center -> downstream
        registerNode("upstream", "cluster-a");
        registerNode("center", "cluster-a");
        registerNode("downstream", "cluster-a");
        registerEdge("e1", "upstream", "center", false);
        registerEdge("e2", "center", "downstream", false);

        FederatedGovernanceView.LineageView view = service.getFederatedLineage("center");
        assertThat(view.getGraph().getNodes()).hasSize(3);
        assertThat(view.getGraph().getEdges()).hasSize(2);
        assertThat(view.getUpstream().getNodes()).extracting(FederatedGovernanceView.LineageNode::getNodeId)
                .contains("upstream", "center");
        assertThat(view.getDownstream().getNodes()).extracting(FederatedGovernanceView.LineageNode::getNodeId)
                .contains("center", "downstream");
    }

    @Test
    void registerCrossClusterLink_shouldConnectClusters() {
        registerNode("table-a", "cluster-a");
        registerNode("table-b", "cluster-b");

        FederatedGovernanceView.LineageEdge edge = service.registerCrossClusterLink(
                "table-a", "table-b", "COPY", "cross cluster copy");

        assertThat(edge.isCrossCluster()).isTrue();
        assertThat(edge.getSourceClusterId()).isEqualTo("cluster-a");
        assertThat(edge.getTargetClusterId()).isEqualTo("cluster-b");

        FederatedGovernanceView.LineageView view = service.getFederatedLineage("table-b");
        assertThat(view.getUpstream().isHasCrossCluster()).isTrue();
        assertThat(view.getUpstream().getClusters()).contains("cluster-a", "cluster-b");
    }

    @Test
    void cyclicLineage_shouldNotInfiniteLoop() {
        // a -> b -> a (循环)
        registerNode("a", "cluster-a");
        registerNode("b", "cluster-a");
        registerEdge("e1", "a", "b", false);
        registerEdge("e2", "b", "a", false);

        FederatedGovernanceView.LineageGraph upstream = service.getUpstreamLineage("a");
        // 循环血缘应能正常返回，节点不重复
        assertThat(upstream.getNodes()).hasSize(2);
        assertThat(upstream.getEdges()).hasSize(2);
    }

    @Test
    void getLineage_shouldReturnEmptyForIsolatedNode() {
        registerNode("isolated", "cluster-a");

        FederatedGovernanceView.LineageView view = service.getFederatedLineage("isolated");
        assertThat(view.getGraph().getNodes()).hasSize(1);
        assertThat(view.getGraph().getEdges()).isEmpty();
        assertThat(view.getUpstream().getEdges()).isEmpty();
        assertThat(view.getDownstream().getEdges()).isEmpty();
    }

    @Test
    void getLineage_shouldHandleNonExistentNode() {
        FederatedGovernanceView.LineageView view = service.getFederatedLineage("non-existent");
        // 不存在的节点应返回占位节点
        assertThat(view.getGraph().getNodes()).hasSize(1);
        assertThat(view.getGraph().getNodes().get(0).getNodeId()).isEqualTo("non-existent");
    }

    @Test
    void syncLineage_shouldFetchAndRegisterEdges() {
        FederatedGovernanceView.LineageEdge edge = FederatedGovernanceView.LineageEdge.builder()
                .edgeId("synced-edge")
                .sourceNodeId("src")
                .targetNodeId("tgt")
                .edgeType("DIRECT")
                .crossCluster(false)
                .build();
        when(provider.fetchLineageEdges("cluster-a")).thenReturn(List.of(edge));

        int count = service.syncLineage("cluster-a");
        assertThat(count).isEqualTo(1);
        assertThat(service.buildLineageGraph().getEdges()).hasSize(1);
    }

    @Test
    void syncLineage_shouldHandleEmptyCluster() {
        when(provider.fetchLineageEdges("empty")).thenReturn(Collections.emptyList());
        int count = service.syncLineage("empty");
        assertThat(count).isEqualTo(0);
    }

    private void registerNode(String nodeId, String clusterId) {
        service.registerNode(FederatedGovernanceView.LineageNode.builder()
                .nodeId(nodeId)
                .name(nodeId)
                .nodeType("TABLE")
                .clusterId(clusterId)
                .label(nodeId)
                .build());
    }

    private void registerEdge(String edgeId, String source, String target, boolean crossCluster) {
        service.registerEdge(FederatedGovernanceView.LineageEdge.builder()
                .edgeId(edgeId)
                .sourceNodeId(source)
                .targetNodeId(target)
                .edgeType("DIRECT")
                .crossCluster(crossCluster)
                .build());
    }
}