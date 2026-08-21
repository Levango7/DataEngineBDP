package com.shuqing.bigdata.governance.lineage.service;

import com.shuqing.bigdata.governance.lineage.model.LineageGraph;
import com.shuqing.bigdata.governance.lineage.model.LineageNode;
import com.shuqing.bigdata.governance.lineage.model.LineageEdge;
import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LineageAnalyzerService} 集成测试。
 *
 * <p>使用 Spring Boot Test + H2 内存数据库，验证端到端血缘分析流程。</p>
 *
 * @author shuqing-bigdata
 */
@SpringBootTest
@Transactional
@DisplayName("血缘分析服务集成测试")
class LineageAnalyzerServiceTest {

    @Autowired
    private LineageAnalyzerService analyzerService;

    @Autowired
    private LineageGraphWriter graphWriter;

    @BeforeEach
    void cleanUp() {
        graphWriter.clear();
    }

    @Test
    @DisplayName("INSERT SELECT 表级 + 字段级血缘")
    void testAnalyzeInsertSelect() {
        String sql = "INSERT INTO dwd.wide (oid, uname) "
                + "SELECT a.id, b.name FROM ods.orders a JOIN dim.user b ON a.uid = b.id";
        LineageGraph graph = analyzerService.analyze(sql, SqlDialect.ANSI);

        // 表级：2 条（ods.orders → dwd.wide, dim.user → dwd.wide）
        assertEquals(2, graph.getTableEdges().size());
        // 字段级：2 条（ods.orders.id → dwd.wide.oid, dim.user.name → dwd.wide.uname）
        assertEquals(2, graph.getColumnEdges().size());
        // 节点：3 表级（2 源 + 1 目标）+ 4 字段级（2 源 + 2 目标）= 7
        assertEquals(7, graph.getNodes().size());
    }

    @Test
    @DisplayName("纯 SELECT 虚拟目标")
    void testAnalyzePureSelect() {
        String sql = "SELECT a.x, b.y FROM db1.t1 a JOIN db1.t2 b ON a.id = b.id";
        LineageGraph graph = analyzerService.analyze(sql, SqlDialect.ANSI);
        // 表级：0（无 INSERT）
        assertTrue(graph.getTableEdges().isEmpty());
        // 字段级：2（→ result.x, → result.y）
        assertEquals(2, graph.getColumnEdges().size());
    }

    @Test
    @DisplayName("INSERT VALUES 无血缘")
    void testAnalyzeInsertValues() {
        String sql = "INSERT INTO db1.t1 (a, b) VALUES (1, 2)";
        LineageGraph graph = analyzerService.analyze(sql, SqlDialect.ANSI);
        assertTrue(graph.getEdges().isEmpty());
    }

    @Test
    @DisplayName("方言自动检测")
    void testAnalyzeAutoDialect() {
        String sql = "INSERT OVERWRITE TABLE dws.r SELECT x FROM dwd.w";
        LineageGraph graph = analyzerService.analyze(sql);
        assertEquals(1, graph.getTableEdges().size());
        assertEquals("HIVE", graph.getDialect());
    }

    @Test
    @DisplayName("空 SQL 返回空图")
    void testAnalyzeEmpty() {
        LineageGraph graph = analyzerService.analyze("");
        assertTrue(graph.getNodes().isEmpty());
        assertTrue(graph.getEdges().isEmpty());
    }

    @Test
    @DisplayName("图谱持久化到 H2")
    void testPersistToH2() {
        String sql = "INSERT INTO b SELECT x FROM a";
        analyzerService.analyze(sql, SqlDialect.ANSI);
        // 内存图应有数据
        assertTrue(graphWriter.getKnownTables().contains("a"));
        assertTrue(graphWriter.getKnownTables().contains("b"));
        assertEquals(1, graphWriter.getDirectDownstream("a").size());
        assertEquals(1, graphWriter.getDirectUpstream("b").size());
    }
}