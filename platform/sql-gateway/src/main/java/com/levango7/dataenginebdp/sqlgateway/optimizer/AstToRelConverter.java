package com.levango7.dataenginebdp.sqlgateway.optimizer;

import com.levango7.dataenginebdp.sqlgateway.parser.ASTNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AST → RelNode 转换器——对应 Calcite 的 {@code SqlToRelConverter}。
 *
 * <p>将 {@link ASTNode}（语法树）转换为 {@link RelNode}（关系代数树），
 * 转换规则遵循关系代数经典映射：
 * <ul>
 *   <li>FROM 表 → {@link RelNode.Op#TABLE_SCAN}</li>
 *   <li>WHERE → {@link RelNode.Op#FILTER}（σ）</li>
 *   <li>SELECT 列 → {@link RelNode.Op#PROJECT}（π）</li>
 *   <li>JOIN → {@link RelNode.Op#JOIN}（⋈）</li>
 *   <li>GROUP BY + 聚合 → {@link RelNode.Op#AGGREGATE}（γ）</li>
 *   <li>ORDER BY → {@link RelNode.Op#SORT}（τ）</li>
 *   <li>LIMIT → {@link RelNode.Op#LIMIT}</li>
 *   <li>UNION → {@link RelNode.Op#UNION}（∪）</li>
 *   <li>子查询 → {@link RelNode.Op#SUBQUERY}</li>
 * </ul>
 * </p>
 *
 * <p>关系代数树自底向上构建：Scan → Filter → Join → Aggregate → Project → Sort → Limit。
 * 这与 Calcite SqlToRelConverter 的输出结构一致，便于后续 HepPlanner 优化。</p>
 *
 * @author shuqing-bigdata
 */
public class AstToRelConverter {

    /**
     * 将 AST 根节点转换为 RelNode 树。
     *
     * @param ast AST 根节点（类型为 STATEMENT 或 SELECT/UNION）
     * @return RelNode 根节点
     * @throws IllegalArgumentException AST 为空或不支持
     */
    public RelNode convert(ASTNode ast) {
        if (ast == null) {
            throw new IllegalArgumentException("AST 不能为空");
        }
        // STATEMENT 包装层：取第一个子节点
        ASTNode target = ast;
        if (ast.getType() == ASTNode.NodeType.STATEMENT) {
            if (ast.getChildren().isEmpty()) {
                throw new IllegalArgumentException("STATEMENT 无子节点");
            }
            target = ast.getChildren().get(0);
        }
        return convertStatement(target);
    }

    private RelNode convertStatement(ASTNode node) {
        switch (node.getType()) {
            case SELECT:
                return convertSelect(node);
            case UNION:
                return convertUnion(node);
            case CTE:
                return convertCte(node);
            case INSERT:
                return convertInsert(node);
            case CREATE_TABLE:
            case DROP:
            case ALTER:
            case DDL:
                return convertDdl(node);
            default:
                throw new IllegalArgumentException("不支持的 AST 节点类型: " + node.getType());
        }
    }

    /** SELECT → Project(Filter(Aggregate(Join(Scan)))) */
    private RelNode convertSelect(ASTNode select) {
        // 1. FROM → Scan + Join（最底层）
        RelNode input = null;
        ASTNode from = select.findChild(ASTNode.NodeType.FROM);
        if (from != null) {
            input = convertFrom(from);
        } else {
            // SELECT 无 FROM（如 SELECT 1）→ VALUES
            input = RelNode.of(RelNode.Op.VALUES).setRemark("无 FROM 子句");
        }

        // 2. WHERE → Filter
        ASTNode where = select.findChild(ASTNode.NodeType.WHERE);
        if (where != null) {
            String cond = where.getString("condition");
            RelNode filter = RelNode.of(RelNode.Op.FILTER).setCondition(cond);
            filter.addChild(input);
            input = filter;
        }

        // 3. GROUP BY → Aggregate
        ASTNode groupBy = select.findChild(ASTNode.NodeType.GROUP_BY);
        if (groupBy != null) {
            RelNode agg = RelNode.of(RelNode.Op.AGGREGATE);
            agg.setGroupKeys(groupBy.getStringList("columns"));
            agg.setAggFuncs(extractAggFuncs(select));
            agg.addChild(input);
            input = agg;
        }

        // 4. HAVING → Filter（作用于 Aggregate 之上）
        ASTNode having = select.findChild(ASTNode.NodeType.HAVING);
        if (having != null) {
            RelNode havingFilter = RelNode.of(RelNode.Op.FILTER)
                    .setCondition(having.getString("condition"))
                    .setRemark("HAVING");
            havingFilter.addChild(input);
            input = havingFilter;
        }

        // 5. SELECT 列 → Project
        List<String> columns = select.getStringList("columns");
        if (!columns.isEmpty() && !isSelectStarOnly(columns)) {
            RelNode project = RelNode.of(RelNode.Op.PROJECT).setProjects(columns);
            // DISTINCT 标记
            if (select.getProperties().get("distinct") != null) {
                project.setRemark("DISTINCT");
            }
            project.addChild(input);
            input = project;
        } else if (select.getProperties().get("distinct") != null) {
            // SELECT DISTINCT * → 仍需 Aggregate 去重
            RelNode dedup = RelNode.of(RelNode.Op.AGGREGATE).setRemark("DISTINCT");
            dedup.addChild(input);
            input = dedup;
        }

        // 6. ORDER BY → Sort
        ASTNode orderBy = select.findChild(ASTNode.NodeType.ORDER_BY);
        if (orderBy != null) {
            RelNode sort = RelNode.of(RelNode.Op.SORT).setSortKeys(orderBy.getStringList("columns"));
            sort.addChild(input);
            input = sort;
        }

        // 7. LIMIT → Limit
        ASTNode limit = select.findChild(ASTNode.NodeType.LIMIT);
        if (limit != null) {
            RelNode limitNode = RelNode.of(RelNode.Op.LIMIT);
            Object cnt = limit.getProperties().get("count");
            Object off = limit.getProperties().get("offset");
            if (cnt instanceof Number) {
                limitNode.setLimit(((Number) cnt).longValue());
            }
            if (off instanceof Number) {
                limitNode.setOffset(((Number) off).longValue());
            }
            limitNode.addChild(input);
            input = limitNode;
        }

        return input;
    }

    /** FROM → Scan (+ Join)* */
    private RelNode convertFrom(ASTNode from) {
        List<ASTNode> children = from.getChildren();
        if (children.isEmpty()) {
            return RelNode.of(RelNode.Op.VALUES);
        }
        // 第一个子节点是主表或子查询
        RelNode left = convertTableRef(children.get(0));
        // 后续子节点是 JOIN
        for (int i = 1; i < children.size(); i++) {
            ASTNode joinAst = children.get(i);
            if (joinAst.getType() == ASTNode.NodeType.JOIN) {
                left = convertJoin(left, joinAst);
            }
        }
        return left;
    }

    /** 表引用 / 子查询 → Scan / Subquery */
    private RelNode convertTableRef(ASTNode node) {
        if (node.getType() == ASTNode.NodeType.TABLE) {
            return RelNode.of(RelNode.Op.TABLE_SCAN)
                    .setTableName(node.getString("name"))
                    .setTableAlias(node.getString("alias"));
        }
        if (node.getType() == ASTNode.NodeType.SUBQUERY) {
            RelNode subquery = RelNode.of(RelNode.Op.SUBQUERY)
                    .setTableAlias(node.getString("alias"));
            // 子查询内容是第一个子节点
            if (!node.getChildren().isEmpty()) {
                subquery.addChild(convertStatement(node.getChildren().get(0)));
            }
            return subquery;
        }
        throw new IllegalArgumentException("FROM 子句不支持的节点: " + node.getType());
    }

    /** JOIN → Join(left, right, condition) */
    private RelNode convertJoin(RelNode left, ASTNode joinAst) {
        RelNode join = RelNode.of(RelNode.Op.JOIN);
        join.setJoinType(joinAst.getString("joinType"));
        join.setCondition(joinAst.getString("on"));
        join.addChild(left);
        if (!joinAst.getChildren().isEmpty()) {
            join.addChild(convertTableRef(joinAst.getChildren().get(0)));
        }
        return join;
    }

    /** UNION → Union(left, right) */
    private RelNode convertUnion(ASTNode union) {
        RelNode unionNode = RelNode.of(RelNode.Op.UNION);
        for (ASTNode child : union.getChildren()) {
            unionNode.addChild(convertStatement(child));
        }
        return unionNode;
    }

    /** CTE → 转换最后一个子节点（主查询），CTE 定义暂作标记 */
    private RelNode convertCte(ASTNode cte) {
        // CTE 的最后一个子节点是主查询
        if (cte.getChildren().isEmpty()) {
            return RelNode.of(RelNode.Op.VALUES).setRemark("空 CTE");
        }
        ASTNode mainQuery = cte.getChildren().get(cte.getChildren().size() - 1);
        return convertStatement(mainQuery);
    }

    /** INSERT → Project(Scan/Values) */
    private RelNode convertInsert(ASTNode insert) {
        RelNode project = RelNode.of(RelNode.Op.PROJECT);
        project.setTableName(insert.getString("table"));
        project.setProjects(insert.getStringList("columns"));
        project.setRemark("INSERT");

        String mode = insert.getString("mode");
        if ("SELECT".equals(mode) && !insert.getChildren().isEmpty()) {
            project.addChild(convertStatement(insert.getChildren().get(0)));
        } else if ("VALUES".equals(mode)) {
            RelNode values = RelNode.of(RelNode.Op.VALUES).setRemark("INSERT VALUES");
            project.addChild(values);
        }
        return project;
    }

    /** DDL → 标记节点 */
    private RelNode convertDdl(ASTNode ddl) {
        RelNode node = RelNode.of(RelNode.Op.VALUES);
        node.setTableName(ddl.getString("table"));
        node.setRemark("DDL: " + ddl.getType());
        return node;
    }

    /** 判断 SELECT 列是否仅为 * */
    private boolean isSelectStarOnly(List<String> columns) {
        return columns.size() == 1 && "*".equals(columns.get(0));
    }

    /** 从 SELECT 子节点中提取聚合函数表达式 */
    private List<String> extractAggFuncs(ASTNode select) {
        List<String> aggs = new ArrayList<>();
        for (ASTNode child : select.getChildren()) {
            if (child.getType() == ASTNode.NodeType.COLUMN) {
                String expr = child.getString("expression");
                if (expr != null && isAggregateFunction(expr)) {
                    aggs.add(expr);
                }
            }
        }
        return aggs;
    }

    /** 判断表达式是否为聚合函数调用 */
    private boolean isAggregateFunction(String expr) {
        if (expr == null) {
            return false;
        }
        String upper = expr.toUpperCase(Locale.ROOT);
        return upper.startsWith("COUNT(") || upper.startsWith("SUM(")
                || upper.startsWith("AVG(") || upper.startsWith("MIN(")
                || upper.startsWith("MAX(") || upper.startsWith("COUNT (")
                || upper.startsWith("SUM (") || upper.startsWith("AVG (")
                || upper.startsWith("MIN (") || upper.startsWith("MAX (");
    }
}