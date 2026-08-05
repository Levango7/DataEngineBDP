package com.shuqing.bigdata.governance.lineage.service;

import com.shuqing.bigdata.governance.lineage.model.LineageGraph;
import com.shuqing.bigdata.governance.lineage.model.LineageEdge;
import com.shuqing.bigdata.governance.lineage.model.LineageNode;
import com.shuqing.bigdata.governance.lineage.model.LineageQueryResult;
import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LineageQueryService} 集成测试。
 *
 * <p>构造链式血缘 {@code a → b → c → d}，验证上下游 BFS 与影响分析。</p>
 *
 * @author shuqing-bigdata
 */
@SpringBootTest
@Transactional
@DisplayName("血缘查询服务测试")
class LineageQueryServiceTest {

    @Autowired
    private LineageAnalyzerService analyzerService;

    @Autowired
    private LineageQueryService queryService;

    @Autowired
    private LineageGraphWriter graphWriter;

    @BeforeEach
    void buildChain() {
        graphWriter.clear();
        // 构造 a → b → c → d 链
        analyzerService.analyze("INSERT INTO b SELECT x FROM a", SqlDialect.ANSI);
        analyzerService.analyze("INSERT INTO c SELECT x FROM b", SqlDialect.ANSI);
        analyzerService.analyze("INSERT INTO d SELECT x FROM c", SqlDialect.ANSI);
    }

    @Test
    @DisplayName("上游查询：d 的上游为 c → b → a")
    void testUpstream() {
        LineageQueryResult result = queryService.getUpstream("d", 5);
        assertEquals(3, result.getTables().size());
        assertTrue(result.getTables().contains("c"));
        assertTrue(result.getTables().contains("b"));
        assertTrue(result.getTables().contains("a"));
    }

    @Test
    @DisplayName("下游查询：a 的下游为 b → c → d")
    void testDownstream() {
        LineageQueryResult result = queryService.getDownstream("a", 5);
        assertEquals(3, result.getTables().size());
        assertTrue(result.getTables().contains("b"));
        assertTrue(result.getTables().contains("c"));
        assertTrue(result.getTables().contains("d"));
    }

    @Test
    @DisplayName("影响分析：变更 a 影响 b/c/d")
    void testImpactAnalysis() {
        LineageQueryResult result = queryService.impactAnalysis("a");
        assertEquals(3, result.getTables().size());
        assertTrue(result.getPaths().stream().anyMatch(p -> p.contains("a -> b -> c -> d")));
    }

    @Test
    @DisplayName("深度限制：a 下游深度 1 仅 b")
    void testDepthLimit() {
        LineageQueryResult result = queryService.getDownstream("a", 1);
        assertEquals(1, result.getTables().size());
        assertTrue(result.getTables().contains("b"));
    }

    @Test
    @DisplayName("中间节点：b 上游 a 下游 c/d")
    void testMiddleNode() {
        LineageQueryResult up = queryService.getUpstream("b", 5);
        assertEquals(1, up.getTables().size());
        assertTrue(up.getTables().contains("a"));

        LineageQueryResult down = queryService.getDownstream("b", 5);
        assertEquals(2, down.getTables().size());
        assertTrue(down.getTables().contains("c"));
        assertTrue(down.getTables().contains("d"));
    }

    @Test
    @DisplayName("叶子节点：d 无下游")
    void testLeafNode() {
        LineageQueryResult result = queryService.getDownstream("d", 5);
        assertTrue(result.getTables().isEmpty());
    }

    @Test
    @DisplayName("根节点：a 无上游")
    void testRootNode() {
        LineageQueryResult result = queryService.getUpstream("a", 5);
        assertTrue(result.getTables().isEmpty());
    }

    @Test
    @DisplayName("不存在表：返回空")
    void testNonExistentTable() {
        LineageQueryResult up = queryService.getUpstream("nonexistent", 5);
        assertTrue(up.getTables().isEmpty());
        LineageQueryResult down = queryService.getDownstream("nonexistent", 5);
        assertTrue(down.getTables().isEmpty());
    }
}