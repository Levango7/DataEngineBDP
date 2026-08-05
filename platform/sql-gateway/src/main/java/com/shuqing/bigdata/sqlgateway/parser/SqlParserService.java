package com.shuqing.bigdata.sqlgateway.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 解析服务入口。
 *
 * <p>实现手写递归下降解析器，支持 ANSI SQL + Hive + Doris + Trino 方言的核心子集：
 * SELECT（含 JOIN/WHERE/GROUP BY/HAVING/ORDER BY/LIMIT/UNION/子查询）、
 * INSERT、CREATE TABLE、DROP、ALTER。</p>
 *
 * <p>不依赖 ANTLR4 生成代码，避免构建复杂性。{@code SqlGrammar.g4} 仅作为
 * 语法形式化定义文档保留在 resources 目录。</p>
 *
 * @author shuqing-bigdata
 */
public class SqlParserService {

    /** 关键字集合（大写） */
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "SELECT", "FROM", "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER",
            "CROSS", "ON", "GROUP", "BY", "HAVING", "ORDER", "ASC", "DESC", "LIMIT",
            "UNION", "ALL", "INSERT", "INTO", "VALUES", "CREATE", "EXTERNAL", "TABLE",
            "IF", "NOT", "EXISTS", "DROP", "ALTER", "ADD", "COLUMN", "COLUMNS", "RENAME",
            "TO", "AS", "AND", "OR", "IN", "BETWEEN", "LIKE", "NULL", "TRUE", "FALSE",
            "PARTITIONED", "STORED", "DISTRIBUTED", "HASH", "PROPERTIES", "WITH", "DISTINCT",
            "SET", "PARTITION", "OVERWRITE", "TEMPORARY", "VIEW", "FUNCTION", "IS", "TABLE"
    ));

    /** 方言检测：Hive 关键字 */
    private static final Set<String> HIVE_HINTS = new HashSet<>(Arrays.asList(
            "STORED", "PARTITIONED", "ROW", "SERDE", "INPUTFORMAT", "OUTPUTFORMAT",
            "LOCATION", "TBLPROPERTIES", "OVERWRITE"
    ));

    /** 方言检测：Doris 关键字 */
    private static final Set<String> DORIS_HINTS = new HashSet<>(Arrays.asList(
            "DISTRIBUTED", "PROPERTIES", "BUCKETS", "BITMAP", "BITMAP_HASH"
    ));

    /** 方言检测：Trino 关键字 */
    private static final Set<String> TRINO_HINTS = new HashSet<>(Arrays.asList(
            "WITH", "ARRAY", "MAP", "ROW", "CROSS", "FETCH", "OFFSET"
    ));

    /**
     * 解析 SQL 并返回 AST 根节点。
     *
     * @param sql     SQL 文本
     * @param dialect SQL 方言
     * @return AST 根节点（类型为 STATEMENT）
     * @throws SqlParseException 解析失败
     */
    public ASTNode parse(String sql, SqlDialect dialect) {
        if (sql == null || sql.isBlank()) {
            throw new SqlParseException("SQL 不能为空");
        }
        ParserContext ctx = new ParserContext(sql, dialect == null ? SqlDialect.ANSI : dialect);
        ASTNode stmt = ctx.parseStatement();
        return stmt;
    }

    /**
     * 自动检测方言并解析。
     *
     * @param sql SQL 文本
     * @return AST 根节点
     * @throws SqlParseException 解析失败
     */
    public ASTNode parseAuto(String sql) {
        return parse(sql, detectDialect(sql));
    }

    /**
     * 提取 SQL 涉及的所有表名（用于血缘分析）。
     *
     * @param sql SQL 文本
     * @return 表名列表
     */
    public List<String> extractTables(String sql) {
        try {
            return parseAuto(sql).extractTables();
        } catch (SqlParseException e) {
            return Collections.emptyList();
        }
    }

    /**
     * 提取 SQL 涉及的所有列名。
     *
     * @param sql SQL 文本
     * @return 列名列表
     */
    public List<String> extractColumns(String sql) {
        try {
            return parseAuto(sql).extractColumns();
        } catch (SqlParseException e) {
            return Collections.emptyList();
        }
    }

    /**
     * SQL 语法校验。
     *
     * @param sql SQL 文本
     * @return {@code true} 表示语法合法
     */
    public boolean validate(String sql) {
        try {
            parseAuto(sql);
            return true;
        } catch (SqlParseException e) {
            return false;
        }
    }

    /**
     * 自动检测 SQL 方言。
     *
     * <p>检测策略：扫描 SQL 中的方言特征关键字，命中 Hive/Doris/Trino 之一则返回对应方言，
     * 否则返回 ANSI。多方言同时命中时按 Hive &gt; Doris &gt; Trino 优先级返回。</p>
     *
     * @param sql SQL 文本
     * @return 检测到的方言
     */
    public SqlDialect detectDialect(String sql) {
        if (sql == null || sql.isBlank()) {
            return SqlDialect.ANSI;
        }
        String upper = sql.toUpperCase(Locale.ROOT);
        int hiveScore = 0;
        int dorisScore = 0;
        int trinoScore = 0;
        for (String kw : HIVE_HINTS) {
            if (containsKeyword(upper, kw)) {
                hiveScore++;
            }
        }
        for (String kw : DORIS_HINTS) {
            if (containsKeyword(upper, kw)) {
                dorisScore++;
            }
        }
        for (String kw : TRINO_HINTS) {
            if (containsKeyword(upper, kw)) {
                trinoScore++;
            }
        }
        if (hiveScore >= dorisScore && hiveScore >= trinoScore && hiveScore > 0) {
            return SqlDialect.HIVE;
        }
        if (dorisScore >= trinoScore && dorisScore > 0) {
            return SqlDialect.DORIS;
        }
        if (trinoScore > 0) {
            return SqlDialect.TRINO;
        }
        return SqlDialect.ANSI;
    }

    private boolean containsKeyword(String upperSql, String keyword) {
        int idx = upperSql.indexOf(keyword);
        while (idx >= 0) {
            int end = idx + keyword.length();
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(upperSql.charAt(idx - 1))
                    && upperSql.charAt(idx - 1) != '_';
            boolean rightOk = end >= upperSql.length()
                    || !Character.isLetterOrDigit(upperSql.charAt(end))
                    && upperSql.charAt(end) != '_';
            if (leftOk && rightOk) {
                return true;
            }
            idx = upperSql.indexOf(keyword, end);
        }
        return false;
    }

    // ===================== 内部：Token =====================

    /** Token 类型 */
    private enum TokenType {
        KEYWORD, IDENTIFIER, NUMBER, STRING, OPERATOR, PUNCT, STAR, EOF
    }

    /** 词法 Token */
    private static final class Token {
        final TokenType type;
        final String text;
        final int pos;

        Token(TokenType type, String text, int pos) {
            this.type = type;
            this.text = text;
            this.pos = pos;
        }

        @Override
        public String toString() {
            return type + "(" + text + ")@" + pos;
        }
    }

    // ===================== 内部：词法分析器 =====================

    private static final class Lexer {
        private final String src;
        private final List<Token> tokens = new ArrayList<>();
        private int i = 0;

        Lexer(String src) {
            this.src = src;
        }

        List<Token> tokenize() {
            while (i < src.length()) {
                char c = src.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (c == '-' && i + 1 < src.length() && src.charAt(i + 1) == '-') {
                    skipLineComment();
                    continue;
                }
                if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                    skipBlockComment();
                    continue;
                }
                if (c == '*' && (tokens.isEmpty() || isAfterOperatorOrPunct())) {
                    tokens.add(new Token(TokenType.STAR, "*", i));
                    i++;
                    continue;
                }
                if (c == '\'' || c == '"') {
                    readString(c);
                    continue;
                }
                if (c == '`') {
                    readBacktickIdent();
                    continue;
                }
                if (Character.isDigit(c) || (c == '.' && i + 1 < src.length()
                        && Character.isDigit(src.charAt(i + 1)))) {
                    readNumber();
                    continue;
                }
                if (Character.isLetter(c) || c == '_') {
                    readIdentifierOrKeyword();
                    continue;
                }
                if (isOperatorChar(c)) {
                    readOperator();
                    continue;
                }
                if (isPunctChar(c)) {
                    tokens.add(new Token(TokenType.PUNCT, String.valueOf(c), i));
                    i++;
                    continue;
                }
                throw new SqlParseException("无法识别的字符: " + c, i);
            }
            tokens.add(new Token(TokenType.EOF, "", i));
            return tokens;
        }

        private boolean isAfterOperatorOrPunct() {
            Token last = tokens.get(tokens.size() - 1);
            return last.type == TokenType.OPERATOR || last.type == TokenType.PUNCT
                    || last.type == TokenType.STAR
                    || (last.type == TokenType.KEYWORD && (last.text.equals("SELECT")
                    || last.text.equals("FROM") || last.text.equals("WHERE")
                    || last.text.equals("ON") || last.text.equals("AND") || last.text.equals("OR")
                    || last.text.equals("BY") || last.text.equals("JOIN")));
        }

        private void skipLineComment() {
            while (i < src.length() && src.charAt(i) != '\n') {
                i++;
            }
        }

        private void skipBlockComment() {
            i += 2;
            while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                i++;
            }
            i += 2;
        }

        private void readString(char quote) {
            int start = i;
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == quote) {
                    if (i + 1 < src.length() && src.charAt(i + 1) == quote) {
                        sb.append(quote);
                        i += 2;
                        continue;
                    }
                    i++;
                    tokens.add(new Token(TokenType.STRING, sb.toString(), start));
                    return;
                }
                if (c == '\\' && i + 1 < src.length()) {
                    sb.append(src.charAt(i + 1));
                    i += 2;
                    continue;
                }
                sb.append(c);
                i++;
            }
            throw new SqlParseException("未闭合的字符串", start);
        }

        private void readBacktickIdent() {
            int start = i;
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < src.length() && src.charAt(i) != '`') {
                sb.append(src.charAt(i));
                i++;
            }
            if (i >= src.length()) {
                throw new SqlParseException("未闭合的反引号标识符", start);
            }
            i++;
            tokens.add(new Token(TokenType.IDENTIFIER, sb.toString(), start));
        }

        private void readNumber() {
            int start = i;
            boolean dot = false;
            boolean exp = false;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (Character.isDigit(c)) {
                    i++;
                } else if (c == '.' && !dot && !exp) {
                    dot = true;
                    i++;
                } else if ((c == 'e' || c == 'E') && !exp) {
                    exp = true;
                    i++;
                    if (i < src.length() && (src.charAt(i) == '+' || src.charAt(i) == '-')) {
                        i++;
                    }
                } else {
                    break;
                }
            }
            tokens.add(new Token(TokenType.NUMBER, src.substring(start, i), start));
        }

        private void readIdentifierOrKeyword() {
            int start = i;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '_') {
                    i++;
                } else {
                    break;
                }
            }
            String text = src.substring(start, i);
            String upper = text.toUpperCase(Locale.ROOT);
            if (KEYWORDS.contains(upper)) {
                tokens.add(new Token(TokenType.KEYWORD, upper, start));
            } else {
                tokens.add(new Token(TokenType.IDENTIFIER, text, start));
            }
        }

        private void readOperator() {
            int start = i;
            char c = src.charAt(i);
            if (i + 1 < src.length()) {
                String two = src.substring(i, i + 2);
                if (two.equals("!=") || two.equals("<>") || two.equals("<=") || two.equals(">=")
                        || two.equals("||") || two.equals("==")) {
                    i += 2;
                    tokens.add(new Token(TokenType.OPERATOR,
                            two.equals("<>") ? "!=" : two, start));
                    return;
                }
            }
            i++;
            tokens.add(new Token(TokenType.OPERATOR, String.valueOf(c), start));
        }

        private static boolean isOperatorChar(char c) {
            return "=<>+-/%!|&".indexOf(c) >= 0;
        }

        private static boolean isPunctChar(char c) {
            return "().,;:".indexOf(c) >= 0;
        }
    }

    // ===================== 内部：解析器上下文 =====================

    private static final class ParserContext {
        private final List<Token> tokens;
        private int pos = 0;
        private final SqlDialect dialect;

        ParserContext(String sql, SqlDialect dialect) {
            this.tokens = new Lexer(sql).tokenize();
            this.dialect = dialect;
        }

        ASTNode parseStatement() {
            ASTNode stmt = new ASTNode(ASTNode.NodeType.STATEMENT);
            stmt.setProperty("dialect", dialect.name());
            Token t = peek();
            if (t.type != TokenType.KEYWORD) {
                throw new SqlParseException("期望 SQL 关键字开头，但遇到: " + t.text, t.pos);
            }
            switch (t.text) {
                case "SELECT" -> stmt.addChild(parseSelectOrUnion());
                case "INSERT" -> stmt.addChild(parseInsert());
                case "CREATE" -> stmt.addChild(parseCreate());
                case "DROP" -> stmt.addChild(parseDrop());
                case "ALTER" -> stmt.addChild(parseAlter());
                case "WITH" -> stmt.addChild(parseWithSelect());
                default -> throw new SqlParseException("不支持的语句类型: " + t.text, t.pos);
            }
            // 容许结尾分号
            if (peek().type == TokenType.PUNCT && peek().text.equals(";")) {
                next();
            }
            expectEof();
            return stmt;
        }

        // ---------- WITH (CTE) ----------
        ASTNode parseWithSelect() {
            ASTNode cte = new ASTNode(ASTNode.NodeType.CTE);
            expectKeyword("WITH");
            do {
                String name = expectIdentifier();
                expectKeyword("AS");
                expectPunct("(");
                ASTNode sub = parseSelectOrUnion();
                expectPunct(")");
                ASTNode item = new ASTNode(ASTNode.NodeType.SUBQUERY);
                item.setProperty("name", name);
                item.addChild(sub);
                cte.addChild(item);
                if (peek().type == TokenType.PUNCT && peek().text.equals(",")) {
                    next();
                } else {
                    break;
                }
            } while (true);
            cte.addChild(parseSelectOrUnion());
            return cte;
        }

        // ---------- SELECT / UNION ----------
        ASTNode parseSelectOrUnion() {
            ASTNode left = parseSelect();
            while (peek().type == TokenType.KEYWORD && peek().text.equals("UNION")) {
                next();
                if (peek().type == TokenType.KEYWORD && peek().text.equals("ALL")) {
                    next();
                }
                ASTNode right = parseSelect();
                ASTNode union = new ASTNode(ASTNode.NodeType.UNION);
                union.addChild(left);
                union.addChild(right);
                left = union;
            }
            return left;
        }

        ASTNode parseSelect() {
            ASTNode select = new ASTNode(ASTNode.NodeType.SELECT);
            expectKeyword("SELECT");
            if (peek().type == TokenType.KEYWORD && peek().text.equals("DISTINCT")) {
                next();
                select.setProperty("distinct", true);
            }
            // SELECT 列表
            List<String> columns = new ArrayList<>();
            List<ASTNode> colNodes = new ArrayList<>();
            do {
                if (peek().type == TokenType.STAR) {
                    next();
                    ASTNode col = new ASTNode(ASTNode.NodeType.COLUMN);
                    col.setProperty("name", "*");
                    colNodes.add(col);
                    columns.add("*");
                } else {
                    String colExpr = readExpressionTokenText();
                    String alias = null;
                    if (peek().type == TokenType.KEYWORD && peek().text.equals("AS")) {
                        next();
                        alias = expectIdentifier();
                    } else if (peek().type == TokenType.IDENTIFIER
                            && !isClauseStart(peek())) {
                        alias = next().text;
                    }
                    ASTNode col = new ASTNode(ASTNode.NodeType.COLUMN);
                    String colName = extractColumnName(colExpr);
                    col.setProperty("name", colName);
                    col.setProperty("expression", colExpr);
                    if (alias != null) {
                        col.setProperty("alias", alias);
                    }
                    colNodes.add(col);
                    columns.add(colName);
                }
            } while (consumeComma());
            select.setProperty("columns", columns);
            colNodes.forEach(select::addChild);

            // FROM
            if (peek().type == TokenType.KEYWORD && peek().text.equals("FROM")) {
                select.addChild(parseFrom());
            }
            // WHERE
            if (peek().type == TokenType.KEYWORD && peek().text.equals("WHERE")) {
                select.addChild(parseWhere());
            }
            // GROUP BY
            if (peek().type == TokenType.KEYWORD && peek().text.equals("GROUP")) {
                select.addChild(parseGroupBy());
            }
            // HAVING
            if (peek().type == TokenType.KEYWORD && peek().text.equals("HAVING")) {
                select.addChild(parseHaving());
            }
            // ORDER BY
            if (peek().type == TokenType.KEYWORD && peek().text.equals("ORDER")) {
                select.addChild(parseOrderBy());
            }
            // LIMIT
            if (peek().type == TokenType.KEYWORD && peek().text.equals("LIMIT")) {
                select.addChild(parseLimit());
            }
            return select;
        }

        // ---------- FROM ----------
        ASTNode parseFrom() {
            ASTNode from = new ASTNode(ASTNode.NodeType.FROM);
            expectKeyword("FROM");
            from.addChild(parseTableReference());
            // JOINs
            while (isRealJoinStart()) {
                from.addChild(parseJoin());
            }
            return from;
        }

        ASTNode parseTableReference() {
            if (peek().type == TokenType.PUNCT && peek().text.equals("(")) {
                next();
                ASTNode sub = parseSelectOrUnion();
                expectPunct(")");
                String alias = null;
                if (peek().type == TokenType.KEYWORD && peek().text.equals("AS")) {
                    next();
                    alias = expectIdentifier();
                } else if (peek().type == TokenType.IDENTIFIER && !isClauseStart(peek())) {
                    alias = next().text;
                } else if (peek().type == TokenType.KEYWORD && !isClauseStart(peek())
                        && !isRealJoinStart()) {
                    // 允许关键字作为子查询别名（如 inner、outer 等保留字被用作别名）
                    alias = next().text;
                }
                ASTNode subNode = new ASTNode(ASTNode.NodeType.SUBQUERY);
                subNode.addChild(sub);
                if (alias != null) {
                    subNode.setProperty("alias", alias);
                }
                return subNode;
            }
            String tableName = expectIdentifier();
            // 数据库前缀 db.table
            if (peek().type == TokenType.PUNCT && peek().text.equals(".")) {
                next();
                tableName = tableName + "." + expectIdentifier();
            }
            String alias = null;
            if (peek().type == TokenType.KEYWORD && peek().text.equals("AS")) {
                next();
                alias = expectIdentifier();
            } else if (peek().type == TokenType.IDENTIFIER && !isClauseStart(peek())
                    && !isRealJoinStart()) {
                alias = next().text;
            }
            ASTNode table = new ASTNode(ASTNode.NodeType.TABLE);
            table.setProperty("name", tableName);
            if (alias != null) {
                table.setProperty("alias", alias);
            }
            return table;
        }

        // ---------- JOIN ----------
        ASTNode parseJoin() {
            ASTNode join = new ASTNode(ASTNode.NodeType.JOIN);
            String joinType = "INNER";
            if (peek().text.equals("INNER")) {
                next();
            } else if (peek().text.equals("LEFT")) {
                next();
                joinType = "LEFT";
                if (peek().text.equals("OUTER")) {
                    next();
                }
            } else if (peek().text.equals("RIGHT")) {
                next();
                joinType = "RIGHT";
                if (peek().text.equals("OUTER")) {
                    next();
                }
            } else if (peek().text.equals("FULL")) {
                next();
                joinType = "FULL";
                if (peek().text.equals("OUTER")) {
                    next();
                }
            } else if (peek().text.equals("CROSS")) {
                next();
                joinType = "CROSS";
            }
            expectKeyword("JOIN");
            join.setProperty("joinType", joinType);
            join.addChild(parseTableReference());
            if (peek().type == TokenType.KEYWORD && peek().text.equals("ON")) {
                next();
                String cond = readExpressionTokenText();
                join.setProperty("on", cond);
                // 提取 ON 条件中的列
                addColumnNodesFromExpr(join, cond);
            }
            return join;
        }

        // ---------- WHERE ----------
        ASTNode parseWhere() {
            ASTNode where = new ASTNode(ASTNode.NodeType.WHERE);
            expectKeyword("WHERE");
            String cond = readExpressionTokenText();
            where.setProperty("condition", cond);
            addColumnNodesFromExpr(where, cond);
            return where;
        }

        // ---------- GROUP BY ----------
        ASTNode parseGroupBy() {
            ASTNode group = new ASTNode(ASTNode.NodeType.GROUP_BY);
            expectKeyword("GROUP");
            expectKeyword("BY");
            List<String> cols = new ArrayList<>();
            do {
                String expr = readExpressionTokenText();
                cols.add(expr);
                ASTNode col = new ASTNode(ASTNode.NodeType.COLUMN);
                col.setProperty("name", extractColumnName(expr));
                col.setProperty("expression", expr);
                group.addChild(col);
            } while (consumeComma());
            group.setProperty("columns", cols);
            return group;
        }

        // ---------- HAVING ----------
        ASTNode parseHaving() {
            ASTNode having = new ASTNode(ASTNode.NodeType.HAVING);
            expectKeyword("HAVING");
            String cond = readExpressionTokenText();
            having.setProperty("condition", cond);
            addColumnNodesFromExpr(having, cond);
            return having;
        }

        // ---------- ORDER BY ----------
        ASTNode parseOrderBy() {
            ASTNode order = new ASTNode(ASTNode.NodeType.ORDER_BY);
            expectKeyword("ORDER");
            expectKeyword("BY");
            List<String> cols = new ArrayList<>();
            do {
                String expr = readExpressionTokenText();
                String direction = "ASC";
                if (peek().type == TokenType.KEYWORD && peek().text.equals("ASC")) {
                    next();
                } else if (peek().type == TokenType.KEYWORD && peek().text.equals("DESC")) {
                    next();
                    direction = "DESC";
                }
                cols.add(expr + " " + direction);
                ASTNode col = new ASTNode(ASTNode.NodeType.COLUMN);
                col.setProperty("name", extractColumnName(expr));
                col.setProperty("expression", expr);
                col.setProperty("direction", direction);
                order.addChild(col);
            } while (consumeComma());
            order.setProperty("columns", cols);
            return order;
        }

        // ---------- LIMIT ----------
        ASTNode parseLimit() {
            ASTNode limit = new ASTNode(ASTNode.NodeType.LIMIT);
            expectKeyword("LIMIT");
            Token t = next();
            if (t.type != TokenType.NUMBER) {
                throw new SqlParseException("LIMIT 后期望数字，但遇到: " + t.text, t.pos);
            }
            limit.setProperty("count", Long.parseLong(t.text));
            // 支持 LIMIT offset, count
            if (peek().type == TokenType.PUNCT && peek().text.equals(",")) {
                next();
                Token t2 = next();
                if (t2.type != TokenType.NUMBER) {
                    throw new SqlParseException("LIMIT 偏移后期望数字，但遇到: " + t2.text, t2.pos);
                }
                limit.setProperty("offset", Long.parseLong(t.text));
                limit.setProperty("count", Long.parseLong(t2.text));
            }
            return limit;
        }

        // ---------- INSERT ----------
        ASTNode parseInsert() {
            ASTNode insert = new ASTNode(ASTNode.NodeType.INSERT);
            expectKeyword("INSERT");
            if (peek().type == TokenType.KEYWORD && peek().text.equals("OVERWRITE")) {
                next();
                insert.setProperty("overwrite", true);
            }
            // 支持 INSERT OVERWRITE TABLE t1 / INSERT INTO TABLE t1 / INSERT INTO t1
            if (peek().type == TokenType.KEYWORD && peek().text.equals("INTO")) {
                next();
            } else if (peek().type == TokenType.KEYWORD && peek().text.equals("TABLE")) {
                next();
            } else {
                throw new SqlParseException("期望 INTO 或 TABLE，但遇到: " + peek().text, peek().pos);
            }
            // INSERT OVERWRITE TABLE 后可能还有 TABLE 关键字
            if (peek().type == TokenType.KEYWORD && peek().text.equals("TABLE")) {
                next();
            }
            String tableName = expectIdentifier();
            if (peek().type == TokenType.PUNCT && peek().text.equals(".")) {
                next();
                tableName = tableName + "." + expectIdentifier();
            }
            insert.setProperty("table", tableName);

            // 可选列清单
            if (peek().type == TokenType.PUNCT && peek().text.equals("(")) {
                next();
                List<String> cols = new ArrayList<>();
                do {
                    String c = expectIdentifier();
                    cols.add(c);
                    ASTNode col = new ASTNode(ASTNode.NodeType.COLUMN);
                    col.setProperty("name", c);
                    insert.addChild(col);
                } while (consumeComma());
                expectPunct(")");
                insert.setProperty("columns", cols);
            }
            // VALUES 或 SELECT
            if (peek().type == TokenType.KEYWORD && peek().text.equals("VALUES")) {
                next();
                insert.setProperty("mode", "VALUES");
                List<List<String>> rows = new ArrayList<>();
                do {
                    expectPunct("(");
                    List<String> row = new ArrayList<>();
                    do {
                        Token t = next();
                        row.add(t.text);
                    } while (consumeComma());
                    expectPunct(")");
                    rows.add(row);
                } while (consumeComma());
                insert.setProperty("values", rows);
            } else if (peek().type == TokenType.KEYWORD && peek().text.equals("SELECT")) {
                insert.setProperty("mode", "SELECT");
                insert.addChild(parseSelectOrUnion());
            } else {
                throw new SqlParseException("INSERT 后期望 VALUES 或 SELECT", peek().pos);
            }
            return insert;
        }

        // ---------- CREATE TABLE ----------
        ASTNode parseCreate() {
            ASTNode create = new ASTNode(ASTNode.NodeType.CREATE_TABLE);
            expectKeyword("CREATE");
            if (peek().type == TokenType.KEYWORD && peek().text.equals("EXTERNAL")) {
                next();
                create.setProperty("external", true);
            }
            if (peek().type == TokenType.KEYWORD && peek().text.equals("TEMPORARY")) {
                next();
                create.setProperty("temporary", true);
            }
            expectKeyword("TABLE");
            if (peek().type == TokenType.KEYWORD && peek().text.equals("IF")) {
                next();
                expectKeyword("NOT");
                expectKeyword("EXISTS");
                create.setProperty("ifNotExists", true);
            }
            String tableName = expectIdentifier();
            if (peek().type == TokenType.PUNCT && peek().text.equals(".")) {
                next();
                tableName = tableName + "." + expectIdentifier();
            }
            create.setProperty("table", tableName);
            // 列定义
            if (peek().type == TokenType.PUNCT && peek().text.equals("(")) {
                next();
                List<String> cols = new ArrayList<>();
                do {
                    String colName = expectIdentifier();
                    cols.add(colName);
                    ASTNode col = new ASTNode(ASTNode.NodeType.COLUMN);
                    col.setProperty("name", colName);
                    // 跳过数据类型（可能含括号参数如 VARCHAR(255)、DECIMAL(10,2)）
                    skipDataType();
                    // 跳过列约束（NOT NULL、DEFAULT 等）直到逗号或右括号
                    skipUntilCommaOrParen();
                    create.addChild(col);
                } while (consumeComma());
                expectPunct(")");
                create.setProperty("columns", cols);
            }
            // 跳过剩余可选子句（PARTITIONED BY、STORED AS、DISTRIBUTED BY、PROPERTIES、LOCATION 等）
            skipRemainingDdlClauses(create);
            return create;
        }

        // ---------- DROP ----------
        ASTNode parseDrop() {
            ASTNode drop = new ASTNode(ASTNode.NodeType.DROP);
            expectKeyword("DROP");
            expectKeyword("TABLE");
            if (peek().type == TokenType.KEYWORD && peek().text.equals("IF")) {
                next();
                expectKeyword("EXISTS");
                drop.setProperty("ifExists", true);
            }
            String tableName = expectIdentifier();
            if (peek().type == TokenType.PUNCT && peek().text.equals(".")) {
                next();
                tableName = tableName + "." + expectIdentifier();
            }
            drop.setProperty("table", tableName);
            return drop;
        }

        // ---------- ALTER ----------
        ASTNode parseAlter() {
            ASTNode alter = new ASTNode(ASTNode.NodeType.ALTER);
            expectKeyword("ALTER");
            expectKeyword("TABLE");
            String tableName = expectIdentifier();
            if (peek().type == TokenType.PUNCT && peek().text.equals(".")) {
                next();
                tableName = tableName + "." + expectIdentifier();
            }
            alter.setProperty("table", tableName);
            // 解析 ALTER 动作
            if (peek().type == TokenType.KEYWORD) {
                String action = peek().text;
                alter.setProperty("action", action);
                // 跳过剩余 token（ADD COLUMN x TYPE / DROP COLUMN x / RENAME TO y）
                while (peek().type != TokenType.EOF
                        && !(peek().type == TokenType.PUNCT && peek().text.equals(";"))) {
                    Token t = next();
                    if (t.type == TokenType.IDENTIFIER) {
                        if ("ADD".equals(action)) {
                            ASTNode col = new ASTNode(ASTNode.NodeType.COLUMN);
                            col.setProperty("name", t.text);
                            alter.addChild(col);
                        } else if ("RENAME".equals(action) && "TO".equals(alter.getString("lastKeyword"))) {
                            alter.setProperty("newName", t.text);
                        }
                    }
                    if (t.type == TokenType.KEYWORD && ("TO".equals(t.text) || "COLUMN".equals(t.text))) {
                        alter.setProperty("lastKeyword", t.text);
                    }
                }
            }
            return alter;
        }

        // ===================== 表达式读取 =====================

        /**
         * 读取一个表达式直到遇到子句边界（FROM/WHERE/GROUP/HAVING/ORDER/LIMIT/UNION/JOIN/ON/AND/OR
         * 等顶层关键字）、逗号、右括号或 EOF，返回表达式的文本表示。
         */
        String readExpressionTokenText() {
            StringBuilder sb = new StringBuilder();
            int parenDepth = 0;
            boolean lastWasValue = false;
            while (true) {
                Token t = peek();
                if (t.type == TokenType.EOF) {
                    break;
                }
                if (t.type == TokenType.PUNCT) {
                    if (t.text.equals(")") && parenDepth == 0) {
                        break;
                    }
                    if (t.text.equals("(")) {
                        parenDepth++;
                    } else if (t.text.equals(")")) {
                        parenDepth--;
                    } else if (t.text.equals(",") && parenDepth == 0) {
                        break;
                    } else if (t.text.equals(";") && parenDepth == 0) {
                        break;
                    }
                }
                if (parenDepth == 0 && t.type == TokenType.KEYWORD && isExpressionTerminator(t.text)) {
                    break;
                }
                // 处理 AS 别名：表达式末尾的 AS 标识符不属于表达式
                if (parenDepth == 0 && t.type == TokenType.KEYWORD && t.text.equals("AS")) {
                    break;
                }
                if (parenDepth == 0 && t.type == TokenType.IDENTIFIER && lastWasValue
                        && !isClauseStart(t) && !isJoinStart(t)) {
                    // 隐式别名，留给上层处理
                    break;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(t.text);
                lastWasValue = t.type == TokenType.IDENTIFIER || t.type == TokenType.NUMBER
                        || t.type == TokenType.STRING || t.type == TokenType.STAR
                        || t.text.equals(")");
                next();
            }
            return sb.toString().trim();
        }

        boolean isExpressionTerminator(String kw) {
            return kw.equals("FROM") || kw.equals("WHERE") || kw.equals("GROUP")
                    || kw.equals("HAVING") || kw.equals("ORDER") || kw.equals("LIMIT")
                    || kw.equals("UNION") || kw.equals("JOIN") || kw.equals("INNER")
                    || kw.equals("LEFT") || kw.equals("RIGHT") || kw.equals("FULL")
                    || kw.equals("CROSS") || kw.equals("ON");
        }

        boolean isClauseStart(Token t) {
            return t.type == TokenType.KEYWORD && (t.text.equals("FROM") || t.text.equals("WHERE")
                    || t.text.equals("GROUP") || t.text.equals("HAVING") || t.text.equals("ORDER")
                    || t.text.equals("LIMIT") || t.text.equals("UNION") || t.text.equals("JOIN")
                    || t.text.equals("ON")
                    || t.text.equals("VALUES") || t.text.equals("SELECT"));
        }

        boolean isJoinStart(Token t) {
            return t.type == TokenType.KEYWORD && (t.text.equals("JOIN") || t.text.equals("INNER")
                    || t.text.equals("LEFT") || t.text.equals("RIGHT") || t.text.equals("FULL")
                    || t.text.equals("CROSS"));
        }

        /** 检查当前位置是否是真正的 JOIN 开始（INNER/LEFT/RIGHT/FULL/CROSS 后必须跟 JOIN/OUTER） */
        boolean isRealJoinStart() {
            Token t = peek();
            if (t.type != TokenType.KEYWORD) {
                return false;
            }
            if (t.text.equals("JOIN")) {
                return true;
            }
            if (t.text.equals("INNER") || t.text.equals("LEFT") || t.text.equals("RIGHT")
                    || t.text.equals("FULL") || t.text.equals("CROSS")) {
                // 检查下一个 token 是否是 JOIN 或 OUTER
                if (pos + 1 < tokens.size()) {
                    Token next = tokens.get(pos + 1);
                    if (next.type == TokenType.KEYWORD
                            && (next.text.equals("JOIN") || next.text.equals("OUTER"))) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** 从表达式文本中提取列名（去掉函数调用、字面量、运算符） */
        String extractColumnName(String expr) {
            if (expr == null || expr.isEmpty()) {
                return expr;
            }
            // 处理 a.b 形式：保留为 a.b
            // 处理函数调用 func(...) → 返回 func(...) 整体作为 expression，name 取 func
            int paren = expr.indexOf('(');
            if (paren > 0) {
                return expr.substring(0, paren).trim();
            }
            // 处理 t.col 形式
            return expr.trim();
        }

        /** 从表达式文本中识别列名并添加 COLUMN 子节点 */
        void addColumnNodesFromExpr(ASTNode parent, String expr) {
            if (expr == null || expr.isEmpty()) {
                return;
            }
            // 简单识别：按运算符、空格、逗号分割，识别标识符（非关键字、非数字、非字符串）
            String[] parts = expr.split("[\\s+\\-*/%=<>!&|(),]+");
            for (String p : parts) {
                if (p.isEmpty()) {
                    continue;
                }
                if (KEYWORDS.contains(p.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                if (p.matches("\\d+(\\.\\d+)?")) {
                    continue;
                }
                if (p.startsWith("'") || p.startsWith("\"")) {
                    continue;
                }
                // 跳过纯别名（点号开头）
                ASTNode col = new ASTNode(ASTNode.NodeType.COLUMN);
                col.setProperty("name", p);
                col.setProperty("expression", p);
                parent.addChild(col);
            }
        }

        // ===================== 跳过辅助 =====================

        void skipDataType() {
            // 数据类型：IDENTIFIER [( ... )]
            Token t = next();
            if (t.type != TokenType.IDENTIFIER && t.type != TokenType.KEYWORD) {
                throw new SqlParseException("期望数据类型，但遇到: " + t.text, t.pos);
            }
            // 处理复合类型如 DECIMAL(10,2)、VARCHAR(255)、ARRAY<INT>（简化：跳过括号内）
            if (peek().type == TokenType.PUNCT && peek().text.equals("(")) {
                int depth = 0;
                do {
                    Token p = next();
                    if (p.text.equals("(")) {
                        depth++;
                    } else if (p.text.equals(")")) {
                        depth--;
                    }
                } while (depth > 0 && peek().type != TokenType.EOF);
            }
        }

        void skipUntilCommaOrParen() {
            while (peek().type != TokenType.EOF) {
                Token t = peek();
                if (t.type == TokenType.PUNCT && (t.text.equals(",") || t.text.equals(")"))) {
                    return;
                }
                next();
            }
        }

        void skipRemainingDdlClauses(ASTNode ddl) {
            while (peek().type != TokenType.EOF
                    && !(peek().type == TokenType.PUNCT && peek().text.equals(";"))) {
                Token t = next();
                if (t.type == TokenType.KEYWORD) {
                    switch (t.text) {
                        case "PARTITIONED" -> ddl.setProperty("partitioned", true);
                        case "STORED" -> ddl.setProperty("stored", true);
                        case "DISTRIBUTED" -> ddl.setProperty("distributed", true);
                        case "PROPERTIES" -> ddl.setProperty("properties", true);
                        case "LOCATION" -> ddl.setProperty("location", true);
                        default -> {
                        }
                    }
                }
            }
        }

        // ===================== Token 工具 =====================

        Token peek() {
            return tokens.get(pos);
        }

        Token next() {
            return tokens.get(pos++);
        }

        boolean consumeComma() {
            if (peek().type == TokenType.PUNCT && peek().text.equals(",")) {
                next();
                return true;
            }
            return false;
        }

        void expectKeyword(String kw) {
            Token t = next();
            if (t.type != TokenType.KEYWORD || !t.text.equals(kw)) {
                throw new SqlParseException("期望关键字 " + kw + "，但遇到: " + t.text, t.pos);
            }
        }

        void expectPunct(String p) {
            Token t = next();
            if (t.type != TokenType.PUNCT || !t.text.equals(p)) {
                throw new SqlParseException("期望 '" + p + "'，但遇到: " + t.text, t.pos);
            }
        }

        String expectIdentifier() {
            Token t = next();
            if (t.type != TokenType.IDENTIFIER) {
                throw new SqlParseException("期望标识符，但遇到: " + t.text, t.pos);
            }
            return t.text;
        }

        void expectEof() {
            Token t = peek();
            if (t.type != TokenType.EOF) {
                throw new SqlParseException("期望语句结束，但遇到: " + t.text, t.pos);
            }
        }
    }
}