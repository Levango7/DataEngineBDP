package com.levango7.dataenginebdp.sqlgateway.optimizer;

import com.levango7.dataenginebdp.sqlgateway.parser.ASTNode;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AstToRelConverter} 单元测试。
 *
 * <p>验证 AST → RelNode 转换的正确性，覆盖 SELECT/JOIN/WHERE/GROUP BY/ORDER BY/LIMIT/UNION/子查询。</p>
 *
 * @author shuqing-bigdata
 */
class AstToRelConverterTest {

    private SqlParserService parser;
    private AstToRelConverter converter;

    @BeforeEach
    void setUp() {
        parser = new SqlParserService();
        converter = new AstToRelConverter();
    }

    private RelNode parseAndConvert(String sql) {
        ASTNode ast = parser.parse(sql, SqlDialect.ANSI);
        return converter.convert(ast);
    }

    @Test
    @DisplayName("简单 SELECT * FROM t → TableScan")
    void testConvertSimpleSelectStar() {
        RelNode rel = parseAndConvert("SELECT * FROM users");
        assertNotNull(rel);
        assertEquals(RelNode.Op.TABLE_SCAN, rel.getOp());
        assertEquals("users", rel.getTableName());
    }

    @Test
    @DisplayName("SELECT 投影列 → Project(TableScan)")
    void testConvertSelectColumns() {
        RelNode rel = parseAndConvert("SELECT id, name FROM users");
        assertEquals(RelNode.Op.PROJECT, rel.getOp());
        assertEquals(1, rel.getChildren().size());
        assertEquals(RelNode.Op.TABLE_SCAN, rel.getChildren().get(0).getOp());
        assertTrue(rel.getProjects().contains("id"));
        assertTrue(rel.getProjects().contains("name"));
    }

    @Test
    @DisplayName("WHERE 条件 → Filter(TableScan)")
    void testConvertWhere() {
        RelNode rel = parseAndConvert("SELECT * FROM users WHERE age > 18");
        assertEquals(RelNode.Op.FILTER, rel.getOp());
        assertNotNull(rel.getCondition());
        assertTrue(rel.getCondition().contains("age"));
        assertEquals(RelNode.Op.TABLE_SCAN, rel.getChildren().get(0).getOp());
    }

    @Test
    @DisplayName("SELECT + WHERE + 投影 → Project(Filter(TableScan))")
    void testConvertSelectWhereProject() {
        RelNode rel = parseAndConvert("SELECT id, name FROM users WHERE age > 18");
        assertEquals(RelNode.Op.PROJECT, rel.getOp());
        assertEquals(RelNode.Op.FILTER, rel.getChildren().get(0).getOp());
        assertEquals(RelNode.Op.TABLE_SCAN, rel.getChildren().get(0).getChildren().get(0).getOp());
    }

    @Test
    @DisplayName("JOIN → Join(Scan, Scan)")
    void testConvertJoin() {
        RelNode rel = parseAndConvert(
                "SELECT * FROM orders o JOIN users u ON o.uid = u.id");
        assertEquals(RelNode.Op.JOIN, rel.getOp());
        assertEquals(2, rel.getChildren().size());
        assertEquals(RelNode.Op.TABLE_SCAN, rel.getChildren().get(0).getOp());
        assertEquals(RelNode.Op.TABLE_SCAN, rel.getChildren().get(1).getOp());
        assertNotNull(rel.getJoinType());
        assertNotNull(rel.getCondition());
    }

    @Test
    @DisplayName("LEFT JOIN → joinType=LEFT")
    void testConvertLeftJoin() {
        RelNode rel = parseAndConvert(
                "SELECT * FROM orders o LEFT JOIN users u ON o.uid = u.id");
        assertEquals(RelNode.Op.JOIN, rel.getOp());
        assertEquals("LEFT", rel.getJoinType());
    }

    @Test
    @DisplayName("GROUP BY → Aggregate")
    void testConvertGroupBy() {
        RelNode rel = parseAndConvert(
                "SELECT dept, COUNT(*) FROM emp GROUP BY dept");
        // SELECT 列非 * → Project(Aggregate(Scan))
        assertEquals(RelNode.Op.PROJECT, rel.getOp());
        RelNode agg = rel.getChildren().get(0);
        assertEquals(RelNode.Op.AGGREGATE, agg.getOp());
        assertTrue(agg.getGroupKeys().contains("dept"));
    }

