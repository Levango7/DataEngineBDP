package com.shuqing.bigdata.governance.lineage.service;

import com.shuqing.bigdata.governance.lineage.model.LineageEdge;
import com.shuqing.bigdata.governance.lineage.model.LineageGraph;
import com.shuqing.bigdata.governance.lineage.model.LineageNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 血缘图谱写入器。
 *
 * <p>双写策略：
 * <ul>
 *   <li><b>内存图</b>：{@link ConcurrentHashMap} 维护邻接表，供快速上下游查询</li>
 *   <li><b>H2/JPA</b>：通过 {@link LineageNodeRepository}/{@link LineageEdgeRepository}
 *       持久化，重启后可恢复</li>
 *   <li><b>NebulaGraph</b>（可选）：通过配置 {@code nebula.enabled=true} 启用，
 *       当前版本预留接口，实际写入降级为日志</li>
 * </ul>
 *
 * <p>内存图结构：{@code upstreamMap} 键为目标表，值为上游表集合；
 * {@code downstreamMap} 键为源表，值为下游表集合。仅记录表级血缘。</p>
 *
 * @author shuqing-bigdata
 */
@Service
public class LineageGraphWriter {

    private static final Logger log = LoggerFactory.getLogger(LineageGraphWriter.class);

    private final LineageNodeRepository nodeRepository;
    private final LineageEdgeRepository edgeRepository;

    /** 内存图：target → {source1, source2, ...} 上游 */
    private final Map<String, Set<String>> upstreamMap = new ConcurrentHashMap<>();
    /** 内存图：source → {target1, target2, ...} 下游 */
    private final Map<String, Set<String>> downstreamMap = new ConcurrentHashMap<>();
    /** 全部已知表节点 */
    private final Set<String> knownTables = ConcurrentHashMap.newKeySet();

    /** 是否启用 NebulaGraph 后端 */
    @Value("${nebula.enabled:false}")
    private boolean nebulaEnabled;

    /** NebulaGraph host（仅日志占位） */
    @Value("${nebula.host:127.0.0.1}")
    private String nebulaHost;

    /**
     * 构造写入器。
     *
     * @param nodeRepository 节点 Repository
     * @param edgeRepository 边 Repository
     */
    @Autowired
    public LineageGraphWriter(LineageNodeRepository nodeRepository,
                              LineageEdgeRepository edgeRepository) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
    }

    /**
     * 写入图谱（内存 + H2 + 可选 Nebula）。
     *
     * @param graph 血缘图谱
     */
    public synchronized void write(LineageGraph graph) {
        if (graph == null) {
            return;
        }
        // 1. 写内存图（仅表级边参与邻接表）
        for (LineageEdge edge : graph.getEdges()) {
            if (edge.getRelationType() == LineageEdge.RelationType.TABLE_LINEAGE) {
                String src = edge.getSourceFullName();
                String tgt = edge.getTargetFullName();
                upstreamMap.computeIfAbsent(tgt, k -> ConcurrentHashMap.newKeySet()).add(src);
                downstreamMap.computeIfAbsent(src, k -> ConcurrentHashMap.newKeySet()).add(tgt);
                knownTables.add(src);
                knownTables.add(tgt);
            }
        }

        // 2. 写 H2（节点 + 边，幂等）
        for (LineageNode node : graph.getNodes()) {
            nodeRepository.findByFullName(node.getFullName()).ifPresentOrElse(
                    existing -> {
                        existing.setUpdatedAt(Instant.now());
                        if (node.getSchemaName() != null) {
                            existing.setSchemaName(node.getSchemaName());
                        }
                        if (node.getTableName() != null) {
                            existing.setTableName(node.getTableName());
                        }
                        if (node.getColumnName() != null) {
                            existing.setColumnName(node.getColumnName());
                        }
                        if (node.getDisplayName() != null) {
                            existing.setDisplayName(node.getDisplayName());
                        }
                        nodeRepository.save(existing);
                    },
                    () -> nodeRepository.save(node)
            );
        }
        for (LineageEdge edge : graph.getEdges()) {
            // 查重：同 source+target+type 不重复写
            boolean exists = edgeRepository.findBySourceFullName(edge.getSourceFullName()).stream()
                    .anyMatch(e -> e.getTargetFullName().equals(edge.getTargetFullName())
                            && e.getRelationType() == edge.getRelationType());
            if (!exists) {
                edgeRepository.save(edge);
            }
        }

        // 3. NebulaGraph（可选占位）
        if (nebulaEnabled) {
            log.info("NebulaGraph 后端已启用 (host={})，当前版本降级为日志占位：{} 节点 {} 边",
                    nebulaHost, graph.getNodes().size(), graph.getEdges().size());
        }

        log.debug("图谱写入完成: {} 节点, {} 边", graph.getNodes().size(), graph.getEdges().size());
    }

    /**
     * 获取指定表的上游表集合（内存图，1 跳）。
     *
     * @param table 表全名
     * @return 上游表集合
     */
    public Set<String> getDirectUpstream(String table) {
        return upstreamMap.getOrDefault(table, new HashSet<>());
    }

    /**
     * 获取指定表的下游表集合（内存图，1 跳）。
     *
     * @param table 表全名
     * @return 下游表集合
     */
    public Set<String> getDirectDownstream(String table) {
        return downstreamMap.getOrDefault(table, new HashSet<>());
    }

    /**
     * 获取全部已知表。
     *
     * @return 表全名集合
     */
    public Set<String> getKnownTables() {
        return new HashSet<>(knownTables);
    }

    /**
     * 获取完整下游邻接表（用于 BFS 遍历）。
     *
     * @return downstreamMap 副本
     */
    public Map<String, Set<String>> getDownstreamMap() {
        Map<String, Set<String>> copy = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : downstreamMap.entrySet()) {
            copy.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        return copy;
    }

    /**
     * 获取完整上游邻接表（用于 BFS 遍历）。
     *
     * @return upstreamMap 副本
     */
    public Map<String, Set<String>> getUpstreamMap() {
        Map<String, Set<String>> copy = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : upstreamMap.entrySet()) {
            copy.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        return copy;
    }

    /**
     * 清空内存图与持久化存储（测试用）。
     */
    public synchronized void clear() {
        upstreamMap.clear();
        downstreamMap.clear();
        knownTables.clear();
        edgeRepository.deleteAll();
        nodeRepository.deleteAll();
    }
}