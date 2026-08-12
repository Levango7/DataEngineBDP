package com.levango7.dataenginebdp.sqlgateway.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SqlParserService} 单元测试。
 *
 * <p>覆盖 SELECT、JOIN、WHERE、GROUP BY、HAVING、ORDER BY、LIMIT、UNION、
 * 子查询、INSERT、CREATE TABLE、DROP、ALTER、方言检测、表名/列名提取、异常处理
 * 等 100+ 用例。</p>
 *
 * @author shuqing-bigdata
 */
@DisplayName("SqlParserService 解析器测试")
class SqlParserServiceTest {

    private final SqlParserService parser = new SqlParserService();

    // ===================== 1. 基本 SELECT =====================

    @Nested
    @DisplayName("基本 SELECT 解析")
    class BasicSelectTest {

        @Test
        @DisplayName("SELECT *")
        void selectStar() {
            ASTNode ast = parser.parse("SELECT *", SqlDialect.ANSI);
            assertEquals(ASTNode.NodeType.STATEMENT, ast.getType());
            ASTNode select = ast.findChild(ASTNode.NodeType.SELECT);
            assertNotNull(select);
            assertTrue(select.getStringList("columns").contains("*"));
        }

        @Test
        @DisplayName("SELECT * FROM t1")
        void selectStarFromTable() {
            ASTNode ast = parser.parse("SELECT * FROM t1", SqlDialect.ANSI);
            List<String> tables = ast.extractTables();
            assertTrue(tables.contains("t1"));
        }

        @Test
        @DisplayName("SELECT a, b FROM t1")
        void selectColumnsFromTable() {
            ASTNode ast = parser.parse("SELECT a, b FROM t1", SqlDialect.ANSI);
            List<String> cols = ast.extractColumns();
            assertTrue(cols.contains("a"));
            assertTrue(cols.contains("b"));
            assertTrue(ast.extractTables().contains("t1"));
        }

        @Test
        @DisplayName("SELECT a AS x FROM t1")
        void selectColumnWithAlias() {
            ASTNode ast = parser.parse("SELECT a AS x FROM t1", SqlDialect.ANSI);
            List<String> cols = ast.extractColumns();
            assertTrue(cols.contains("a"));
        }

        @Test
        @DisplayName("SELECT DISTINCT a FROM t1")
        void selectDistinct() {
            ASTNode ast = parser.parse("SELECT DISTINCT a FROM t1", SqlDialect.ANSI);
            ASTNode select = ast.findChild(ASTNode.NodeType.SELECT);
            assertEquals(true, select.getProperties().get("distinct"));
        }

        @Test
        @DisplayName("SELECT 1")
        void selectLiteral() {
            ASTNode ast = parser.parse("SELECT 1", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT));
        }

