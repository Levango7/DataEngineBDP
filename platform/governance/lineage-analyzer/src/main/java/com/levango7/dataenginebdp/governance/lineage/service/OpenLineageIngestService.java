package com.levango7.dataenginebdp.governance.lineage.service;

import com.levango7.dataenginebdp.governance.lineage.model.LineageEdge;
import com.levango7.dataenginebdp.governance.lineage.model.LineageGraph;
import com.levango7.dataenginebdp.governance.lineage.model.LineageNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenLineage RunEvent 摄取服务（M4 lineage 归一：统一血缘入口）。
 *
 * <p>接收 <a href="https://openlineage.io/spec/">OpenLineage v1</a> RunEvent JSON
 * （单事件对象或事件数组，经 Spring 反序列化为 {@code Map}/{@code List}），把每个
 * 事件的 {@code inputs × outputs} 笛卡尔积映射为表级血缘边，经
 * {@link LineageGraphWriter} 双写内存图 + H2（可选 Nebula）。上游生产方为
 * {@code platform/batch-pipeline} 的 {@code batch_pipeline.openlineage} 发射器
 * （NDJSON 逐事件 POST），也兼容任何标准 OpenLineage 生产端。</p>
 *
 * <p>映射约定：
 * <ul>
 *   <li>数据集节点全名 = {@code <namespace>/<name>}，与 SQL 血缘的 {@code db.table}
 *       命名空间正交，查询/影响分析 API 通用</li>
 *   <li>边类型 {@link LineageEdge.RelationType#TABLE_LINEAGE}，dialect 记为
 *       {@code openlineage}，便于区分来源</li>
 *   <li>无 inputs/outputs 的事件（如 pipeline 父 Run 事件）合法，仅记录不产生边</li>
 *   <li>重复事件幂等：Writer 按 节点 fullName / 边 source+target+type 去重</li>
 * </ul>
 *
 * <p>刻意使用 {@code Map}/{@code List} 而非 Jackson {@code JsonNode}：
 * 平台已升级 Spring Boot 4（Jackson 3 主导），com.fasterxml JsonNode 无默认
 * HttpMessageConverter 支持，纯 Map 契约与转换器版本无关。</p>
 *
 * @author shuqing-bigdata
 */
@Service
public class OpenLineageIngestService {

    private static final Logger log = LoggerFactory.getLogger(OpenLineageIngestService.class);

    /** 摄取血缘边的 dialect 标记（区分 SQL 解析血缘与 OpenLineage 上报血缘） */
    public static final String DIALECT = "openlineage";

    /** 数据集 namespace 缺省值（OpenLineage 规范中 namespace 必填，此处容错回退） */
    private static final String DEFAULT_NAMESPACE = "openlineage";

    private final LineageGraphWriter graphWriter;

    @Autowired
    public OpenLineageIngestService(LineageGraphWriter graphWriter) {
        this.graphWriter = graphWriter;
    }

    /**
     * 摄取 OpenLineage 事件（单对象或数组）。
     *
     * @param body RunEvent JSON 对象或事件数组（Map / List&lt;Map&gt;）
     * @return 摄取汇总（事件数/去重节点数/边数/各事件摘要）
     * @throws IllegalArgumentException 事件缺必需字段（job.name / run.runId）或结构非法
     */
    public Map<String, Object> ingest(Object body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空（需 OpenLineage RunEvent 对象或数组）");
        }
        List<Object> events = new ArrayList<>();
        if (body instanceof List<?> list) {
            events.addAll(list);
        } else if (body instanceof Map) {
            events.add(body);
        } else {
            throw new IllegalArgumentException("请求体需为 JSON 对象或数组");
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException("事件数组为空");
        }

        long startMs = System.currentTimeMillis();
        int totalEdges = 0;
        Set<String> allNodes = new LinkedHashSet<>();
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Object ev : events) {
            Map<String, Object> s = ingestOne(ev, allNodes);
            summaries.add(s);
            totalEdges += (int) s.get("edges");
        }
        log.info("OpenLineage 摄取完成: events={}, nodes={}, edges={}, elapsedMs={}",
                events.size(), allNodes.size(), totalEdges, System.currentTimeMillis() - startMs);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", events.size());
        result.put("nodes", allNodes.size());
        result.put("edges", totalEdges);
        result.put("runs", summaries);
        return result;
    }

    /** 摄取单个 RunEvent，返回该事件摘要（nodes 为该事件去重后数量；allNodes 累积全量节点供聚合去重）。 */
    private Map<String, Object> ingestOne(Object ev, Set<String> allNodes) {
        if (!(ev instanceof Map<?, ?> event)) {
            throw new IllegalArgumentException("事件需为 JSON 对象");
        }
        if (!(event.get("job") instanceof Map<?, ?> job)) {
            throw new IllegalArgumentException("事件缺少 job.name（OpenLineage RunEvent 必需字段）");
        }
        String jobName = str(job.get("name"));
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("事件缺少 job.name（OpenLineage RunEvent 必需字段）");
        }
        String namespace = withDefault(str(job.get("namespace")), DEFAULT_NAMESPACE);
        if (!(event.get("run") instanceof Map<?, ?> run) || str(run.get("runId")) == null
                || str(run.get("runId")).isBlank()) {
            throw new IllegalArgumentException("事件缺少 run.runId（OpenLineage RunEvent 必需字段）");
        }
        String runId = str(run.get("runId"));
        String eventType = withDefault(str(event.get("eventType")), "UNSPECIFIED");

        List<String> inputs = datasets(namespace, event.get("inputs"));
        List<String> outputs = datasets(namespace, event.get("outputs"));

        LineageGraph graph = new LineageGraph(null, DIALECT, 0);
        Set<String> nodeNames = new LinkedHashSet<>(inputs);
        nodeNames.addAll(outputs);
        allNodes.addAll(nodeNames);
        for (String fullName : nodeNames) {
            LineageNode node = new LineageNode(fullName, LineageNode.NodeType.TABLE);
            node.setDisplayName(fullName);
            graph.addNode(node);
        }
        for (String in : inputs) {
            for (String out : outputs) {
                LineageEdge edge = new LineageEdge(in, out, LineageEdge.RelationType.TABLE_LINEAGE);
                edge.setDialect(DIALECT);
                graph.addEdge(edge);
            }
        }
        graphWriter.write(graph);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("job", namespace + "." + jobName);
        summary.put("runId", runId);
        summary.put("eventType", eventType);
        summary.put("nodes", nodeNames.size());
        summary.put("edges", graph.getEdges().size());
        return summary;
    }

    /** 提取 inputs/outputs 数据集全名列表（缺 name 的条目跳过，namespace 缺省回退）。 */
    private List<String> datasets(String defaultNamespace, Object array) {
        List<String> names = new ArrayList<>();
        if (!(array instanceof List<?> list)) {
            return names;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> ds)) {
                continue;
            }
            String name = str(ds.get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            names.add(withDefault(str(ds.get("namespace")), defaultNamespace) + "/" + name);
        }
        return names;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String withDefault(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
