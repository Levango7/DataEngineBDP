package com.shuqing.bigdata.rule.engine.orchestrator.scheduler;

import com.shuqing.bigdata.rule.engine.orchestrator.alert.AlertEvent;
import com.shuqing.bigdata.rule.engine.orchestrator.alert.AlertManager;
import com.shuqing.bigdata.rule.engine.orchestrator.dag.DagGraph;
import com.shuqing.bigdata.rule.engine.orchestrator.dag.DagNode;
import com.shuqing.bigdata.rule.engine.orchestrator.dag.DagValidator;
import com.shuqing.bigdata.rule.engine.orchestrator.retry.ExponentialBackoff;
import com.shuqing.bigdata.rule.engine.orchestrator.retry.FixedBackoff;
import com.shuqing.bigdata.rule.engine.orchestrator.retry.RetryExecutor;
import com.shuqing.bigdata.rule.engine.orchestrator.retry.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 依赖调度器。
 *
 * <p>基于拓扑排序与入度消减实现 DAG 任务调度：
 * <ol>
 *   <li>校验图无环；</li>
 *   <li>从入度为 0 的节点开始，提交到线程池并发执行；</li>
 *   <li>节点执行成功后消减后继入度，新的入度为 0 节点进入就绪队列；</li>
 *   <li>节点失败时按重试策略重试，最终失败则触发告警并跳过其所有后继；</li>
 *   <li>支持超时控制与停止信号。</li>
 * </ol>
 * </p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>使用固定大小线程池，避免无限制并发打爆下游；</li>
 *   <li>执行器按 taskType 分派，未匹配类型走 NOOP 执行器（仅日志）；</li>
 *   <li>调度为同步阻塞方法，由上层服务决定是否异步包装；</li>
 *   <li>停止通过 stopSignal 标志位实现，已在就绪队列的任务会执行完。</li>
 * </ul>
 * </p>
 */
@Component
public class DependencyScheduler {

    private static final Logger log = LoggerFactory.getLogger(DependencyScheduler.class);

    private final AlertManager alertManager;
    private final Map<String, TaskExecutor> executorsByType;
    private final ExecutorService executorService;
    private final RetryExecutor retryExecutor;

    /** 运行中图集合，key=dagId，value=stop 标志 */
    private final Map<String, Boolean> stopSignals = new ConcurrentHashMap<>();

    /**
     * 构造函数：Spring 自动注入 AlertManager 与所有 TaskExecutor Bean。
     *
     * @param alertManager 告警管理器
     * @param executors    任务执行器列表
     */
    public DependencyScheduler(AlertManager alertManager, List<TaskExecutor> executors) {
        this(alertManager, executors, Executors.newFixedThreadPool(8), new RetryExecutor());
    }

    /**
     * 测试构造函数：允许注入线程池与重试执行器。
     */
    public DependencyScheduler(AlertManager alertManager, List<TaskExecutor> executors,
                               ExecutorService executorService, RetryExecutor retryExecutor) {
        this.alertManager = alertManager;
        this.executorsByType = executors.stream()
                .collect(Collectors.toMap(TaskExecutor::taskType, Function.identity(), (a, b) -> a));
        this.executorService = executorService;
        this.retryExecutor = retryExecutor;
    }

