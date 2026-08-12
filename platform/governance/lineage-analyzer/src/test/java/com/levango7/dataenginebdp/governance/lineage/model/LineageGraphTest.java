package com.levango7.dataenginebdp.governance.lineage.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LineageGraph} 单元测试。
 *
 * @author shuqing-bigdata
 */
@DisplayName("血缘图谱模型")
class LineageGraphTest {

    @Test
    @DisplayName("添加节点去重")
    void testAddNodeDedup() {
        LineageGraph graph = new LineageGraph("sql", "ANSI", 0);
        LineageNode n1 = new LineageNode("db.t", LineageNode.NodeType.TABLE);
        LineageNode n2 = new LineageNode("db.t", LineageNode.NodeType.TABLE);
        graph.addNode(n1);
        graph.addNode(n2);
        assertEquals(1, graph.getNodes().size());
    }

    @Test
    @DisplayName("添加边去重")
    void testAddEdgeDedup() {
        LineageGraph graph = new LineageGraph("sql", "ANSI", 0);
        LineageEdge e1 = new LineageEdge("a", "b", LineageEdge.RelationType.TABLE_LINEAGE);
        LineageEdge e2 = new LineageEdge("a", "b", LineageEdge.RelationType.TABLE_LINEAGE);
        graph.addEdge(e1);
        graph.addEdge(e2);
        assertEquals(1, graph.getEdges().size());
    }

    @Test
    @DisplayName("自环边被忽略")
    void testSelfLoopIgnored() {
        LineageGraph graph = new LineageGraph("sql", "ANSI", 0);
        graph.addEdge(new LineageEdge("a", "a", LineageEdge.RelationType.TABLE_LINEAGE));
        assertTrue(graph.getEdges().isEmpty());
    }

    @Test
    @DisplayName("表/字段节点分类")
    void testNodeClassification() {
        LineageGraph graph = new LineageGraph("sql", "ANSI", 0);
        graph.addNode(new LineageNode("db.t", LineageNode.NodeType.TABLE));
        graph.addNode(new LineageNode("db.t.c", LineageNode.NodeType.COLUMN));
        assertEquals(1, graph.getTableNodes().size());
        assertEquals(1, graph.getColumnNodes().size());
    }

    @Test
    @DisplayName("ECharts 格式输出")
    void testEChartsFormat() {
        LineageGraph graph = new LineageGraph("SELECT 1", "ANSI", 5);
        graph.addNode(new LineageNode("a", LineageNode.NodeType.TABLE));
        graph.addNode(new LineageNode("b", LineageNode.NodeType.TABLE));
        graph.addEdge(new LineageEdge("a", "b", LineageEdge.RelationType.TABLE_LINEAGE));
        Map<String, Object> result = graph.toEChartsFormat();
        assertNotNull(result.get("categories"));
        assertNotNull(result.get("nodes"));
        assertNotNull(result.get("links"));
        assertNotNull(result.get("meta"));
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) result.get("meta");
        assertEquals(2, meta.get("nodeCount"));
        assertEquals(1, meta.get("edgeCount"));
    }
}