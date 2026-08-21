package com.shuqing.bigdata.rule.engine.orchestrator.service;

import com.shuqing.bigdata.rule.engine.orchestrator.dag.DagGraph;
import com.shuqing.bigdata.rule.engine.orchestrator.dag.DagValidator;
import com.shuqing.bigdata.rule.engine.orchestrator.scheduler.DependencyScheduler;
import com.shuqing.bigdata.rule.engine.orchestrator.scheduler.TaskResult;
import com.shuqing.bigdata.rule.engine.orchestrator.visual.DagJsonExporter;
import com.shuqing.bigdata.rule.engine.orchestrator.visual.MermaidGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编排引擎业务服务。
 *
 * <p>对外提供 DAG 提交、查询、停止与可视化能力，是 controller 与调度器之间的门面。
 * 内部维护一个内存图仓库（{@link ConcurrentHashMap}），MVP 阶段不持久化，
 * 后续可替换为 JPA Repository。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>submit 时自动生成 id、校验无环、初始化状态为 DRAFT；</li>
 *   <li>runDag 同步执行并返回结果，便于 API 一次性返回；</li>
 *   <li>visualize 返回 Mermaid 文本，exportJson 返回 JSON Map。</li>
 * </ul>
 * </p>
 */
@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);

    private final DependencyScheduler scheduler;
    /** 内存图仓库：dagId -> DagGraph */
    private final Map<String, DagGraph> graphStore = new ConcurrentHashMap<>();
    /** 执行结果仓库：dagId -> 节点结果 */
    private final Map<String, Map<String, TaskResult>> resultStore = new ConcurrentHashMap<>();

    public OrchestratorService(DependencyScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 提交 DAG。校验无环后存入仓库，返回带 id 的图对象。
     *
     * @param graph 待提交图（id 可为空，将自动生成）
     * @return 已入库的图
     * @throws IllegalStateException 图存在环
     */
    public DagGraph submit(DagGraph graph) {
        if (graph.getId() == null || graph.getId().isBlank()) {
            graph.setId(UUID.randomUUID().toString());
        }
        graph.recomputeInDegrees();
        DagValidator.validate(graph);
        if (graph.getStatus() == null) {
            graph.setStatus(DagGraph.STATUS_DRAFT);
        }
        LocalDateTime now = LocalDateTime.now();
        graph.setCreatedAt(now);
        graph.setUpdatedAt(now);
        graphStore.put(graph.getId(), graph);
        log.info("dag submitted id={} name={} nodes={}", graph.getId(), graph.getName(), graph.nodeIds().size());
        return graph;
    }

    /**
     * 同步执行指定 DAG。
     *
     * @param dagId 图 ID
     * @return 各节点执行结果
     * @throws IllegalArgumentException 图不存在
     */
    public Map<String, TaskResult> runDag(String dagId) {
        DagGraph graph = requireGraph(dagId);
        Map<String, TaskResult> results = scheduler.schedule(graph);
        resultStore.put(dagId, results);
        graph.setUpdatedAt(LocalDateTime.now());
        return results;
    }

    /**
     * 查询图定义。
     *
     * @param dagId 图 ID
     * @return 图对象；不存在返回 null
     */
    public DagGraph getDag(String dagId) {
        return graphStore.get(dagId);
    }

    /**
     * 查询执行结果。
     *
     * @param dagId 图 ID
     * @return 节点结果 Map；未执行过返回 null
     */
    public Map<String, TaskResult> getResults(String dagId) {
        return resultStore.get(dagId);
    }

    /**
     * 列出所有已提交图。
     *
     * @return 图列表
     */
    public List<DagGraph> listAll() {
        return List.copyOf(graphStore.values());
    }

    /**
     * 停止正在执行的 DAG。
     *
     * @param dagId 图 ID
     */
    public void stop(String dagId) {
        requireGraph(dagId);
        scheduler.stop(dagId);
        log.info("dag stop requested id={}", dagId);
    }

    /**
     * 生成 Mermaid 可视化文本。
     *
     * @param dagId 图 ID
     * @return Mermaid 文本
     */
    public String visualize(String dagId) {
        return MermaidGenerator.generate(requireGraph(dagId));
    }

    /**
     * 导出 JSON 结构。
     *
     * @param dagId 图 ID
     * @return JSON 兼容 Map
     */
    public Map<String, Object> exportJson(String dagId) {
        return DagJsonExporter.toMap(requireGraph(dagId));
    }

    /**
     * 删除 DAG。
     *
     * @param dagId 图 ID
     * @return 是否删除成功
     */
    public boolean delete(String dagId) {
        boolean removed = graphStore.remove(dagId) != null;
        resultStore.remove(dagId);
        return removed;
    }

    /**
     * 获取图或抛异常。
     */
    private DagGraph requireGraph(String dagId) {
        DagGraph graph = graphStore.get(dagId);
        if (graph == null) {
            throw new IllegalArgumentException("dag not found: " + dagId);
        }
        return graph;
    }
}