        @Test
        @DisplayName("SELECT 'hello'")
        void selectStringLiteral() {
            ASTNode ast = parser.parse("SELECT 'hello'", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT));
        }

        @Test
        @DisplayName("SELECT a, b, c, d FROM t1")
        void selectMultipleColumns() {
            ASTNode ast = parser.parse("SELECT a, b, c, d FROM t1", SqlDialect.ANSI);
            List<String> cols = ast.extractColumns();
            assertTrue(cols.containsAll(List.of("a", "b", "c", "d")));
        }

        @Test
        @DisplayName("SELECT t1.a, t1.b FROM t1")
        void selectQualifiedColumns() {
            ASTNode ast = parser.parse("SELECT t1.a, t1.b FROM t1", SqlDialect.ANSI);
            List<String> cols = ast.extractColumns();
            assertFalse(cols.isEmpty());
        }

        @Test
        @DisplayName("SELECT COUNT(*) FROM t1")
        void selectCountStar() {
            ASTNode ast = parser.parse("SELECT COUNT(*) FROM t1", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT));
            assertTrue(ast.extractTables().contains("t1"));
        }

        @Test
        @DisplayName("SELECT a + b FROM t1")
        void selectArithmeticExpression() {
            ASTNode ast = parser.parse("SELECT a + b FROM t1", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT));
        }

        @Test
        @DisplayName("SELECT a FROM t1; (带分号)")
        void selectWithSemicolon() {
            ASTNode ast = parser.parse("SELECT a FROM t1;", SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("t1"));
        }
    }

    // ===================== 2. JOIN =====================

    @Nested
    @DisplayName("JOIN 解析")
    class JoinTest {

        @Test
        @DisplayName("INNER JOIN")
        void innerJoin() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 INNER JOIN t2 ON t1.id = t2.id", SqlDialect.ANSI);
            List<String> tables = ast.extractTables();
            assertTrue(tables.contains("t1"));
            assertTrue(tables.contains("t2"));
            ASTNode from = ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.FROM);
            assertNotNull(from.findChild(ASTNode.NodeType.JOIN));
        }

        @Test
        @DisplayName("默认 JOIN 等价于 INNER JOIN")
        void defaultJoin() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 JOIN t2 ON t1.id = t2.id", SqlDialect.ANSI);
            List<String> tables = ast.extractTables();
            assertTrue(tables.containsAll(List.of("t1", "t2")));
        }

        @Test
        @DisplayName("LEFT JOIN")
        void leftJoin() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 LEFT JOIN t2 ON t1.id = t2.id", SqlDialect.ANSI);
            ASTNode join = ast.findChild(ASTNode.NodeType.SELECT)
                    .findChild(ASTNode.NodeType.FROM).findChild(ASTNode.NodeType.JOIN);
            assertEquals("LEFT", join.getString("joinType"));
        }

        @Test
        @DisplayName("LEFT OUTER JOIN")
        void leftOuterJoin() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 LEFT OUTER JOIN t2 ON t1.id = t2.id", SqlDialect.ANSI);
            ASTNode join = ast.findChild(ASTNode.NodeType.SELECT)
                    .findChild(ASTNode.NodeType.FROM).findChild(ASTNode.NodeType.JOIN);
            assertEquals("LEFT", join.getString("joinType"));
        }

        @Test
        @DisplayName("RIGHT JOIN")
        void rightJoin() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 RIGHT JOIN t2 ON t1.id = t2.id", SqlDialect.ANSI);
            ASTNode join = ast.findChild(ASTNode.NodeType.SELECT)
                    .findChild(ASTNode.NodeType.FROM).findChild(ASTNode.NodeType.JOIN);
            assertEquals("RIGHT", join.getString("joinType"));
        }

        @Test
        @DisplayName("RIGHT OUTER JOIN")
        void rightOuterJoin() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 RIGHT OUTER JOIN t2 ON t1.id = t2.id", SqlDialect.ANSI);
            ASTNode join = ast.findChild(ASTNode.NodeType.SELECT)
                    .findChild(ASTNode.NodeType.FROM).findChild(ASTNode.NodeType.JOIN);
            assertEquals("RIGHT", join.getString("joinType"));
        }

        @Test
        @DisplayName("FULL OUTER JOIN")
        void fullOuterJoin() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 FULL OUTER JOIN t2 ON t1.id = t2.id", SqlDialect.ANSI);
            ASTNode join = ast.findChild(ASTNode.NodeType.SELECT)
                    .findChild(ASTNode.NodeType.FROM).findChild(ASTNode.NodeType.JOIN);
            assertEquals("FULL", join.getString("joinType"));
        }

        @Test
        @DisplayName("CROSS JOIN")
        void crossJoin() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 CROSS JOIN t2 ON t1.id = t2.id", SqlDialect.ANSI);
            ASTNode join = ast.findChild(ASTNode.NodeType.SELECT)
                    .findChild(ASTNode.NodeType.FROM).findChild(ASTNode.NodeType.JOIN);
            assertEquals("CROSS", join.getString("joinType"));
        }

        @Test
        @DisplayName("三表 JOIN")
        void threeTableJoin() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 JOIN t2 ON t1.id = t2.id JOIN t3 ON t2.id = t3.id",
                    SqlDialect.ANSI);
            List<String> tables = ast.extractTables();
            assertTrue(tables.containsAll(List.of("t1", "t2", "t3")));
        }

        @Test
        @DisplayName("JOIN 带表别名")
        void joinWithAlias() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 a JOIN t2 b ON a.id = b.id", SqlDialect.ANSI);
            List<String> tables = ast.extractTables();
            assertTrue(tables.contains("t1"));
            assertTrue(tables.contains("t2"));
        }

        @Test
        @DisplayName("JOIN 带 AS 别名")
        void joinWithAsAlias() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 AS a JOIN t2 AS b ON a.id = b.id", SqlDialect.ANSI);
            List<String> tables = ast.extractTables();
            assertTrue(tables.containsAll(List.of("t1", "t2")));
        }

        @Test
        @DisplayName("多表 JOIN 混合类型")
        void mixedJoinTypes() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 INNER JOIN t2 ON t1.id = t2.id "
                            + "LEFT JOIN t3 ON t2.id = t3.id",
                    SqlDialect.ANSI);
            List<String> tables = ast.extractTables();
            assertEquals(3, tables.size());
        }

        @Test
        @DisplayName("JOIN ON 复合条件")
        void joinOnComplexCondition() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 JOIN t2 ON t1.id = t2.id AND t1.code = t2.code",
                    SqlDialect.ANSI);
            ASTNode join = ast.findChild(ASTNode.NodeType.SELECT)
                    .findChild(ASTNode.NodeType.FROM).findChild(ASTNode.NodeType.JOIN);
            assertNotNull(join.getString("on"));
        }

        @Test
        @DisplayName("JOIN 提取 ON 条件中的列")
        void joinExtractOnColumns() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 JOIN t2 ON t1.id = t2.id", SqlDialect.ANSI);
            List<String> cols = ast.extractColumns();
            assertFalse(cols.isEmpty());
        }

        @Test
        @DisplayName("四表 JOIN")
        void fourTableJoin() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 JOIN t2 ON t1.id = t2.id "
                            + "JOIN t3 ON t2.id = t3.id JOIN t4 ON t3.id = t4.id",
                    SqlDialect.ANSI);
            assertEquals(4, ast.extractTables().size());
        }
    }

    // ===================== 3. WHERE 条件 =====================

    @Nested
    @DisplayName("WHERE 条件解析")
    class WhereTest {

        @Test
        @DisplayName("简单等值条件")
        void simpleEqual() {
            ASTNode ast = parser.parse("SELECT * FROM t1 WHERE a = 1", SqlDialect.ANSI);
            ASTNode where = ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE);
            assertNotNull(where);
            assertEquals("a = 1", where.getString("condition"));
        }

        @Test
        @DisplayName("AND 条件")
        void andCondition() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 WHERE a = 1 AND b = 2", SqlDialect.ANSI);
            ASTNode where = ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE);
            assertNotNull(where.getString("condition"));
        }

        @Test
        @DisplayName("OR 条件")
        void orCondition() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 WHERE a = 1 OR b = 2", SqlDialect.ANSI);
            ASTNode where = ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE);
            assertNotNull(where.getString("condition"));
        }

        @Test
        @DisplayName("AND + OR 混合")
        void andOrMixed() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 WHERE a = 1 AND b = 2 OR c = 3", SqlDialect.ANSI);
            ASTNode where = ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE);
            assertNotNull(where.getString("condition"));
        }

        @Test
        @DisplayName("IN 条件")
        void inCondition() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 WHERE a IN (1, 2, 3)", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("BETWEEN 条件")
        void betweenCondition() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 WHERE a BETWEEN 1 AND 100", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("LIKE 条件")
        void likeCondition() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 WHERE a LIKE 'abc%'", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("NOT 条件")
        void notCondition() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 WHERE NOT a = 1", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("比较运算符 >")
        void greaterThan() {
            ASTNode ast = parser.parse("SELECT * FROM t1 WHERE a > 10", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("比较运算符 >=")
        void greaterOrEqual() {
            ASTNode ast = parser.parse("SELECT * FROM t1 WHERE a >= 10", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("比较运算符 <")
        void lessThan() {
            ASTNode ast = parser.parse("SELECT * FROM t1 WHERE a < 10", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("比较运算符 <=")
        void lessOrEqual() {
            ASTNode ast = parser.parse("SELECT * FROM t1 WHERE a <= 10", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("不等于 !=")
        void notEqual() {
            ASTNode ast = parser.parse("SELECT * FROM t1 WHERE a != 10", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("不等于 <>")
        void notEqualAlt() {
            ASTNode ast = parser.parse("SELECT * FROM t1 WHERE a <> 10", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("IS NULL（简化处理为表达式）")
        void isNull() {
            ASTNode ast = parser.parse("SELECT * FROM t1 WHERE a IS NULL", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("括号分组条件")
        void parenGroupedCondition() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 WHERE (a = 1 OR b = 2) AND c = 3", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }
    }

    // ===================== 4. GROUP BY + HAVING =====================

    @Nested
    @DisplayName("GROUP BY + HAVING 解析")
    class GroupByHavingTest {

        @Test
        @DisplayName("GROUP BY 单列")
        void groupBySingleColumn() {
            ASTNode ast = parser.parse("SELECT a, COUNT(*) FROM t1 GROUP BY a", SqlDialect.ANSI);
            ASTNode group = ast.findChild(ASTNode.NodeType.SELECT)
                    .findChild(ASTNode.NodeType.GROUP_BY);
            assertNotNull(group);
            assertTrue(group.getStringList("columns").contains("a"));
        }

        @Test
        @DisplayName("GROUP BY 多列")
        void groupByMultipleColumns() {
            ASTNode ast = parser.parse(
                    "SELECT a, b, COUNT(*) FROM t1 GROUP BY a, b", SqlDialect.ANSI);
            ASTNode group = ast.findChild(ASTNode.NodeType.SELECT)
                    .findChild(ASTNode.NodeType.GROUP_BY);
            List<String> cols = group.getStringList("columns");
            assertTrue(cols.contains("a"));
            assertTrue(cols.contains("b"));
        }

        @Test
        @DisplayName("GROUP BY + HAVING")
        void groupByHaving() {
            ASTNode ast = parser.parse(
                    "SELECT a, COUNT(*) FROM t1 GROUP BY a HAVING COUNT(*) > 1",
                    SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.HAVING));
        }

        @Test
        @DisplayName("GROUP BY 表达式")
        void groupByExpression() {
            ASTNode ast = parser.parse(
                    "SELECT a + b FROM t1 GROUP BY a + b", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.GROUP_BY));
        }

        @Test
        @DisplayName("GROUP BY 函数")
        void groupByFunction() {
            ASTNode ast = parser.parse(
                    "SELECT date_format(t, 'yyyy') FROM t1 GROUP BY date_format(t, 'yyyy')",
                    SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.GROUP_BY));
        }

        @Test
        @DisplayName("HAVING 简单条件")
        void havingSimple() {
            ASTNode ast = parser.parse(
                    "SELECT a, SUM(b) FROM t1 GROUP BY a HAVING SUM(b) > 100",
                    SqlDialect.ANSI);
            ASTNode having = ast.findChild(ASTNode.NodeType.SELECT)
                    .findChild(ASTNode.NodeType.HAVING);
            assertNotNull(having.getString("condition"));
        }

        @Test
        @DisplayName("HAVING 复合条件")
        void havingComplex() {
            ASTNode ast = parser.parse(
                    "SELECT a, SUM(b) FROM t1 GROUP BY a HAVING SUM(b) > 100 AND COUNT(*) > 5",
                    SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.HAVING));
        }

        @Test
        @DisplayName("GROUP BY 限定列名提取")
        void groupByExtractColumns() {
            ASTNode ast = parser.parse(
                    "SELECT a, b FROM t1 GROUP BY a, b", SqlDialect.ANSI);
            List<String> cols = ast.extractColumns();
            assertTrue(cols.contains("a"));
            assertTrue(cols.contains("b"));
        }

        @Test
        @DisplayName("GROUP BY 不影响表名提取")
        void groupByDoesNotAffectTables() {
            ASTNode ast = parser.parse("SELECT a FROM t1 GROUP BY a", SqlDialect.ANSI);
            assertEquals(List.of("t1"), ast.extractTables());
        }

        @Test
        @DisplayName("GROUP BY + HAVING + ORDER BY 组合")
        void groupByHavingOrderBy() {
            ASTNode ast = parser.parse(
                    "SELECT a, COUNT(*) AS cnt FROM t1 GROUP BY a HAVING COUNT(*) > 1 ORDER BY cnt DESC",
                    SqlDialect.ANSI);
            ASTNode select = ast.findChild(ASTNode.NodeType.SELECT);
            assertNotNull(select.findChild(ASTNode.NodeType.GROUP_BY));
            assertNotNull(select.findChild(ASTNode.NodeType.HAVING));
            assertNotNull(select.findChild(ASTNode.NodeType.ORDER_BY));
        }
    }

    // ===================== 5. ORDER BY + LIMIT =====================

    @Nested
    @DisplayName("ORDER BY + LIMIT 解析")
    class OrderByLimitTest {

        @Test
        @DisplayName("ORDER BY 单列 ASC")
        void orderByAsc() {
            ASTNode ast = parser.parse("SELECT * FROM t1 ORDER BY a ASC", SqlDialect.ANSI);
            ASTNode order = ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.ORDER_BY);
            assertNotNull(order);
        }

        @Test
        @DisplayName("ORDER BY 单列 DESC")
        void orderByDesc() {
            ASTNode ast = parser.parse("SELECT * FROM t1 ORDER BY a DESC", SqlDialect.ANSI);
            ASTNode order = ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.ORDER_BY);
            assertNotNull(order);
        }

        @Test
        @DisplayName("ORDER BY 多列")
        void orderByMultiple() {
            ASTNode ast = parser.parse("SELECT * FROM t1 ORDER BY a ASC, b DESC", SqlDialect.ANSI);
            ASTNode order = ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.ORDER_BY);
            assertEquals(2, order.getChildren().size());
        }

        @Test
        @DisplayName("ORDER BY 默认 ASC")
        void orderByDefaultDirection() {
            ASTNode ast = parser.parse("SELECT * FROM t1 ORDER BY a", SqlDialect.ANSI);
            ASTNode order = ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.ORDER_BY);
            assertNotNull(order);
        }

        @Test
        @DisplayName("LIMIT 单参数")
        void limitSingle() {
            ASTNode ast = parser.parse("SELECT * FROM t1 LIMIT 10", SqlDialect.ANSI);
            ASTNode limit = ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.LIMIT);
            assertEquals(10L, limit.getProperties().get("count"));
        }

        @Test
        @DisplayName("LIMIT offset, count")
        void limitOffsetCount() {
            ASTNode ast = parser.parse("SELECT * FROM t1 LIMIT 5, 10", SqlDialect.ANSI);
            ASTNode limit = ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.LIMIT);
            assertEquals(10L, limit.getProperties().get("count"));
            assertEquals(5L, limit.getProperties().get("offset"));
        }

        @Test
        @DisplayName("ORDER BY + LIMIT 组合")
        void orderByLimit() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 ORDER BY a DESC LIMIT 10", SqlDialect.ANSI);
            ASTNode select = ast.findChild(ASTNode.NodeType.SELECT);
            assertNotNull(select.findChild(ASTNode.NodeType.ORDER_BY));
            assertNotNull(select.findChild(ASTNode.NodeType.LIMIT));
        }

        @Test
        @DisplayName("ORDER BY 函数")
        void orderByFunction() {
            ASTNode ast = parser.parse(
                    "SELECT a FROM t1 ORDER BY COUNT(*) DESC", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.ORDER_BY));
        }

        @Test
        @DisplayName("LIMIT 0")
        void limitZero() {
            ASTNode ast = parser.parse("SELECT * FROM t1 LIMIT 0", SqlDialect.ANSI);
            ASTNode limit = ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.LIMIT);
            assertEquals(0L, limit.getProperties().get("count"));
        }

        @Test
        @DisplayName("完整 SELECT 各子句组合")
        void fullSelectClauses() {
            ASTNode ast = parser.parse(
                    "SELECT a, COUNT(*) AS cnt FROM t1 WHERE b > 0 GROUP BY a HAVING COUNT(*) > 1 "
                            + "ORDER BY cnt DESC LIMIT 10",
                    SqlDialect.ANSI);
            ASTNode select = ast.findChild(ASTNode.NodeType.SELECT);
            assertNotNull(select.findChild(ASTNode.NodeType.FROM));
            assertNotNull(select.findChild(ASTNode.NodeType.WHERE));
            assertNotNull(select.findChild(ASTNode.NodeType.GROUP_BY));
            assertNotNull(select.findChild(ASTNode.NodeType.HAVING));
            assertNotNull(select.findChild(ASTNode.NodeType.ORDER_BY));
            assertNotNull(select.findChild(ASTNode.NodeType.LIMIT));
        }
    }

    // ===================== 6. UNION =====================

    @Nested
    @DisplayName("UNION 解析")
    class UnionTest {

        @Test
        @DisplayName("UNION")
        void union() {
            ASTNode ast = parser.parse(
                    "SELECT a FROM t1 UNION SELECT a FROM t2", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.UNION));
        }

        @Test
        @DisplayName("UNION ALL")
        void unionAll() {
            ASTNode ast = parser.parse(
                    "SELECT a FROM t1 UNION ALL SELECT a FROM t2", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.UNION));
        }

        @Test
        @DisplayName("UNION 提取多表")
        void unionExtractTables() {
            ASTNode ast = parser.parse(
                    "SELECT a FROM t1 UNION SELECT a FROM t2", SqlDialect.ANSI);
            List<String> tables = ast.extractTables();
            assertTrue(tables.contains("t1"));
            assertTrue(tables.contains("t2"));
        }

        @Test
        @DisplayName("UNION 三查询")
        void unionThree() {
            ASTNode ast = parser.parse(
                    "SELECT a FROM t1 UNION SELECT a FROM t2 UNION SELECT a FROM t3",
                    SqlDialect.ANSI);
            List<String> tables = ast.extractTables();
            assertTrue(tables.containsAll(List.of("t1", "t2", "t3")));
        }

        @Test
        @DisplayName("UNION ALL 嵌套")
        void unionAllNested() {
            ASTNode ast = parser.parse(
                    "SELECT a FROM t1 UNION ALL SELECT a FROM t2 UNION ALL SELECT a FROM t3",
                    SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.UNION));
        }
    }

    // ===================== 7. 子查询 =====================

    @Nested
    @DisplayName("子查询解析")
    class SubqueryTest {

        @Test
        @DisplayName("FROM 子查询")
        void fromSubquery() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM (SELECT * FROM t1) sub", SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("t1"));
        }

        @Test
        @DisplayName("FROM 子查询带 AS")
        void fromSubqueryWithAs() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM (SELECT a FROM t1) AS sub", SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("t1"));
        }

        @Test
        @DisplayName("WHERE 子查询 (IN)")
        void whereInSubquery() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 WHERE id IN (SELECT id FROM t2)", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("嵌套子查询")
        void nestedSubquery() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM (SELECT * FROM (SELECT * FROM t1) inner) outer",
                    SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("t1"));
        }

        @Test
        @DisplayName("子查询带 JOIN")
        void subqueryWithJoin() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM (SELECT a FROM t1 JOIN t2 ON t1.id = t2.id) sub",
                    SqlDialect.ANSI);
            List<String> tables = ast.extractTables();
            assertTrue(tables.contains("t1"));
            assertTrue(tables.contains("t2"));
        }

        @Test
        @DisplayName("子查询带 WHERE")
        void subqueryWithWhere() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM (SELECT a FROM t1 WHERE b > 0) sub", SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("t1"));
        }

        @Test
        @DisplayName("子查询带 GROUP BY")
        void subqueryWithGroupBy() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM (SELECT a, COUNT(*) FROM t1 GROUP BY a) sub", SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("t1"));
        }

        @Test
        @DisplayName("子查询带 LIMIT")
        void subqueryWithLimit() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM (SELECT a FROM t1 LIMIT 10) sub", SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("t1"));
        }

        @Test
        @DisplayName("子查询 UNION")
        void subqueryUnion() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM (SELECT a FROM t1 UNION SELECT a FROM t2) sub",
                    SqlDialect.ANSI);
            List<String> tables = ast.extractTables();
            assertTrue(tables.contains("t1"));
            assertTrue(tables.contains("t2"));
        }

        @Test
        @DisplayName("WHERE EXISTS 子查询（简化）")
        void whereExistsSubquery() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 WHERE id IN (SELECT id FROM t2 WHERE b > 0)",
                    SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }
    }

    // ===================== 8. INSERT =====================

    @Nested
    @DisplayName("INSERT 解析")
    class InsertTest {

        @Test
        @DisplayName("INSERT INTO ... VALUES")
        void insertValues() {
            ASTNode ast = parser.parse(
                    "INSERT INTO t1 VALUES (1, 'a', 2.0)", SqlDialect.ANSI);
            ASTNode insert = ast.findChild(ASTNode.NodeType.INSERT);
            assertNotNull(insert);
            assertEquals("t1", insert.getString("table"));
            assertEquals("VALUES", insert.getString("mode"));
        }

        @Test
        @DisplayName("INSERT INTO ... VALUES 多行")
        void insertValuesMultipleRows() {
            ASTNode ast = parser.parse(
                    "INSERT INTO t1 VALUES (1, 'a'), (2, 'b'), (3, 'c')", SqlDialect.ANSI);
            ASTNode insert = ast.findChild(ASTNode.NodeType.INSERT);
            assertNotNull(insert);
            assertEquals("VALUES", insert.getString("mode"));
        }

        @Test
        @DisplayName("INSERT INTO ... SELECT")
        void insertSelect() {
            ASTNode ast = parser.parse(
                    "INSERT INTO t1 SELECT a, b FROM t2", SqlDialect.ANSI);
            ASTNode insert = ast.findChild(ASTNode.NodeType.INSERT);
            assertEquals("SELECT", insert.getString("mode"));
            List<String> tables = ast.extractTables();
            assertTrue(tables.contains("t1"));
            assertTrue(tables.contains("t2"));
        }

        @Test
        @DisplayName("INSERT INTO 指定列")
        void insertWithColumns() {
            ASTNode ast = parser.parse(
                    "INSERT INTO t1 (a, b) VALUES (1, 2)", SqlDialect.ANSI);
            ASTNode insert = ast.findChild(ASTNode.NodeType.INSERT);
            List<String> cols = insert.getStringList("columns");
            assertTrue(cols.contains("a"));
            assertTrue(cols.contains("b"));
        }

        @Test
        @DisplayName("INSERT INTO 指定列 + SELECT")
        void insertWithColumnsSelect() {
            ASTNode ast = parser.parse(
                    "INSERT INTO t1 (a, b) SELECT x, y FROM t2", SqlDialect.ANSI);
            ASTNode insert = ast.findChild(ASTNode.NodeType.INSERT);
            assertEquals("SELECT", insert.getString("mode"));
        }

        @Test
        @DisplayName("INSERT OVERWRITE (Hive)")
        void insertOverwrite() {
            ASTNode ast = parser.parse(
                    "INSERT OVERWRITE TABLE t1 SELECT a FROM t2", SqlDialect.HIVE);
            ASTNode insert = ast.findChild(ASTNode.NodeType.INSERT);
            assertEquals(true, insert.getProperties().get("overwrite"));
        }

        @Test
        @DisplayName("INSERT 带数据库前缀表名")
        void insertWithDbPrefix() {
            ASTNode ast = parser.parse(
                    "INSERT INTO db.t1 VALUES (1)", SqlDialect.ANSI);
            ASTNode insert = ast.findChild(ASTNode.NodeType.INSERT);
            assertEquals("db.t1", insert.getString("table"));
        }

        @Test
        @DisplayName("INSERT SELECT 带 WHERE")
        void insertSelectWithWhere() {
            ASTNode ast = parser.parse(
                    "INSERT INTO t1 SELECT a FROM t2 WHERE b > 0", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.INSERT));
        }

        @Test
        @DisplayName("INSERT SELECT 带 JOIN")
        void insertSelectWithJoin() {
            ASTNode ast = parser.parse(
                    "INSERT INTO t1 SELECT a FROM t2 JOIN t3 ON t2.id = t3.id",
                    SqlDialect.ANSI);
            List<String> tables = ast.extractTables();
            assertTrue(tables.containsAll(List.of("t1", "t2", "t3")));
        }

        @Test
        @DisplayName("INSERT VALUES 提取目标表")
        void insertValuesExtractTable() {
            ASTNode ast = parser.parse(
                    "INSERT INTO target VALUES (1, 'a')", SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("target"));
        }
    }

    // ===================== 9. CREATE TABLE =====================

    @Nested
    @DisplayName("CREATE TABLE 解析")
    class CreateTableTest {

        @Test
        @DisplayName("基本 CREATE TABLE")
        void createTable() {
            ASTNode ast = parser.parse(
                    "CREATE TABLE t1 (a INT, b VARCHAR(255))", SqlDialect.ANSI);
            ASTNode create = ast.findChild(ASTNode.NodeType.CREATE_TABLE);
            assertNotNull(create);
            assertEquals("t1", create.getString("table"));
        }

        @Test
        @DisplayName("CREATE TABLE IF NOT EXISTS")
        void createTableIfNotExists() {
            ASTNode ast = parser.parse(
                    "CREATE TABLE IF NOT EXISTS t1 (a INT)", SqlDialect.ANSI);
            ASTNode create = ast.findChild(ASTNode.NodeType.CREATE_TABLE);
            assertEquals(true, create.getProperties().get("ifNotExists"));
        }

        @Test
        @DisplayName("CREATE EXTERNAL TABLE (Hive)")
        void createExternalTable() {
            ASTNode ast = parser.parse(
                    "CREATE EXTERNAL TABLE t1 (a INT) STORED AS ORC", SqlDialect.HIVE);
            ASTNode create = ast.findChild(ASTNode.NodeType.CREATE_TABLE);
            assertEquals(true, create.getProperties().get("external"));
            assertEquals(true, create.getProperties().get("stored"));
        }

        @Test
        @DisplayName("CREATE TABLE 多列")
        void createTableMultipleColumns() {
            ASTNode ast = parser.parse(
                    "CREATE TABLE t1 (a INT, b VARCHAR(255), c DECIMAL(10,2))", SqlDialect.ANSI);
            ASTNode create = ast.findChild(ASTNode.NodeType.CREATE_TABLE);
            List<String> cols = create.getStringList("columns");
            assertTrue(cols.containsAll(List.of("a", "b", "c")));
        }

        @Test
        @DisplayName("CREATE TABLE 带数据库前缀")
        void createTableWithDbPrefix() {
            ASTNode ast = parser.parse(
                    "CREATE TABLE db.t1 (a INT)", SqlDialect.ANSI);
            ASTNode create = ast.findChild(ASTNode.NodeType.CREATE_TABLE);
            assertEquals("db.t1", create.getString("table"));
        }

        @Test
        @DisplayName("CREATE TABLE PARTITIONED BY (Hive)")
        void createTablePartitionedBy() {
            ASTNode ast = parser.parse(
                    "CREATE TABLE t1 (a INT) PARTITIONED BY (dt STRING) STORED AS ORC",
                    SqlDialect.HIVE);
            ASTNode create = ast.findChild(ASTNode.NodeType.CREATE_TABLE);
            assertEquals(true, create.getProperties().get("partitioned"));
        }

        @Test
        @DisplayName("CREATE TABLE DISTRIBUTED BY HASH (Doris)")
        void createTableDistributedByHash() {
            ASTNode ast = parser.parse(
                    "CREATE TABLE t1 (a INT, b INT) DISTRIBUTED BY HASH(a) BUCKETS 10 PROPERTIES('replication'='3')",
                    SqlDialect.DORIS);
            ASTNode create = ast.findChild(ASTNode.NodeType.CREATE_TABLE);
            assertEquals(true, create.getProperties().get("distributed"));
            assertEquals(true, create.getProperties().get("properties"));
        }

        @Test
        @DisplayName("CREATE TEMPORARY TABLE")
        void createTemporaryTable() {
            ASTNode ast = parser.parse(
                    "CREATE TEMPORARY TABLE t1 (a INT)", SqlDialect.ANSI);
            ASTNode create = ast.findChild(ASTNode.NodeType.CREATE_TABLE);
            assertEquals(true, create.getProperties().get("temporary"));
        }

        @Test
        @DisplayName("CREATE TABLE 提取表名")
        void createTableExtractTable() {
            ASTNode ast = parser.parse(
                    "CREATE TABLE mytable (a INT, b INT)", SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("mytable"));
        }

        @Test
        @DisplayName("CREATE TABLE 提取列名")
        void createTableExtractColumns() {
            ASTNode ast = parser.parse(
                    "CREATE TABLE t1 (col_a INT, col_b INT)", SqlDialect.ANSI);
            List<String> cols = ast.extractColumns();
            assertTrue(cols.contains("col_a"));
            assertTrue(cols.contains("col_b"));
        }
    }

    // ===================== 10. DROP / ALTER =====================

    @Nested
    @DisplayName("DROP / ALTER 解析")
    class DropAlterTest {

        @Test
        @DisplayName("DROP TABLE")
        void dropTable() {
            ASTNode ast = parser.parse("DROP TABLE t1", SqlDialect.ANSI);
            ASTNode drop = ast.findChild(ASTNode.NodeType.DROP);
            assertEquals("t1", drop.getString("table"));
        }

        @Test
        @DisplayName("DROP TABLE IF EXISTS")
        void dropTableIfExists() {
            ASTNode ast = parser.parse("DROP TABLE IF EXISTS t1", SqlDialect.ANSI);
            ASTNode drop = ast.findChild(ASTNode.NodeType.DROP);
            assertEquals(true, drop.getProperties().get("ifExists"));
        }

        @Test
        @DisplayName("DROP TABLE 带数据库前缀")
        void dropTableWithDbPrefix() {
            ASTNode ast = parser.parse("DROP TABLE db.t1", SqlDialect.ANSI);
            assertEquals("db.t1", ast.findChild(ASTNode.NodeType.DROP).getString("table"));
        }

        @Test
        @DisplayName("ALTER TABLE ADD COLUMN")
        void alterAddColumn() {
            ASTNode ast = parser.parse(
                    "ALTER TABLE t1 ADD COLUMN c INT", SqlDialect.ANSI);
            ASTNode alter = ast.findChild(ASTNode.NodeType.ALTER);
            assertEquals("t1", alter.getString("table"));
            assertEquals("ADD", alter.getString("action"));
        }

        @Test
        @DisplayName("ALTER TABLE DROP COLUMN")
        void alterDropColumn() {
            ASTNode ast = parser.parse(
                    "ALTER TABLE t1 DROP COLUMN c", SqlDialect.ANSI);
            ASTNode alter = ast.findChild(ASTNode.NodeType.ALTER);
            assertEquals("DROP", alter.getString("action"));
        }

        @Test
        @DisplayName("ALTER TABLE RENAME TO")
        void alterRenameTo() {
            ASTNode ast = parser.parse(
                    "ALTER TABLE t1 RENAME TO t2", SqlDialect.ANSI);
            ASTNode alter = ast.findChild(ASTNode.NodeType.ALTER);
            assertEquals("RENAME", alter.getString("action"));
        }

        @Test
        @DisplayName("DROP TABLE 提取表名")
        void dropTableExtractTable() {
            ASTNode ast = parser.parse("DROP TABLE mytable", SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("mytable"));
        }

        @Test
        @DisplayName("ALTER TABLE 提取表名")
        void alterTableExtractTable() {
            ASTNode ast = parser.parse(
                    "ALTER TABLE mytable ADD COLUMN c INT", SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("mytable"));
        }
    }

    // ===================== 11. 方言检测 =====================

    @Nested
    @DisplayName("方言检测")
    class DialectDetectionTest {

        @Test
        @DisplayName("检测 ANSI SQL")
        void detectAnsi() {
            assertEquals(SqlDialect.ANSI, parser.detectDialect("SELECT * FROM t1"));
        }

        @Test
        @DisplayName("检测 Hive (STORED AS)")
        void detectHive() {
            assertEquals(SqlDialect.HIVE,
                    parser.detectDialect("CREATE TABLE t1 (a INT) STORED AS ORC"));
        }

        @Test
        @DisplayName("检测 Hive (PARTITIONED BY)")
        void detectHiveByPartitioned() {
            assertEquals(SqlDialect.HIVE,
                    parser.detectDialect("CREATE TABLE t1 (a INT) PARTITIONED BY (dt STRING)"));
        }

        @Test
        @DisplayName("检测 Doris (DISTRIBUTED BY)")
        void detectDoris() {
            assertEquals(SqlDialect.DORIS,
                    parser.detectDialect("CREATE TABLE t1 (a INT) DISTRIBUTED BY HASH(a) BUCKETS 10"));
        }

        @Test
        @DisplayName("检测 Doris (PROPERTIES)")
        void detectDorisByProperties() {
            assertEquals(SqlDialect.DORIS,
                    parser.detectDialect("CREATE TABLE t1 (a INT) PROPERTIES('k'='v')"));
        }

        @Test
        @DisplayName("检测 Trino (WITH CTE)")
        void detectTrinoByWith() {
            assertEquals(SqlDialect.TRINO,
                    parser.detectDialect("WITH cte AS (SELECT a FROM t1) SELECT * FROM cte"));
        }

        @Test
        @DisplayName("检测 Trino (CROSS JOIN)")
        void detectTrinoByCrossJoin() {
            assertEquals(SqlDialect.TRINO,
                    parser.detectDialect("SELECT * FROM t1 CROSS JOIN t2"));
        }

        @Test
        @DisplayName("parseAuto 自动检测 Hive")
        void parseAutoHive() {
            ASTNode ast = parser.parseAuto(
                    "CREATE TABLE t1 (a INT) STORED AS ORC");
            assertNotNull(ast.findChild(ASTNode.NodeType.CREATE_TABLE));
        }

        @Test
        @DisplayName("parseAuto 自动检测 Doris")
        void parseAutoDoris() {
            ASTNode ast = parser.parseAuto(
                    "CREATE TABLE t1 (a INT) DISTRIBUTED BY HASH(a) BUCKETS 10");
            assertNotNull(ast.findChild(ASTNode.NodeType.CREATE_TABLE));
        }

        @Test
        @DisplayName("空 SQL 检测为 ANSI")
        void detectEmpty() {
            assertEquals(SqlDialect.ANSI, parser.detectDialect(""));
        }
    }

    // ===================== 12. 表名/列名提取 =====================

    @Nested
    @DisplayName("表名/列名提取 API")
    class ExtractApiTest {

        @Test
        @DisplayName("extractTables 单表")
        void extractTablesSingle() {
            List<String> tables = parser.extractTables("SELECT * FROM t1");
            assertEquals(List.of("t1"), tables);
        }

        @Test
        @DisplayName("extractTables 多表 JOIN")
        void extractTablesJoin() {
            List<String> tables = parser.extractTables(
                    "SELECT * FROM t1 JOIN t2 ON t1.id = t2.id");
            assertTrue(tables.contains("t1"));
            assertTrue(tables.contains("t2"));
        }

        @Test
        @DisplayName("extractTables INSERT SELECT")
        void extractTablesInsertSelect() {
            List<String> tables = parser.extractTables(
                    "INSERT INTO t1 SELECT * FROM t2");
            assertTrue(tables.contains("t1"));
            assertTrue(tables.contains("t2"));
        }

        @Test
        @DisplayName("extractTables 子查询")
        void extractTablesSubquery() {
            List<String> tables = parser.extractTables(
                    "SELECT * FROM (SELECT * FROM t1) sub");
            assertTrue(tables.contains("t1"));
        }

        @Test
        @DisplayName("extractTables CREATE TABLE")
        void extractTablesCreate() {
            List<String> tables = parser.extractTables("CREATE TABLE t1 (a INT)");
            assertTrue(tables.contains("t1"));
        }

        @Test
        @DisplayName("extractTables 解析失败返回空")
        void extractTablesInvalidSql() {
            List<String> tables = parser.extractTables("NOT A SQL");
            assertTrue(tables.isEmpty());
        }

        @Test
        @DisplayName("extractColumns 单列")
        void extractColumnsSingle() {
            List<String> cols = parser.extractColumns("SELECT a FROM t1");
            assertTrue(cols.contains("a"));
        }

        @Test
        @DisplayName("extractColumns 多列")
        void extractColumnsMultiple() {
            List<String> cols = parser.extractColumns("SELECT a, b, c FROM t1");
            assertTrue(cols.containsAll(List.of("a", "b", "c")));
        }

        @Test
        @DisplayName("extractColumns WHERE 条件列")
        void extractColumnsWhere() {
            List<String> cols = parser.extractColumns("SELECT * FROM t1 WHERE a > 0 AND b < 10");
            assertTrue(cols.contains("a"));
            assertTrue(cols.contains("b"));
        }

        @Test
        @DisplayName("extractColumns 解析失败返回空")
        void extractColumnsInvalidSql() {
            List<String> cols = parser.extractColumns("NOT A SQL");
            assertTrue(cols.isEmpty());
        }
    }

    // ===================== 13. 校验 + 异常 =====================

    @Nested
    @DisplayName("校验与异常处理")
    class ValidateAndExceptionTest {

        @Test
        @DisplayName("validate 合法 SQL")
        void validateValid() {
            assertTrue(parser.validate("SELECT * FROM t1"));
        }

        @Test
        @DisplayName("validate 非法 SQL")
        void validateInvalid() {
            assertFalse(parser.validate("SELECT FROM"));
        }

        @Test
        @DisplayName("validate INSERT")
        void validateInsert() {
            assertTrue(parser.validate("INSERT INTO t1 VALUES (1)"));
        }

        @Test
        @DisplayName("validate CREATE")
        void validateCreate() {
            assertTrue(parser.validate("CREATE TABLE t1 (a INT)"));
        }

        @Test
        @DisplayName("validate DROP")
        void validateDrop() {
            assertTrue(parser.validate("DROP TABLE t1"));
        }

        @Test
        @DisplayName("空 SQL 抛出异常")
        void emptySqlThrows() {
            assertThrows(SqlParseException.class, () -> parser.parse("", SqlDialect.ANSI));
        }

        @Test
        @DisplayName("null SQL 抛出异常")
        void nullSqlThrows() {
            assertThrows(SqlParseException.class, () -> parser.parse(null, SqlDialect.ANSI));
        }

        @Test
        @DisplayName("未知语句类型抛出异常")
        void unknownStatementThrows() {
            assertThrows(SqlParseException.class, () -> parser.parse("FOOBAR t1", SqlDialect.ANSI));
        }

        @Test
        @DisplayName("异常包含位置信息")
        void exceptionContainsPosition() {
            try {
                parser.parse("SELECT FROM", SqlDialect.ANSI);
                fail("应抛出异常");
            } catch (SqlParseException e) {
                assertTrue(e.getPosition() >= 0 || e.getMessage().contains("position"));
            }
        }
    }

    // ===================== 14. AST 结构 =====================

    @Nested
    @DisplayName("AST 结构验证")
    class AstStructureTest {

        @Test
        @DisplayName("STATEMENT 根节点包含 dialect 属性")
        void rootHasDialect() {
            ASTNode ast = parser.parse("SELECT * FROM t1", SqlDialect.HIVE);
            assertEquals("HIVE", ast.getString("dialect"));
        }

        @Test
        @DisplayName("SELECT 子节点存在")
        void selectChildExists() {
            ASTNode ast = parser.parse("SELECT a FROM t1", SqlDialect.ANSI);
            assertEquals(ASTNode.NodeType.SELECT, ast.getChildren().get(0).getType());
        }

        @Test
        @DisplayName("FROM 子节点存在")
        void fromChildExists() {
            ASTNode ast = parser.parse("SELECT a FROM t1", SqlDialect.ANSI);
            ASTNode select = ast.findChild(ASTNode.NodeType.SELECT);
            assertNotNull(select.findChild(ASTNode.NodeType.FROM));
        }

        @Test
        @DisplayName("TABLE 节点包含 alias 属性")
        void tableNodeHasAlias() {
            ASTNode ast = parser.parse("SELECT * FROM t1 a", SqlDialect.ANSI);
            List<ASTNode> tables = ast.findAll(ASTNode.NodeType.TABLE);
            assertEquals(1, tables.size());
            assertEquals("a", tables.get(0).getString("alias"));
        }

        @Test
        @DisplayName("TABLE 节点 AS 别名")
        void tableNodeAsAlias() {
            ASTNode ast = parser.parse("SELECT * FROM t1 AS a", SqlDialect.ANSI);
            List<ASTNode> tables = ast.findAll(ASTNode.NodeType.TABLE);
            assertEquals(1, tables.size());
            assertEquals("a", tables.get(0).getString("alias"));
        }

        @Test
        @DisplayName("findAll 递归查找")
        void findAllRecursive() {
            ASTNode ast = parser.parse(
                    "SELECT * FROM t1 JOIN t2 ON t1.id = t2.id JOIN t3 ON t2.id = t3.id",
                    SqlDialect.ANSI);
            List<ASTNode> tables = ast.findAll(ASTNode.NodeType.TABLE);
            assertEquals(3, tables.size());
        }

        @Test
        @DisplayName("CTE (WITH) 解析")
        void cteParse() {
            ASTNode ast = parser.parse(
                    "WITH cte AS (SELECT a FROM t1) SELECT * FROM cte", SqlDialect.TRINO);
            assertNotNull(ast.findChild(ASTNode.NodeType.CTE));
        }

        @Test
        @DisplayName("CTE 多个定义")
        void cteMultiple() {
            ASTNode ast = parser.parse(
                    "WITH cte1 AS (SELECT a FROM t1), cte2 AS (SELECT b FROM t2) "
                            + "SELECT * FROM cte1 JOIN cte2 ON cte1.a = cte2.b",
                    SqlDialect.TRINO);
            ASTNode cte = ast.findChild(ASTNode.NodeType.CTE);
            assertNotNull(cte);
        }
    }

    // ===================== 15. 注释与边界 =====================

    @Nested
    @DisplayName("注释与边界情况")
    class CommentAndEdgeTest {

        @Test
        @DisplayName("单行注释")
        void singleLineComment() {
            ASTNode ast = parser.parse("SELECT a FROM t1 -- comment\n", SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("t1"));
        }

        @Test
        @DisplayName("多行注释")
        void multiLineComment() {
            ASTNode ast = parser.parse(
                    "/* header */ SELECT a FROM t1", SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("t1"));
        }

        @Test
        @DisplayName("反引号标识符")
        void backtickIdentifier() {
            ASTNode ast = parser.parse("SELECT `a` FROM `t1`", SqlDialect.HIVE);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT));
        }

        @Test
        @DisplayName("双引号字符串")
        void doubleQuotedString() {
            ASTNode ast = parser.parse("SELECT a FROM t1 WHERE b = \"abc\"", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("大小写无关关键字")
        void caseInsensitiveKeywords() {
            ASTNode ast = parser.parse("select a from t1", SqlDialect.ANSI);
            assertTrue(ast.extractTables().contains("t1"));
        }

        @Test
        @DisplayName("混合大小写关键字")
        void mixedCaseKeywords() {
            ASTNode ast = parser.parse("Select a From t1 Where a > 0", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("浮点数字面量")
        void floatLiteral() {
            ASTNode ast = parser.parse("SELECT a FROM t1 WHERE b > 3.14", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("科学计数法字面量")
        void scientificLiteral() {
            ASTNode ast = parser.parse("SELECT a FROM t1 WHERE b > 1e10", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }

        @Test
        @DisplayName("嵌套括号表达式")
        void nestedParenExpression() {
            ASTNode ast = parser.parse(
                    "SELECT ((a + b) * c) FROM t1", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT));
        }

        @Test
        @DisplayName("字符串中含转义引号")
        void stringWithEscapedQuote() {
            ASTNode ast = parser.parse(
                    "SELECT a FROM t1 WHERE b = 'it''s ok'", SqlDialect.ANSI);
            assertNotNull(ast.findChild(ASTNode.NodeType.SELECT).findChild(ASTNode.NodeType.WHERE));
        }
    }
}