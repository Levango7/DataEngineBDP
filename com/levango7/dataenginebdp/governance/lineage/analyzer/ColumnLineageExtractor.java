package com.shuqing.bigdata.governance.lineage.analyzer;

import com.shuqing.bigdata.sqlgateway.parser.ASTNode;
import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;
import com.shuqing.bigdata.sqlgateway.parser.SqlParseException;
import com.shuqing.bigdata.sqlgateway.parser.SqlParserService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字段级血缘提取器。
 *
 * <p>在表级血缘基础上，进一步解析 SELECT 列表中每个列引用的来源表，
 * 产生字段到字段的血缘关系：
 * <ul>
 *   <li>{@code INSERT INTO t2(c1, c2) SELECT a.x, b.y FROM a JOIN b}：
 *       {@code a.x → t2.c1}、{@code b.y → t2.c2}</li>
 *   <li>{@code INSERT INTO t2 SELECT a.x, b.y FROM a JOIN b}（未指定列清单）：
 *       按位置映射，目标列名取源列名：{@code a.x → t2.x}、{@code b.y → t2.y}</li>
 *   <li>纯 {@code SELECT a.x, b.y FROM a JOIN b}：虚拟目标 {@code result}：
 *       {@code a.x → result.x}、{@code b.y → result.y}</li>
 *   <li>{@code SELECT * FROM a}：展开为 {@code a.* → result.*}</li>
 * </ul>
 *
 * <p>表达式列（如 {@code SUM(a.x)}）通过提取表达式中的列引用，将每个引用列
 * 都映射到目标列，表达式记录在 {@link LineageRelation#getExpression()}。</p>
 *
 * @author shuqing-bigdata
 */
public class ColumnLineageExtractor {

    /** 纯 SELECT 语句的虚拟目标表名 */
    public static final String VIRTUAL_TARGET = "result";

    private final SqlParserService parser;

    /**
     * 构造提取器。
     *
     * @param parser SQL 解析器
     */
    public ColumnLineageExtractor(SqlParserService parser) {
        this.parser = parser;
    }

    /**
     * 从 SQL 文本提取字段级血缘。
     *
     * @param sql     SQL 文本
     * @param dialect 方言；{@code null} 时自动检测
     * @return 字段级血缘关系列表
     * @throws SqlParseException 解析失败
     */
    public List<LineageRelation> extract(String sql, SqlDialect dialect) {
        if (sql == null || sql.isBlank()) {
            return Collections.emptyList();
        }
        ASTNode ast = dialect == null ? parser.parseAuto(sql) : parser.parse(sql, dialect);
        return extractFromAst(ast, sql, dialect);
    }

    /**
     * 从已解析的 AST 提取字段级血缘。
     *
     * @param ast     AST 根节点
     * @param sql     原始 SQL
     * @param dialect 方言
     * @return 字段级血缘关系列表
     */
    public List<LineageRelation> extractFromAst(ASTNode ast, String sql, SqlDialect dialect) {
        if (ast == null) {
            return Collections.emptyList();
        }
        List<LineageRelation> relations = new ArrayList<>();
        String dialectName = dialect == null ? "AUTO" : dialect.name();

        for (ASTNode child : ast.getChildren()) {
            collectFromStatement(child, relations, sql, dialectName);
        }
        return relations;
    }

    private void collectFromStatement(ASTNode stmt, List<LineageRelation> relations,
                                      String sql, String dialectName) {
        if (stmt == null) {
            return;
        }
        switch (stmt.getType()) {
            case INSERT -> handleInsert(stmt, relations, sql, dialectName);
            case CREATE_TABLE -> handleCreateTableAsSelect(stmt, relations, sql, dialectName);
            case SELECT, UNION -> handlePureSelect(stmt, relations, sql, dialectName);
            default -> {
            }
        }
    }

    /**
     * 处理 INSERT 语句的字段级血缘。
     */
    private void handleInsert(ASTNode insert, List<LineageRelation> relations,
                              String sql, String dialectName) {
        String targetTable = insert.getString("table");
        if (targetTable == null || targetTable.isEmpty()) {
            return;
        }
        if (!"SELECT".equalsIgnoreCase(insert.getString("mode"))) {
            return;
        }
        // 目标列清单（INSERT INTO t(c1, c2) ...）
        List<String> targetColumns = insert.getStringList("columns");

        // 找到内嵌 SELECT
        ASTNode select = insert.findChild(ASTNode.NodeType.SELECT);
        if (select == null) {
            // 可能是 UNION
            ASTNode union = insert.findChild(ASTNode.NodeType.UNION);
            if (union != null) {
                select = union;
            }
        }
        if (select == null) {
            return;
        }
        mapSelectToTarget(select, targetTable, targetColumns, relations, sql, dialectName);
    }

    /**
     * 处理 CREATE TABLE AS SELECT。
     */
    private void handleCreateTableAsSelect(ASTNode create, List<LineageRelation> relations,
                                           String sql, String dialectName) {
        String targetTable = create.getString("table");
        if (targetTable == null || targetTable.isEmpty()) {
            return;
        }
        List<ASTNode> selects = create.findAll(ASTNode.NodeType.SELECT);
        if (selects.isEmpty()) {
            return;
        }
        List<String> targetColumns = create.getStringList("columns");
        mapSelectToTarget(selects.get(0), targetTable, targetColumns, relations, sql, dialectName);
    }

    /**
     * 处理纯 SELECT（虚拟目标 result）。
     */
    private void handlePureSelect(ASTNode select, List<LineageRelation> relations,
                                  String sql, String dialectName) {
        mapSelectToTarget(select, VIRTUAL_TARGET, Collections.emptyList(), relations, sql, dialectName);
    }

    /**
     * 将 SELECT 列表的列引用映射到目标表字段。
     *
     * @param select        SELECT 节点
     * @param targetTable   目标表名
     * @param targetColumns 目标列清单（可为空，按位置/源列名推导）
     * @param relations     输出血缘关系
     * @param sql           源 SQL
     * @param dialectName   方言名
     */
    private void mapSelectToTarget(ASTNode select, String targetTable, List<String> targetColumns,
                                   List<LineageRelation> relations,
                                   String sql, String dialectName) {
        // UNION：分别处理每个分支，目标列清单共享
        if (select.getType() == ASTNode.NodeType.UNION) {
            for (ASTNode child : select.getChildren()) {
                mapSelectToTarget(child, targetTable, targetColumns, relations, sql, dialectName);
            }
            return;
        }
        if (select.getType() != ASTNode.NodeType.SELECT) {
            return;
        }

        // 构建 FROM 中别名 → 表名 映射
        Map<String, String> aliasToTable = buildAliasMap(select);
        // 收集 SELECT 列表的 COLUMN 子节点
        List<ASTNode> selectColumns = new ArrayList<>();
        for (ASTNode child : select.getChildren()) {
            if (child.getType() == ASTNode.NodeType.COLUMN) {
                selectColumns.add(child);
            }
        }

        for (int i = 0; i < selectColumns.size(); i++) {
            ASTNode colNode = selectColumns.get(i);
            String expression = colNode.getString("expression");
            String colName = colNode.getString("name");
            String alias = colNode.getString("alias");

            // 目标列名：优先使用 INSERT 列清单；其次使用 SELECT 别名；最后用源列名
            String targetCol;
            if (!targetColumns.isEmpty() && i < targetColumns.size()) {
                targetCol = targetColumns.get(i);
            } else if (alias != null && !alias.isEmpty()) {
                targetCol = alias;
            } else if (colName != null && !"*".equals(colName)) {
                // colName 可能是 "a . x" 或 "a.x" 形式，取最后一段作为目标列名
                targetCol = shortName(normalizeDot(colName));
            } else {
                targetCol = "*";
            }

            // 从表达式提取源列引用（形如 a.x 或 table.column）
            List<ColumnRef> refs = extractColumnRefs(expression, aliasToTable);
            if (refs.isEmpty() && colName != null && !"*".equals(colName)) {
                // 简单列引用：expression 可能就是列名
                refs = extractColumnRefs(colName, aliasToTable);
            }
            if (refs.isEmpty()) {
                // 无法识别源（常量、函数无列参数等）：跳过
                continue;
            }

            String targetFullName = targetTable + "." + targetCol;
            for (ColumnRef ref : refs) {
                String sourceFullName = ref.table + "." + ref.column;
                if (sourceFullName.equalsIgnoreCase(targetFullName)) {
                    continue;
                }
                relations.add(new LineageRelation(sourceFullName, targetFullName,
                        LineageRelation.RelationType.COLUMN_LINEAGE,
                        expression, sql, dialectName));
            }
        }
    }

    /**
     * 构建 SELECT 中 FROM/JOIN 的别名 → 真实表名 映射。
     */
    private Map<String, String> buildAliasMap(ASTNode select) {
        Map<String, String> map = new LinkedHashMap<>();
        ASTNode from = select.findChild(ASTNode.NodeType.FROM);
        if (from == null) {
            return map;
        }
        for (ASTNode child : from.getChildren()) {
            collectAlias(child, map);
        }
        return map;
    }

    private void collectAlias(ASTNode node, Map<String, String> map) {
        if (node == null) {
            return;
        }
        if (node.getType() == ASTNode.NodeType.TABLE) {
            String name = node.getString("name");
            String alias = node.getString("alias");
            if (name != null) {
                // 表名本身作为键（允许 table.column 形式）
                String shortName = shortName(name);
                map.putIfAbsent(shortName, name);
                map.putIfAbsent(name, name);
                if (alias != null && !alias.isEmpty()) {
                    map.putIfAbsent(alias, name);
                }
            }
            return;
        }
        if (node.getType() == ASTNode.NodeType.JOIN) {
            for (ASTNode child : node.getChildren()) {
                collectAlias(child, map);
            }
            return;
        }
        if (node.getType() == ASTNode.NodeType.SUBQUERY) {
            String alias = node.getString("alias");
            // 子查询别名映射到自身（字段引用以子查询别名作表名）
            if (alias != null && !alias.isEmpty()) {
                map.putIfAbsent(alias, alias);
            }
        }
    }

    /**
     * 从表达式文本提取列引用。
     *
     * <p>识别形如 {@code alias.column} 或 {@code table.column} 的 token。
     * 单独的裸列名（无前缀）尝试用唯一表别名补全；无法确定时跳过。</p>
     *
     * <p>注意：SQL 解析器产出的 expression 在 token 间可能含空格，如 {@code "a . x"}，
     * 此方法先规范化：去除 {@code .} 周围空格，再按非标识符字符分割。</p>
     *
     * @param expression   表达式文本
     * @param aliasToTable 别名映射
     * @return 列引用列表
     */
    private List<ColumnRef> extractColumnRefs(String expression, Map<String, String> aliasToTable) {
        List<ColumnRef> refs = new ArrayList<>();
        if (expression == null || expression.isBlank()) {
            return refs;
        }
        // 规范化：去掉 '.' 周围的空格，使 "a . x" → "a.x"
        String normalized = expression.replaceAll("\\s*\\.\\s*", ".");
        // 用非标识符字符分割（保留 '.' 以识别 a.b 形式）
        String[] tokens = normalized.split("[^\\w.]+");
        for (String token : tokens) {
            if (token.isEmpty() || token.equals(".") || token.startsWith(".")) {
                continue;
            }
            int dot = token.indexOf('.');
            if (dot > 0 && dot < token.length() - 1) {
                String prefix = token.substring(0, dot);
                String col = token.substring(dot + 1);
                if (isIdentifier(prefix) && isIdentifier(col)) {
                    String realTable = aliasToTable.get(prefix);
                    if (realTable == null) {
                        // 别名未找到：保留原 prefix（可能是 db.table 形式被拆错）
                        realTable = prefix;
                    }
                    refs.add(new ColumnRef(realTable, col));
                }
            }
        }
        // 处理裸列名：若 FROM 仅一张唯一表，则将该列归到该表
        if (refs.isEmpty()) {
            // 去重 aliasToTable 的值集合，判断是否仅一张唯一表
            Set<String> uniqueTables = new LinkedHashSet<>(aliasToTable.values());
            if (uniqueTables.size() == 1) {
                String onlyTable = uniqueTables.iterator().next();
                for (String token : tokens) {
                    if (isIdentifier(token) && !token.equals(onlyTable)
                            && !shortName(onlyTable).equals(token)
                            && !isKeyword(token) && !isNumeric(token)) {
                        refs.add(new ColumnRef(onlyTable, token));
                    }
                }
            }
        }
        return refs;
    }

    /**
     * 取表名的最后一段（去掉 db. 前缀），用于别名匹配。
     */
    private String shortName(String fullName) {
        int dot = fullName.lastIndexOf('.');
        return dot >= 0 ? fullName.substring(dot + 1) : fullName;
    }

    /**
     * 规范化表达式中的点号：去除 '.' 周围空格，使 "a . x" → "a.x"。
     *
     * @param expr 原始表达式
     * @return 规范化后的表达式
     */
    private String normalizeDot(String expr) {
        return expr == null ? null : expr.replaceAll("\\s*\\.\\s*", ".");
    }

    private boolean isIdentifier(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (!Character.isLetter(s.charAt(0)) && s.charAt(0) != '_') {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    private boolean isKeyword(String s) {
        return switch (s.toUpperCase()) {
            case "SELECT", "FROM", "WHERE", "JOIN", "ON", "AND", "OR", "NOT",
                 "AS", "BY", "GROUP", "ORDER", "HAVING", "LIMIT", "INSERT",
                 "INTO", "VALUES", "CREATE", "TABLE", "DROP", "ALTER", "UNION",
                 "ALL", "DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END",
                 "NULL", "TRUE", "FALSE", "IN", "BETWEEN", "LIKE", "IS" -> true;
            default -> false;
        };
    }

    private boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 列引用内部表示 */
    private static final class ColumnRef {
        final String table;
        final String column;

        ColumnRef(String table, String column) {
            this.table = table;
            this.column = column;
        }
    }
}