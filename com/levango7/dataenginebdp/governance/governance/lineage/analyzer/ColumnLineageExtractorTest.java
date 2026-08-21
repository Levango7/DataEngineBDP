package com.shuqing.bigdata.governance.lineage.analyzer;

import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;
import com.shuqing.bigdata.sqlgateway.parser.SqlParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ColumnLineageExtractor} 单元测试。
 *
 * @author shuqing-bigdata
 */
@DisplayName("字段级血缘提取器")
class ColumnLineageExtractorTest {

    private ColumnLineageExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new ColumnLineageExtractor(new SqlParserService());
    }

    @Test
    @DisplayName("INSERT 指定列清单 + 简单列引用")
    void testInsertWithColumnList() {
        String sql = "INSERT INTO db2.t2 (c1, c2) SELECT a.x, b.y FROM db1.t1 a JOIN db1.t2 b ON a.id = b.id";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertEquals(2, relations.size());
        // a.x → t2.c1
        assertTrue(relations.stream().anyMatch(r ->
                r.getSource().equals("db1.t1.x") && r.getTarget().equals("db2.t2.c1")));
        // b.y → t2.c2
        assertTrue(relations.stream().anyMatch(r ->
                r.getSource().equals("db1.t2.y") && r.getTarget().equals("db2.t2.c2")));
    }

    @Test
    @DisplayName("INSERT 未指定列清单：目标列名取源列名")
    void testInsertWithoutColumnList() {
        String sql = "INSERT INTO db2.t2 SELECT a.x, a.y FROM db1.t1 a";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertEquals(2, relations.size());
        assertTrue(relations.stream().anyMatch(r ->
                r.getSource().equals("db1.t1.x") && r.getTarget().equals("db2.t2.x")));
        assertTrue(relations.stream().anyMatch(r ->
                r.getSource().equals("db1.t1.y") && r.getTarget().equals("db2.t2.y")));
    }

    @Test
    @DisplayName("SELECT 别名作为目标列名")
    void testSelectAlias() {
        String sql = "INSERT INTO db2.t2 SELECT a.x AS px, a.y AS py FROM db1.t1 a";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertEquals(2, relations.size());
        assertTrue(relations.stream().anyMatch(r ->
                r.getSource().equals("db1.t1.x") && r.getTarget().equals("db2.t2.px")));
        assertTrue(relations.stream().anyMatch(r ->
                r.getSource().equals("db1.t1.y") && r.getTarget().equals("db2.t2.py")));
    }

    @Test
    @DisplayName("纯 SELECT 虚拟目标 result")
    void testPureSelectVirtualTarget() {
        String sql = "SELECT a.x, b.y FROM db1.t1 a JOIN db1.t2 b ON a.id = b.id";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertEquals(2, relations.size());
        assertTrue(relations.stream().anyMatch(r ->
                r.getSource().equals("db1.t1.x") && r.getTarget().equals("result.x")));
        assertTrue(relations.stream().anyMatch(r ->
                r.getSource().equals("db1.t2.y") && r.getTarget().equals("result.y")));
    }

    @Test
    @DisplayName("聚合函数表达式：SUM(a.x) → target")
    void testAggregateExpression() {
        String sql = "INSERT INTO db2.t2 (total) SELECT SUM(a.x) FROM db1.t1 a";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertEquals(1, relations.size());
        assertEquals("db1.t1.x", relations.get(0).getSource());
        assertEquals("db2.t2.total", relations.get(0).getTarget());
        assertNotNull(relations.get(0).getExpression());
    }

    @Test
    @DisplayName("多列表达式：a.x + b.y → target")
    void testMultiColumnExpression() {
        String sql = "INSERT INTO db2.t2 (sum_xy) SELECT a.x + b.y FROM db1.t1 a JOIN db1.t2 b ON a.id = b.id";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertEquals(2, relations.size());
        List<String> sources = relations.stream().map(LineageRelation::getSource).toList();
        assertTrue(sources.contains("db1.t1.x"));
        assertTrue(sources.contains("db1.t2.y"));
        relations.forEach(r -> assertEquals("db2.t2.sum_xy", r.getTarget()));
    }

    @Test
    @DisplayName("裸列名 + 单表：自动补全表名")
    void testBareColumnSingleTable() {
        String sql = "INSERT INTO db2.t2 (c1) SELECT x FROM db1.t1";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertEquals(1, relations.size());
        assertEquals("db1.t1.x", relations.get(0).getSource());
        assertEquals("db2.t2.c1", relations.get(0).getTarget());
    }

    @Test
    @DisplayName("常量 SELECT 无字段血缘")
    void testConstantSelect() {
        String sql = "INSERT INTO db2.t2 (c1) SELECT 1";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertTrue(relations.isEmpty());
    }

    @Test
    @DisplayName("空 SQL 返回空")
    void testEmptySql() {
        assertTrue(extractor.extract("", SqlDialect.ANSI).isEmpty());
        assertTrue(extractor.extract(null, SqlDialect.ANSI).isEmpty());
    }

    @Test
    @DisplayName("自环过滤：a.x → a.x 不产生")
    void testSelfLoopFiltered() {
        // 构造使源与目标同名的场景较难，此处验证表达式包含自身列时不会产生自环
        String sql = "INSERT INTO t1 (x) SELECT a.x FROM t1 a";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        // t1.x → t1.x 应被过滤
        assertTrue(relations.isEmpty());
    }

    @Test
    @DisplayName("LEFT JOIN 字段血缘")
    void testLeftJoin() {
        String sql = "INSERT INTO dwd.wide (oid, uname) "
                + "SELECT a.id, b.name FROM ods.orders a LEFT JOIN dim.user b ON a.uid = b.id";
        List<LineageRelation> relations = extractor.extract(sql, SqlDialect.ANSI);
        assertEquals(2, relations.size());
        assertTrue(relations.stream().anyMatch(r ->
                r.getSource().equals("ods.orders.id") && r.getTarget().equals("dwd.wide.oid")));
        assertTrue(relations.stream().anyMatch(r ->
                r.getSource().equals("dim.user.name") && r.getTarget().equals("dwd.wide.uname")));
    }
}