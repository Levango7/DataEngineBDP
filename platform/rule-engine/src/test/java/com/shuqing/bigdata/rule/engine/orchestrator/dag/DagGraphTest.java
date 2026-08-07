package com.shuqing.bigdata.rule.engine.orchestrator.dag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DagGraph} 单元测试。
 *
 * <p>覆盖节点/边增删、入度维护、前驱后继查询等核心行为。</p>
 */
class DagGraphTest {

    @Test
    void addNode_shouldStoreNode() {
        DagGraph graph = DagGraph.builder().id("g1").build();
        graph.addNode(DagNode.of("a", "A", "NOOP", "echo a"));
        assertEquals(1, graph.nodeIds().size());
        assertEquals("A", graph.node("a").getName());
    }

    @Test
    void addEdge_shouldMaintainInDegree() {
        DagGraph graph = DagGraph.builder().id("g1").build();
        graph.addNode(DagNode.of("a", "A", "NOOP", ""));
        graph.addNode(DagNode.of("b", "B", "NOOP", ""));
        graph.addEdge(DagEdge.of("a", "b"));
        assertEquals(1, graph.node("b").getInDegree());
        assertEquals(0, graph.node("a").getInDegree());
    }

    @Test
    void addEdge_unknownTarget_shouldThrow() {
        DagGraph graph = DagGraph.builder().id("g1").build();
        graph.addNode(DagNode.of("a", "A", "NOOP", ""));
        assertThrows(IllegalArgumentException.class, () -> graph.addEdge(DagEdge.of("a", "b")));
    }

    @Test
    void successors_andPredecessors_shouldReturnCorrectIds() {
        DagGraph graph = DagGraph.builder().id("g1").build();
        graph.addNode(DagNode.of("a", "A", "NOOP", ""));
        graph.addNode(DagNode.of("b", "B", "NOOP", ""));
        graph.addNode(DagNode.of("c", "C", "NOOP", ""));
        graph.addEdge(DagEdge.of("a", "b"));
        graph.addEdge(DagEdge.of("a", "c"));
        graph.addEdge(DagEdge.of("b", "c"));

        assertEquals(List.of("b", "c"), graph.successors("a"));
        assertEquals(List.of("a", "b"), graph.predecessors("c"));
    }

    @Test
    void roots_shouldReturnZeroInDegreeNodes() {
        DagGraph graph = DagGraph.builder().id("g1").build();
        graph.addNode(DagNode.of("a", "A", "NOOP", ""));
        graph.addNode(DagNode.of("b", "B", "NOOP", ""));
        graph.addEdge(DagEdge.of("a", "b"));
        Set<String> roots = graph.roots();
        assertTrue(roots.contains("a"));
        assertFalse(roots.contains("b"));
    }

    @Test
    void recomputeInDegrees_shouldResetAndRecompute() {
        DagGraph graph = DagGraph.builder().id("g1").build();
        graph.addNode(DagNode.of("a", "A", "NOOP", ""));
        graph.addNode(DagNode.of("b", "B", "NOOP", ""));
        graph.addEdge(DagEdge.of("a", "b"));
        // 手动破坏入度
        graph.node("b").setInDegree(99);
        graph.recomputeInDegrees();
        assertEquals(1, graph.node("b").getInDegree());
    }
}