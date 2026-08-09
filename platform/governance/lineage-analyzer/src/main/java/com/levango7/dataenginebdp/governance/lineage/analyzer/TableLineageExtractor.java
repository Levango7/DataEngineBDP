package com.levango7.dataenginebdp.governance.lineage.analyzer;

import com.levango7.dataenginebdp.sqlgateway.parser.ASTNode;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlParseException;
import com.levango7.dataenginebdp.sqlgateway.parser.SqlParserService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 表级血缘提取器。
 *
 * <p>基于 SQL 解析器产出的 AST，识别数据从源表流向目标表的语句：
 * <ul>
 *   <li>{@code INSERT INTO target SELECT ... FROM source1 JOIN source2 ...}：
 *       产生 {@code source1 → target}、{@code source2 → target}</li>
 *   <li>{@code CREATE TABLE target AS SELECT ... FROM source}：
 *       产生 {@code source → target}</li>
 *   <li>{@code INSERT OVERWRITE TABLE target SELECT ... FROM source}：
 *       同 INSERT INTO</li>
 * </ul>
 * 纯 SELECT 语句无目标表，返回空列表。</p>
 *
 * @author shuqing-bigdata
 */
public class TableLineageExtractor {

    private final SqlParserService parser;

    /**
     * 构造提取器。
     *
     * @param parser SQL 解析器
     */
    public TableLineageExtractor(SqlParserService parser) {
        this.parser = parser;
    }

    /**
     * 从 SQL 文本提取表级血缘。
     *
     * @param sql     SQL 文本
     * @param dialect 方言；{@code null} 时自动检测
     * @return 血缘关系列表
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
     * 从已解析的 AST 提取表级血缘（避免重复解析）。
     *
     * @param ast    AST 根节点
     * @param sql    原始 SQL（用于记录）
     * @param dialect 方言
     * @return 血缘关系列表
     */
    public List<LineageRelation> extractFromAst(ASTNode ast, String sql, SqlDialect dialect) {
        if (ast == null) {
            return Collections.emptyList();
        }
        List<LineageRelation> relations = new ArrayList<>();
        String dialectName = dialect == null ? "AUTO" : dialect.name();

        // 遍历顶层语句的子节点（INSERT / CREATE_TABLE / SELECT 等）
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
            case CTE -> handleCte(stmt, relations, sql, dialectName);
            default -> {
                // 纯 SELECT 等无目标表的语句：不产生表级血缘
            }
        }
    }

    /**
     * 处理 INSERT 语句：目标表 = INSERT.table；源表 = 内嵌 SELECT 的 FROM 表。
     */
    private void handleInsert(ASTNode insert, List<LineageRelation> relations,
                              String sql, String dialectName) {
        String targetTable = insert.getString("table");
        if (targetTable == null || targetTable.isEmpty()) {
            return;
        }
        // 仅 SELECT 模式产生血缘；VALUES 模式无源表
        String mode = insert.getString("mode");
        if (!"SELECT".equalsIgnoreCase(mode)) {
            return;
        }
        List<String> sourceTables = collectSelectSources(insert);
        for (String source : sourceTables) {
            if (!source.equalsIgnoreCase(targetTable)) {
                relations.add(new LineageRelation(source, targetTable,
                        LineageRelation.RelationType.TABLE_LINEAGE, null, sql, dialectName));
            }
        }
    }

    /**
     * 处理 CREATE TABLE AS SELECT：当前 sql-gateway 解析器未显式支持 AS SELECT，
     * 但若 CREATE_TABLE 节点包含子 SELECT（未来扩展），则按 INSERT 逻辑处理。
     */
    private void handleCreateTableAsSelect(ASTNode create, List<LineageRelation> relations,
                                           String sql, String dialectName) {
        String targetTable = create.getString("table");
        if (targetTable == null || targetTable.isEmpty()) {
            return;
        }
        // 检查是否有子 SELECT 节点（CREATE TABLE t AS SELECT ...）
        List<ASTNode> selects = create.findAll(ASTNode.NodeType.SELECT);
        if (selects.isEmpty()) {
            return;
        }
        List<String> sourceTables = collectSelectSources(create);
        for (String source : sourceTables) {
            if (!source.equalsIgnoreCase(targetTable)) {
                relations.add(new LineageRelation(source, targetTable,
                        LineageRelation.RelationType.TABLE_LINEAGE, null, sql, dialectName));
            }
        }
    }

    /**
     * 处理 CTE（WITH ... SELECT）：CTE 内部子查询产生 CTE 名 → 源表 的血缘，
     * 但 CTE 名通常不作为持久化表，此处仅提取最终 SELECT 的源表（含 CTE 名）。
     */
    private void handleCte(ASTNode cte, List<LineageRelation> relations,
                           String sql, String dialectName) {
        // CTE 不直接产生表级血缘（CTE 名非持久化表），保持空实现
    }

    /**
     * 收集语句内所有 SELECT 的 FROM 表（含 JOIN、子查询递归）。
     *
     * @param stmt 语句节点
     * @return 源表全名列表（去重保序）
     */
    private List<String> collectSelectSources(ASTNode stmt) {
        List<String> sources = new ArrayList<>();
        List<ASTNode> froms = stmt.findAll(ASTNode.NodeType.FROM);
        for (ASTNode from : froms) {
            collectTablesFromFrom(from, sources);
        }
        return sources;
    }

    /**
     * 从 FROM 节点收集表名（含 JOIN 引入的表）。
     */
    private void collectTablesFromFrom(ASTNode from, List<String> sources) {
        for (ASTNode child : from.getChildren()) {
            collectTableReference(child, sources);
        }
    }

    /**
     * 递归收集表引用：TABLE 节点直接取 name；SUBQUERY 节点递归其内部 SELECT。
     */
    private void collectTableReference(ASTNode node, List<String> sources) {
        if (node == null) {
            return;
        }
        if (node.getType() == ASTNode.NodeType.TABLE) {
            String name = node.getString("name");
            if (name != null && !name.isEmpty() && !sources.contains(name)) {
                sources.add(name);
            }
            return;
        }
        if (node.getType() == ASTNode.NodeType.JOIN) {
            // JOIN 的第一个子节点是被 JOIN 的表引用
            for (ASTNode child : node.getChildren()) {
                collectTableReference(child, sources);
            }
            return;
        }
        if (node.getType() == ASTNode.NodeType.SUBQUERY) {
            // 子查询：递归内部 SELECT 的 FROM
            for (ASTNode child : node.getChildren()) {
                List<ASTNode> innerFroms = child.findAll(ASTNode.NodeType.FROM);
                for (ASTNode innerFrom : innerFroms) {
                    collectTablesFromFrom(innerFrom, sources);
                }
            }
        }
    }
}