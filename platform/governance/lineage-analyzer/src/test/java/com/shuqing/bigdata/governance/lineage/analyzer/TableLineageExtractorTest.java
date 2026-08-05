package com.shuqing.bigdata.governance.lineage.analyzer;

import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;
import com.shuqing.bigdata.sqlgateway.parser.SqlParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TableLineageExtractor} 单元测试。
 *
 * @author shuqing-bigdata
 */
@DisplayName("表级血缘提取器")
class TableLineageExtractorTest {

    private TableLineageExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new TableLineageExtractor(new SqlParserService());
    }

    @Test
    @DisplayName("INSERT INTO SELECT 单源表")
    void testInsertSelectSingleSource() {
        String sql = "INSERT INTO db2.t2 SELECT a, b FROM db1.t1";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertEquals(1, relations.size());
        assertEquals("db1.t1", relations.get(0).getSource());
        assertEquals("db2.t2", relations.get(0).getTarget());
        assertEquals(LineageRelation.RelationType.TABLE_LINEAGE,
                relations.get(0).getRelationType());
    }

    @Test
    @DisplayName("INSERT INTO SELECT JOIN 多源表")
    void testInsertSelectJoin() {
        String sql = "INSERT INTO dwd.order_wide SELECT a.id, b.name "
                + "FROM ods.orders a JOIN dim.user b ON a.uid = b.id";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertEquals(2, relations.size());
        List<String> sources = relations.stream().map(LineageRelation::getSource).toList();
        assertTrue(sources.contains("ods.orders"));
        assertTrue(sources.contains("dim.user"));
        relations.forEach(r -> assertEquals("dwd.order_wide", r.getTarget()));
    }

    @Test
    @DisplayName("INSERT OVERWRITE TABLE SELECT")
    void testInsertOverwrite() {
        String sql = "INSERT OVERWRITE TABLE dws.user_rebuy SELECT uid, cnt FROM dwd.order_wide";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.HIVE);
        assertEquals(1, relations.size());
        assertEquals("dwd.order_wide", relations.get(0).getSource());
        assertEquals("dws.user_rebuy", relations.get(0).getTarget());
    }

    @Test
    @DisplayName("INSERT VALUES 无源表")
    void testInsertValuesNoLineage() {
        String sql = "INSERT INTO db1.t1 (a, b) VALUES (1, 2)";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertTrue(relations.isEmpty());
    }

    @Test
    @DisplayName("纯 SELECT 无表级血缘")
    void testPureSelectNoLineage() {
        String sql = "SELECT a, b FROM db1.t1 WHERE a > 0";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertTrue(relations.isEmpty());
    }

    @Test
    @DisplayName("子查询作为源表")
    void testSubquerySource() {
        String sql = "INSERT INTO db2.t2 SELECT x FROM (SELECT a AS x FROM db1.t1) sub";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertEquals(1, relations.size());
        assertEquals("db1.t1", relations.get(0).getSource());
        assertEquals("db2.t2", relations.get(0).getTarget());
    }

    @Test
    @DisplayName("多 JOIN 三源表")
    void testThreeJoinSources() {
        String sql = "INSERT INTO target SELECT a.x, b.y, c.z "
                + "FROM a JOIN b ON a.id = b.id JOIN c ON b.id = c.id";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertEquals(3, relations.size());
        List<String> sources = relations.stream().map(LineageRelation::getSource).toList();
        assertTrue(sources.contains("a"));
        assertTrue(sources.contains("b"));
        assertTrue(sources.contains("c"));
    }

    @Test
    @DisplayName("空 SQL 返回空")
    void testEmptySql() {
        assertTrue(extractor.extract("", SqlDialect.ANSI).isEmpty());
        assertTrue(extractor.extract(null, SqlDialect.ANSI).isEmpty());
    }

    @Test
    @DisplayName("方言 null 自动检测")
    void testNullDialect() {
        String sql = "INSERT INTO b SELECT x FROM a";
        List<LineageRelation> relations = extractor.extract(sql, null);
        assertEquals(1, relations.size());
        assertEquals("a", relations.get(0).getSource());
        assertEquals("b", relations.get(0).getTarget());
    }

    @Test
    @DisplayName("自环过滤：INSERT INTO t SELECT FROM t")
    void testSelfLoopFiltered() {
        String sql = "INSERT INTO t SELECT x FROM t";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertTrue(relations.isEmpty());
    }
}