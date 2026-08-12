package com.levango7.dataenginebdp.rule.engine.orchestrator.service;

import com.levango7.dataenginebdp.rule.engine.orchestrator.alert.AlertManager;
import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagEdge;
import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagGraph;
import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagNode;
import com.levango7.dataenginebdp.rule.engine.orchestrator.scheduler.DependencyScheduler;
import com.levango7.dataenginebdp.rule.engine.orchestrator.scheduler.NoopTaskExecutor;
import com.levango7.dataenginebdp.rule.engine.orchestrator.scheduler.TaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrchestratorService} 单元测试。
 */
class OrchestratorServiceTest {

    private DependencyScheduler scheduler;
    private OrchestratorService service;

    @BeforeEach
    void setUp() {
        AlertManager alertManager = new AlertManager(List.of());
        scheduler = new DependencyScheduler(alertManager, List.of(new NoopTaskExecutor()),
                Executors.newFixedThreadPool(4),
                new com.levango7.dataenginebdp.rule.engine.orchestrator.retry.RetryExecutor());
        service = new OrchestratorService(scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void submit_shouldAssignIdAndStore() {
        DagGraph graph = buildLinearGraph();
        DagGraph saved = service.submit(graph);
        assertNotNull(saved.getId());
        assertEquals(DagGraph.STATUS_DRAFT, saved.getStatus());
        assertEquals(saved, service.getDag(saved.getId()));
    }

    @Test
    void submit_cyclicGraph_shouldThrow() {
        DagGraph graph = DagGraph.builder().id("g").build();
        graph.addNode(DagNode.of("a", "A", "NOOP", ""));
        graph.addNode(DagNode.of("b", "B", "NOOP", ""));
        graph.addEdge(DagEdge.of("a", "b"));
        graph.addEdge(DagEdge.of("b", "a"));
        assertThrows(IllegalStateException.class, () -> service.submit(graph));
    }

    @Test
    void runDag_shouldExecuteAndStoreResults() {
        DagGraph graph = service.submit(buildLinearGraph());
        Map<String, TaskResult> results = service.runDag(graph.getId());
        assertEquals(3, results.size());
        assertNotNull(service.getResults(graph.getId()));
    }

    @Test
    void runDag_unknownId_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> service.runDag("nonexistent"));
    }

    @Test
    void visualize_shouldReturnMermaidText() {
        DagGraph graph = service.submit(buildLinearGraph());
        String mermaid = service.visualize(graph.getId());
        assertTrue(mermaid.startsWith("flowchart"));
    }

    @Test
    void exportJson_shouldReturnMapWithNodes() {
        DagGraph graph = service.submit(buildLinearGraph());
        Map<String, Object> json = service.exportJson(graph.getId());
        assertNotNull(json.get("nodes"));
        assertNotNull(json.get("edges"));
    }

    @Test
    void listAll_shouldReturnSubmittedGraphs() {
        service.submit(buildLinearGraph());
        service.submit(buildLinearGraph());
        assertEquals(2, service.listAll().size());
    }

    @Test
    void delete_shouldRemoveGraph() {
        DagGraph graph = service.submit(buildLinearGraph());
        assertTrue(service.delete(graph.getId()));
        assertFalse(service.delete(graph.getId()));
    }

    private DagGraph buildLinearGraph() {
        DagGraph graph = DagGraph.builder().name("linear").build();
        graph.addNode(DagNode.of("a", "A", "NOOP", ""));
        graph.addNode(DagNode.of("b", "B", "NOOP", ""));
        graph.addNode(DagNode.of("c", "C", "NOOP", ""));
        graph.addEdge(DagEdge.of("a", "b"));
        graph.addEdge(DagEdge.of("b", "c"));
        return graph;
    }
}