    @Test
    @DisplayName("ORDER BY → Sort")
    void testConvertOrderBy() {
        RelNode rel = parseAndConvert(
                "SELECT * FROM users ORDER BY id DESC");
        assertEquals(RelNode.Op.SORT, rel.getOp());
        assertFalse(rel.getSortKeys().isEmpty());
    }

    @Test
    @DisplayName("LIMIT → Limit")
    void testConvertLimit() {
        RelNode rel = parseAndConvert("SELECT * FROM users LIMIT 10");
        assertEquals(RelNode.Op.LIMIT, rel.getOp());
        assertEquals(10, rel.getLimit());
    }

    @Test
    @DisplayName("完整 SELECT → Limit(Sort(Project(Filter(Scan))))")
    void testConvertFullSelect() {
        RelNode rel = parseAndConvert(
                "SELECT id, name FROM users WHERE age > 18 ORDER BY id DESC LIMIT 10");
        assertEquals(RelNode.Op.LIMIT, rel.getOp());
        assertEquals(RelNode.Op.SORT, rel.getChildren().get(0).getOp());
        assertEquals(RelNode.Op.PROJECT, rel.getChildren().get(0).getChildren().get(0).getOp());
    }

    @Test
    @DisplayName("UNION → Union(left, right)")
    void testConvertUnion() {
        RelNode rel = parseAndConvert(
                "SELECT id FROM a UNION SELECT id FROM b");
        assertEquals(RelNode.Op.UNION, rel.getOp());
        assertEquals(2, rel.getChildren().size());
    }

    @Test
    @DisplayName("子查询 → Subquery")
    void testConvertSubquery() {
        RelNode rel = parseAndConvert(
                "SELECT * FROM (SELECT id, name FROM users) t");
        // 外层 SELECT * → 直接返回子查询内容
        assertTrue(rel.getOp() == RelNode.Op.SUBQUERY
                || rel.getOp() == RelNode.Op.PROJECT);
    }

    @Test
    @DisplayName("聚合函数提取 → aggFuncs 非空")
    void testConvertAggregateFunction() {
        RelNode rel = parseAndConvert(
                "SELECT dept, SUM(salary), COUNT(*) FROM emp GROUP BY dept");
        // Project(Aggregate(Scan))
        assertEquals(RelNode.Op.PROJECT, rel.getOp());
        RelNode agg = rel.getChildren().get(0);
        assertEquals(RelNode.Op.AGGREGATE, agg.getOp());
        assertFalse(agg.getAggFuncs().isEmpty());
    }

    @Test
    @DisplayName("多表 JOIN 链 → Join(Join(a, b), c)")
    void testConvertMultiJoin() {
        RelNode rel = parseAndConvert(
                "SELECT * FROM a JOIN b ON a.id = b.aid JOIN c ON b.id = c.bid");
        assertEquals(RelNode.Op.JOIN, rel.getOp());
        // 左子树也是 Join
        assertEquals(RelNode.Op.JOIN, rel.getChildren().get(0).getOp());
    }

    @Test
    @DisplayName("collectTables 收集所有表名")
    void testCollectTables() {
        RelNode rel = parseAndConvert(
                "SELECT * FROM a JOIN b ON a.id = b.id");
        java.util.List<String> tables = rel.collectTables();
        assertEquals(2, tables.size());
        assertTrue(tables.contains("a"));
        assertTrue(tables.contains("b"));
    }

    @Test
    @DisplayName("空 AST 抛异常")
    void testConvertNullAst() {
        assertThrows(IllegalArgumentException.class, () -> converter.convert(null));
    }

    @Test
    @DisplayName("SELECT 无 FROM → VALUES")
    void testConvertSelectNoFrom() {
        RelNode rel = parseAndConvert("SELECT 1");
        // SELECT 1 → Project(VALUES) 或直接 VALUES
        assertNotNull(rel);
    }

    @Test
    @DisplayName("表别名保留 → tableAlias")
    void testConvertTableAlias() {
        RelNode rel = parseAndConvert("SELECT * FROM users u");
        assertEquals(RelNode.Op.TABLE_SCAN, rel.getOp());
        assertEquals("u", rel.getTableAlias());
    }

    @Test
    @DisplayName("depth 返回树深度")
    void testNodeDepth() {
        RelNode rel = parseAndConvert(
                "SELECT id FROM users WHERE age > 18");
        assertTrue(rel.depth() >= 2);
    }
}