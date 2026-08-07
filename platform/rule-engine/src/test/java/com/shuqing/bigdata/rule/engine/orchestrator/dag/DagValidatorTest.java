package com.shuqing.bigdata.rule.engine.orchestrator.dag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DagValidator} 单元测试。
 *
 * <p>覆盖无环图拓扑排序、有环图检测、空图与边端点缺失校验。</p>
 */
class DagValidatorTest {

    @Test
    void topologicalSort_acyclic_shouldReturnValidOrder() {
        DagGraph graph = buildLinearGraph();
        List<String> order = DagValidator.topologicalSort(graph);
        assertEquals(3, order.size());
        // a 必须在 b 前，b 必须在 c 前
        assertTrue(order.indexOf("a") < order.indexOf("b"));
        assertTrue(order.indexOf("b") < order.indexOf("c"));
    }

    @Test
    void hasCycle_acyclic_shouldReturnFalse() {
        DagGraph graph = buildLinearGraph();
        assertFalse(DagValidator.hasCycle(graph));
    }

    @Test
    void hasCycle_cyclic_shouldReturnTrue() {
        DagGraph graph = DagGraph.builder().id("g").build();
        graph.addNode(DagNode.of("a", "A", "NOOP", ""));
        graph.addNode(DagNode.of("b", "B", "NOOP", ""));
        graph.addNode(DagNode.of("c", "C", "NOOP", ""));
        graph.addEdge(DagEdge.of("a", "b"));
        graph.addEdge(DagEdge.of("b", "c"));
        graph.addEdge(DagEdge.of("c", "a"));
        assertTrue(DagValidator.hasCycle(graph));
    }

    @Test
    void detectCycle_shouldReturnCyclePath() {
        DagGraph graph = DagGraph.builder().id("g").build();
        graph.addNode(DagNode.of("a", "A", "NOOP", ""));
        graph.addNode(DagNode.of("b", "B", "NOOP", ""));
        graph.addEdge(DagEdge.of("a", "b"));
        graph.addEdge(DagEdge.of("b", "a"));
        List<String> cycle = DagValidator.detectCycle(graph);
        assertFalse(cycle.isEmpty(), "cycle should be detected");
    }

    @Test
    void topologicalSort_cyclic_shouldThrow() {
        DagGraph graph = DagGraph.builder().id("g").build();
        graph.addNode(DagNode.of("a", "A", "NOOP", ""));
        graph.addNode(DagNode.of("b", "B", "NOOP", ""));
        graph.addEdge(DagEdge.of("a", "b"));
        graph.addEdge(DagEdge.of("b", "a"));
        assertThrows(IllegalStateException.class, () -> DagValidator.topologicalSort(graph));
    }

    @Test
    void validate_emptyGraph_shouldThrow() {
        DagGraph graph = DagGraph.builder().id("g").build();
        assertThrows(IllegalStateException.class, () -> DagValidator.validate(graph));
    }

    @Test
    void validate_validGraph_shouldPass() {
        DagGraph graph = buildLinearGraph();
        DagValidator.validate(graph); // 不抛异常即通过
    }

    private DagGraph buildLinearGraph() {
        DagGraph graph = DagGraph.builder().id("g").build();
        graph.addNode(DagNode.of("a", "A", "NOOP", ""));
        graph.addNode(DagNode.of("b", "B", "NOOP", ""));
        graph.addNode(DagNode.of("c", "C", "NOOP", ""));
        graph.addEdge(DagEdge.of("a", "b"));
        graph.addEdge(DagEdge.of("b", "c"));
        return graph;
    }
}