package com.levango7.dataenginebdp.rule.engine.orchestrator.visual;

import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagEdge;
import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagGraph;
import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MermaidGenerator} 单元测试。
 */
class MermaidGeneratorTest {

    @Test
    void generate_shouldContainFlowchartHeader() {
        DagGraph graph = buildGraph();
        String mermaid = MermaidGenerator.generate(graph);
        assertTrue(mermaid.startsWith("flowchart LR"));
    }

    @Test
    void generate_shouldContainNodeDeclarations() {
        DagGraph graph = buildGraph();
        String mermaid = MermaidGenerator.generate(graph);
        assertTrue(mermaid.contains("a[\"Extract\"]"));
        assertTrue(mermaid.contains("b[\"Transform\"]"));
        assertTrue(mermaid.contains("c[\"Load\"]"));
    }

    @Test
    void generate_shouldContainEdges() {
        DagGraph graph = buildGraph();
        String mermaid = MermaidGenerator.generate(graph);
        assertTrue(mermaid.contains("a --> b"));
        assertTrue(mermaid.contains("b --> c"));
    }

    @Test
    void generate_shouldContainClassDef() {
        DagGraph graph = buildGraph();
        String mermaid = MermaidGenerator.generate(graph);
        assertTrue(mermaid.contains("classDef PENDING"));
        assertTrue(mermaid.contains("classDef SUCCESS"));
        assertTrue(mermaid.contains("classDef FAILED"));
    }

    @Test
    void generate_customDirection_shouldUseIt() {
        DagGraph graph = buildGraph();
        String mermaid = MermaidGenerator.generate(graph, "TD");
        assertTrue(mermaid.startsWith("flowchart TD"));
    }

    @Test
    void generate_specialCharsInId_shouldBeSanitized() {
        DagGraph graph = DagGraph.builder().id("g").build();
        graph.addNode(DagNode.of("node-1", "N1", "NOOP", ""));
        String mermaid = MermaidGenerator.generate(graph);
        assertTrue(mermaid.contains("node_1["));
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