    /**
     * 调度执行整个 DAG。
     *
     * <p>阻塞直到所有可达节点执行完成或被停止。返回每个节点的执行结果。</p>
     *
     * @param graph 待执行图（会被写入运行时状态）
     * @return 节点 ID -> 执行结果
     */
    public Map<String, TaskResult> schedule(DagGraph graph) {
        DagValidator.validate(graph);
        graph.setStatus(DagGraph.STATUS_RUNNING);
        graph.setStartedAt(LocalDateTime.now());
        stopSignals.put(graph.getId(), Boolean.FALSE);

        Map<String, TaskResult> results = new LinkedHashMap<>();
        Map<String, Integer> remainingInDegree = new HashMap<>();
        for (DagNode node : graph.allNodes()) {
            remainingInDegree.put(node.getId(), node.getInDegree());
            node.setStatus(DagNode.STATUS_PENDING);
        }

        // 就绪队列：入度为 0 的节点
        java.util.concurrent.ConcurrentLinkedQueue<String> ready = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (String id : graph.roots()) {
            ready.add(id);
        }

        // 已提交 Future 映射
        Map<String, Future<TaskResult>> futures = new ConcurrentHashMap<>();
        // 失败节点集合，用于跳过其后继
        java.util.Set<String> failedNodes = ConcurrentHashMap.newKeySet();

        while (!ready.isEmpty() || !futures.isEmpty()) {
            // 检查停止信号
            if (Boolean.TRUE.equals(stopSignals.get(graph.getId()))) {
                graph.setStatus(DagGraph.STATUS_STOPPED);
                break;
            }

            // 提交就绪节点
            while (!ready.isEmpty()) {
                String nodeId = ready.poll();
                DagNode node = graph.node(nodeId);
                if (node == null) {
                    continue;
                }
                // 若任一前驱失败，跳过该节点
                if (shouldSkip(graph, nodeId, failedNodes)) {
                    node.setStatus(DagNode.STATUS_SKIPPED);
                    continue;
                }
                node.setStatus(DagNode.STATUS_RUNNING);
                node.setStartedAt(LocalDateTime.now());
                Future<TaskResult> future = executorService.submit(() -> executeWithRetry(node, graph.getId()));
                futures.put(nodeId, future);
            }

            // 轮询已提交任务完成情况
            if (!futures.isEmpty()) {
                pollCompleted(graph, futures, results, remainingInDegree, ready, failedNodes);
                // 短暂让出 CPU，避免空转
                if (!futures.isEmpty() && ready.isEmpty()) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // 汇总图状态
        graph.setFinishedAt(LocalDateTime.now());
        if (DagGraph.STATUS_RUNNING.equals(graph.getStatus())) {
            boolean anyFailed = results.values().stream().anyMatch(r -> !r.isSuccess());
            graph.setStatus(anyFailed ? DagGraph.STATUS_FAILED : DagGraph.STATUS_SUCCESS);
        }
        stopSignals.remove(graph.getId());
        return results;
    }

    /**
     * 请求停止指定 DAG。已在执行中的节点会跑完，未开始的就绪节点会被跳过。
     *
     * @param dagId 图 ID
     */
    public void stop(String dagId) {
        stopSignals.put(dagId, Boolean.TRUE);
    }

    /**
     * 关闭调度器线程池。
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 判断节点是否应被跳过：任一前驱失败则跳过。
     */
    private boolean shouldSkip(DagGraph graph, String nodeId, java.util.Set<String> failedNodes) {
        for (String pred : graph.predecessors(nodeId)) {
            if (failedNodes.contains(pred)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 带重试执行单个节点。
     */
    private TaskResult executeWithRetry(DagNode node, String dagId) {
        TaskExecutor executor = executorsByType.get(node.getTaskType());
        if (executor == null) {
            log.warn("no executor for taskType={}, node={} will be marked FAILED", node.getTaskType(), node.getId());
            return TaskResult.failure(node.getId(), "NO_EXECUTOR_FOR_TYPE:" + node.getTaskType(), 0);
        }
        RetryPolicy policy = buildPolicy(node);
        int maxAttempts = node.getMaxRetries() + 1;
        try {
            return retryExecutor.execute(
                    () -> executor.execute(node, Map.of()),
                    policy,
                    maxAttempts,
                    e -> !(e instanceof InterruptedException));
        } catch (Exception e) {
            return TaskResult.failure(node.getId(), e.getMessage(), 0);
        }
    }

    /**
     * 根据节点配置构建重试策略。
     */
    private RetryPolicy buildPolicy(DagNode node) {
        String strategy = node.getBackoffStrategy() == null ? "FIXED" : node.getBackoffStrategy().toUpperCase();
        if ("EXPONENTIAL".equals(strategy)) {
            return new ExponentialBackoff(node.getBackoffIntervalMs(), 2.0, 30_000L);
        }
        return new FixedBackoff(node.getBackoffIntervalMs());
    }

    /**
     * 轮询已完成任务，更新入度、触发告警、推进就绪队列。
     */
    private void pollCompleted(DagGraph graph, Map<String, Future<TaskResult>> futures,
                               Map<String, TaskResult> results,
                               Map<String, Integer> remainingInDegree,
                               java.util.concurrent.ConcurrentLinkedQueue<String> ready,
                               java.util.Set<String> failedNodes) {
        for (String nodeId : new java.util.ArrayList<>(futures.keySet())) {
            Future<TaskResult> future = futures.get(nodeId);
            if (!future.isDone()) {
                continue;
            }
            try {
                TaskResult result = future.get();
                results.put(nodeId, result);
                DagNode node = graph.node(nodeId);
                node.setFinishedAt(LocalDateTime.now());
                if (result.isSuccess()) {
                    node.setStatus(DagNode.STATUS_SUCCESS);
                    // 消减后继入度
                    for (String succ : graph.successors(nodeId)) {
                        int deg = remainingInDegree.merge(succ, -1, Integer::sum);
                        if (deg == 0) {
                            ready.add(succ);
                        }
                    }
                } else {
                    node.setStatus(DagNode.STATUS_FAILED);
                    node.setErrorMessage(result.getErrorMessage());
                    failedNodes.add(nodeId);
                    fireAlert(graph, node, result);
                }
                futures.remove(nodeId);
            } catch (Exception e) {
                DagNode node = graph.node(nodeId);
                node.setStatus(DagNode.STATUS_FAILED);
                node.setErrorMessage(e.getMessage());
                node.setFinishedAt(LocalDateTime.now());
                failedNodes.add(nodeId);
                results.put(nodeId, TaskResult.failure(nodeId, e.getMessage(), 0));
                fireAlert(graph, node, TaskResult.failure(nodeId, e.getMessage(), 0));
                futures.remove(nodeId);
            }
        }
    }

    /**
     * 触发任务失败告警。
     */
    private void fireAlert(DagGraph graph, DagNode node, TaskResult result) {
        try {
            AlertEvent event = AlertEvent.builder()
                    .id(UUID.randomUUID().toString())
                    .type(AlertEvent.TYPE_TASK_FAILED)
                    .level(AlertEvent.LEVEL_ERROR)
                    .dagId(graph.getId())
                    .nodeId(node.getId())
                    .title("Task failed: " + node.getName())
                    .message(result.getErrorMessage())
                    .triggeredAt(LocalDateTime.now())
                    .extras(Map.of("taskType", node.getTaskType(), "command", String.valueOf(node.getCommand())))
                    .build();
            alertManager.dispatch(event);
        } catch (Exception e) {
            log.warn("fire alert failed for node={}: {}", node.getId(), e.getMessage());
        }
    }
}