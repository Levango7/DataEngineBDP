package com.levango7.dataenginebdp.governance.lineage.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 血缘图谱（内存视图）。
 *
 * <p>一次 {@code analyze(sql, dialect)} 调用的产物，包含本次分析涉及的节点与边。
 * 该类为不可变快照：构造完成后节点/边集合不再变化，便于序列化为 JSON 返回前端。</p>
 *
 * @author shuqing-bigdata
 */
public class LineageGraph {

    private final List<LineageNode> nodes;
    private final List<LineageEdge> edges;
    private final String sourceSql;
    private final String dialect;
    private final long analyzeTimeMs;

    /**
     * 构造空图。
     *
     * @param sourceSql     源 SQL
     * @param dialect       方言
     * @param analyzeTimeMs 分析耗时（毫秒）
     */
    public LineageGraph(String sourceSql, String dialect, long analyzeTimeMs) {
        this.nodes = new ArrayList<>();
        this.edges = new ArrayList<>();
        this.sourceSql = sourceSql;
        this.dialect = dialect;
        this.analyzeTimeMs = analyzeTimeMs;
    }

    /**
     * 添加节点（去重，按 fullName + nodeType）。
     *
     * @param node 节点
     */
    public void addNode(LineageNode node) {
        if (node == null || node.getFullName() == null) {
            return;
        }
        for (LineageNode existing : nodes) {
            if (Objects.equals(existing.getFullName(), node.getFullName())
                    && existing.getNodeType() == node.getNodeType()) {
                return;
            }
        }
        nodes.add(node);
    }

    /**
     * 添加边（去重，按 source + target + relationType）。
     *
     * @param edge 边
     */
    public void addEdge(LineageEdge edge) {
        if (edge == null || edge.getSourceFullName() == null || edge.getTargetFullName() == null) {
            return;
        }
        // 自环忽略
        if (edge.getSourceFullName().equals(edge.getTargetFullName())) {
            return;
        }
        for (LineageEdge existing : edges) {
            if (Objects.equals(existing.getSourceFullName(), edge.getSourceFullName())
                    && Objects.equals(existing.getTargetFullName(), edge.getTargetFullName())
                    && existing.getRelationType() == edge.getRelationType()) {
                return;
            }
        }
        edges.add(edge);
    }

    /**
     * 获取所有节点。
     *
     * @return 节点列表
     */
    public List<LineageNode> getNodes() {
        return nodes;
    }

    /**
     * 获取所有边。
     *
     * @return 边列表
     */
    public List<LineageEdge> getEdges() {
        return edges;
    }

    public String getSourceSql() {
        return sourceSql;
    }

    public String getDialect() {
        return dialect;
    }

    public long getAnalyzeTimeMs() {
        return analyzeTimeMs;
    }

    /**
     * 获取表级节点。
     *
     * @return 表节点列表
     */
    public List<LineageNode> getTableNodes() {
        List<LineageNode> result = new ArrayList<>();
        for (LineageNode node : nodes) {
            if (node.getNodeType() == LineageNode.NodeType.TABLE) {
                result.add(node);
            }
        }
        return result;
    }

    /**
     * 获取字段级节点。
     *
     * @return 字段节点列表
     */
    public List<LineageNode> getColumnNodes() {
        List<LineageNode> result = new ArrayList<>();
        for (LineageNode node : nodes) {
            if (node.getNodeType() == LineageNode.NodeType.COLUMN) {
                result.add(node);
            }
        }
        return result;
    }

    /**
     * 获取表级边。
     *
     * @return 表级边列表
     */
    public List<LineageEdge> getTableEdges() {
        List<LineageEdge> result = new ArrayList<>();
        for (LineageEdge edge : edges) {
            if (edge.getRelationType() == LineageEdge.RelationType.TABLE_LINEAGE) {
                result.add(edge);
            }
        }
        return result;
    }

    /**
     * 获取字段级边。
     *
     * @return 字段级边列表
     */
    public List<LineageEdge> getColumnEdges() {
        List<LineageEdge> result = new ArrayList<>();
        for (LineageEdge edge : edges) {
            if (edge.getRelationType() == LineageEdge.RelationType.COLUMN_LINEAGE) {
                result.add(edge);
            }
        }
        return result;
    }

    /**
     * 转换为 ECharts 关系图前端友好格式。
     *
     * @return 包含 {@code categories}/{@code nodes}/{@code links} 的映射
     */
    public Map<String, Object> toEChartsFormat() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 分类：0=源表 1=目标表 2=源字段 3=目标字段
        List<Map<String, Object>> categories = new ArrayList<>();
        for (String name : new String[]{"源表", "目标表", "源字段", "目标字段"}) {
            Map<String, Object> cat = new LinkedHashMap<>();
            cat.put("name", name);
            categories.add(cat);
        }
        result.put("categories", categories);

        // 节点
        Set<String> targetTables = new LinkedHashSet<>();
        for (LineageEdge e : getTableEdges()) {
            targetTables.add(e.getTargetFullName());
        }
        Set<String> targetColumns = new LinkedHashSet<>();
        for (LineageEdge e : getColumnEdges()) {
            targetColumns.add(e.getTargetFullName());
        }

        List<Map<String, Object>> echartsNodes = new ArrayList<>();
        for (LineageNode node : nodes) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", node.getFullName());
            n.put("name", node.getDisplayName() != null ? node.getDisplayName() : node.getFullName());
            int category;
            if (node.getNodeType() == LineageNode.NodeType.TABLE) {
                category = targetTables.contains(node.getFullName()) ? 1 : 0;
            } else {
                category = targetColumns.contains(node.getFullName()) ? 3 : 2;
            }
            n.put("category", category);
            n.put("nodeType", node.getNodeType().name());
            echartsNodes.add(n);
        }
        result.put("nodes", echartsNodes);

        // 边
        List<Map<String, Object>> echartsLinks = new ArrayList<>();
        for (LineageEdge edge : edges) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("source", edge.getSourceFullName());
            l.put("target", edge.getTargetFullName());
            l.put("relationType", edge.getRelationType().name());
            if (edge.getExpression() != null) {
                l.put("expression", edge.getExpression());
            }
            echartsLinks.add(l);
        }
        result.put("links", echartsLinks);

        // 元信息
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sourceSql", sourceSql);
        meta.put("dialect", dialect);
        meta.put("analyzeTimeMs", analyzeTimeMs);
        meta.put("nodeCount", nodes.size());
        meta.put("edgeCount", edges.size());
        result.put("meta", meta);

        return result;
    }

    @Override
    public String toString() {
        return "LineageGraph{nodes=" + nodes.size() + ", edges=" + edges.size()
                + ", dialect=" + dialect + '}';
    }
}