package com.levango7.dataenginebdp.rule.engine.orchestrator.visual;

import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagEdge;
import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagGraph;
import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DagJsonExporter} 单元测试。
 */
class DagJsonExporterTest {

    @Test
    void toMap_shouldContainGraphMetadata() {
        DagGraph graph = buildGraph();
        Map<String, Object> map = DagJsonExporter.toMap(graph);
        assertEquals("g1", map.get("id"));
        assertEquals("ETL", map.get("name"));
    }

    @Test
    void toMap_shouldContainNodes() {
        DagGraph graph = buildGraph();
        Map<String, Object> map = DagJsonExporter.toMap(graph);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) map.get("nodes");
        assertEquals(3, nodes.size());
        assertTrue(nodes.stream().anyMatch(n -> "a".equals(n.get("id"))));
    }

    @Test
    void toMap_shouldContainEdges() {
        DagGraph graph = buildGraph();
        Map<String, Object> map = DagJsonExporter.toMap(graph);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) map.get("edges");
        assertEquals(2, edges.size());
    }

    @Test
    void toJson_shouldBeValidJsonString() {
        DagGraph graph = buildGraph();
        String json = DagJsonExporter.toJson(graph);
        assertTrue(json.contains("\"id\""));
        assertTrue(json.contains("\"nodes\""));
        assertTrue(json.contains("\"edges\""));
    }

    private DagGraph buildGraph() {
        DagGraph graph = DagGraph.builder().id("g1").name("ETL").build();
        graph.addNode(DagNode.of("a", "Extract", "HTTP", ""));
        graph.addNode(DagNode.of("b", "Transform", "NOOP", ""));
        graph.addNode(DagNode.of("c", "Load", "SHELL", ""));
        graph.addEdge(DagEdge.of("a", "b"));
        graph.addEdge(DagEdge.of("b", "c"));
        return graph;
    }
}