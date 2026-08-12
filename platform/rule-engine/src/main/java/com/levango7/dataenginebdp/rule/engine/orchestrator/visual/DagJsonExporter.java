package com.levango7.dataenginebdp.rule.engine.orchestrator.visual;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagEdge;
import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagGraph;
import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DAG JSON 导出器。
 *
 * <p>将 {@link DagGraph} 导出为前端可直接渲染的 JSON 结构，包含图元信息、
 * 节点列表与边列表。节点中附带运行时状态，便于前端着色与高亮。</p>
 *
 * <p>输出结构示例：
 * <pre>
 * {
 *   "id": "dag-1",
 *   "name": "ETL Pipeline",
 *   "status": "RUNNING",
 *   "nodes": [
 *     {"id":"n1","name":"Extract","taskType":"HTTP","status":"SUCCESS","inDegree":0}
 *   ],
 *   "edges": [
 *     {"source":"n1","target":"n2"}
 *   ]
 * }
 * </pre>
 * </p>
 */
public final class DagJsonExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private DagJsonExporter() {
    }

    /**
     * 导出为 Map 结构，便于进一步序列化或嵌入响应体。
     *
     * @param graph DAG 图
     * @return JSON 兼容的 Map 结构
     */
    public static Map<String, Object> toMap(DagGraph graph) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", graph.getId());
        result.put("name", graph.getName());
        result.put("description", graph.getDescription());
        result.put("status", graph.getStatus());
        result.put("createdAt", graph.getCreatedAt() == null ? null : graph.getCreatedAt().toString());
        result.put("updatedAt", graph.getUpdatedAt() == null ? null : graph.getUpdatedAt().toString());
        result.put("startedAt", graph.getStartedAt() == null ? null : graph.getStartedAt().toString());
        result.put("finishedAt", graph.getFinishedAt() == null ? null : graph.getFinishedAt().toString());

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (DagNode node : graph.allNodes()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", node.getId());
            n.put("name", node.getName());
            n.put("taskType", node.getTaskType());
            n.put("command", node.getCommand());
            n.put("timeoutSeconds", node.getTimeoutSeconds());
            n.put("maxRetries", node.getMaxRetries());
            n.put("backoffStrategy", node.getBackoffStrategy());
            n.put("backoffIntervalMs", node.getBackoffIntervalMs());
            n.put("status", node.getStatus());
            n.put("inDegree", node.getInDegree());
            n.put("startedAt", node.getStartedAt() == null ? null : node.getStartedAt().toString());
            n.put("finishedAt", node.getFinishedAt() == null ? null : node.getFinishedAt().toString());
            n.put("errorMessage", node.getErrorMessage());
            n.put("params", node.getParams());
            nodes.add(n);
        }
        result.put("nodes", nodes);

        List<Map<String, Object>> edges = new ArrayList<>();
        for (DagEdge edge : graph.getEdges()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("source", edge.getSource());
            e.put("target", edge.getTarget());
            e.put("condition", edge.getCondition());
            edges.add(e);
        }
        result.put("edges", edges);
        return result;
    }

    /**
     * 导出为格式化 JSON 字符串。
     *
     * @param graph DAG 图
     * @return JSON 文本
     */
    public static String toJson(DagGraph graph) {
        try {
            return MAPPER.writeValueAsString(toMap(graph));
        } catch (Exception e) {
            throw new IllegalStateException("export dag to json failed: " + e.getMessage(), e);
        }
    }
}