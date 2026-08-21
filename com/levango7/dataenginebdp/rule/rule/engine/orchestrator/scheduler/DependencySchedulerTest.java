package com.shuqing.bigdata.rule.engine.orchestrator.scheduler;

import com.shuqing.bigdata.rule.engine.orchestrator.alert.AlertManager;
import com.shuqing.bigdata.rule.engine.orchestrator.dag.DagEdge;
import com.shuqing.bigdata.rule.engine.orchestrator.dag.DagGraph;
import com.shuqing.bigdata.rule.engine.orchestrator.dag.DagNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DependencyScheduler} 单元测试。
 *
 * <p>使用 NOOP 执行器验证拓扑调度、失败跳过后继等核心行为。</p>
 */
class DependencySchedulerTest {

    private DependencyScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void schedule_linearGraph_shouldExecuteAllNodes() {
        DagGraph graph = buildLinearGraph();
        scheduler = newSchedulerWithNoop();

        Map<String, TaskResult> results = scheduler.schedule(graph);

        assertEquals(3, results.size());
        results.values().forEach(r -> assertTrue(r.isSuccess(), "all nodes should succeed"));
        assertEquals(DagGraph.STATUS_SUCCESS, graph.getStatus());
    }

    @Test
    void schedule_parallelRoots_shouldExecuteBoth() {
        DagGraph graph = DagGraph.builder().id("g").build();
        graph.addNode(DagNode.of("a", "A", "NOOP", ""));
        graph.addNode(DagNode.of("b", "B", "NOOP", ""));
        graph.addNode(DagNode.of("c", "C", "NOOP", ""));
        graph.addEdge(DagEdge.of("a", "c"));
        graph.addEdge(DagEdge.of("b", "c"));
        scheduler = newSchedulerWithNoop();

        Map<String, TaskResult> results = scheduler.schedule(graph);

        assertEquals(3, results.size());
        assertTrue(results.get("a").isSuccess());
        assertTrue(results.get("b").isSuccess());
        assertTrue(results.get("c").isSuccess());
    }

    @Test
    void schedule_failedNode_shouldSkipSuccessors() {
        DagGraph graph = DagGraph.builder().id("g").build();
        graph.addNode(DagNode.of("a", "A", "FAIL", ""));
        graph.addNode(DagNode.of("b", "B", "NOOP", ""));
        graph.addEdge(DagEdge.of("a", "b"));
        scheduler = newSchedulerWith(List.of(
                new NoopTaskExecutor(),
                new FailTaskExecutor()));

        Map<String, TaskResult> results = scheduler.schedule(graph);

        // a 失败
        assertEquals(TaskResult.STATUS_FAILED, results.get("a").getStatus());
        // b 应被跳过（不在结果中或标记 skipped）
        assertTrue(!results.containsKey("b") || !results.get("b").isSuccess(),
                "successor of failed node should not succeed");
        assertEquals(DagGraph.STATUS_FAILED, graph.getStatus());
    }

    @Test
    void schedule_emptyGraph_shouldThrow() {
        DagGraph graph = DagGraph.builder().id("g").build();
        scheduler = newSchedulerWithNoop();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> scheduler.schedule(graph));
    }

    private DependencyScheduler newSchedulerWithNoop() {
        return newSchedulerWith(List.of(new NoopTaskExecutor()));
    }

    private DependencyScheduler newSchedulerWith(List<TaskExecutor> executors) {
        AlertManager alertManager = new AlertManager(List.of());
        return new DependencyScheduler(alertManager, executors,
                Executors.newFixedThreadPool(4), new com.shuqing.bigdata.rule.engine.orchestrator.retry.RetryExecutor());
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

    /** 失败执行器，taskType=FAIL */
    static class FailTaskExecutor implements TaskExecutor {
        @Override
        public TaskResult execute(DagNode node, Map<String, TaskResult> context) {
            return TaskResult.failure(node.getId(), "intentional failure", 0);
        }

        @Override
        public String taskType() {
            return "FAIL";
        }
    }
}