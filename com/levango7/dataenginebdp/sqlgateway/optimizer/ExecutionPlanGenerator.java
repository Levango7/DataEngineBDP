package com.shuqing.bigdata.sqlgateway.optimizer;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行计划生成器——对应 Calcite 的 {@code RelOptPlanImpl} + EXPLAIN 输出。
 *
 * <p>将 {@link RelNode} 树转换为类 EXPLAIN 的可读执行计划文本，包含：
 * <ul>
 *   <li>操作类型与缩进层级（树形结构）</li>
 *   <li>表名/别名</li>
 *   <li>谓词条件</li>
 *   <li>投影列/分组键/排序键</li>
 *   <li>估算行数与代价</li>
 *   <li>表访问顺序</li>
 * </ul>
 * </p>
 *
 * <p>输出格式示例：
 * <pre>
 * == Optimized Logical Plan ==
 * Limit(fetch=10)
 *   Sort(sort=[id DESC])
 *     Project(proj=[id, name])
 *       Filter(cond=[age > 18])
 *         TableScan(table=users)
 * == Table Access Order ==
 *   users
 * == Estimated Cost ==
 *   rows=100.0  cost=220.0
 * </pre>
 * </p>
 *
 * @author shuqing-bigdata
 */
public class ExecutionPlanGenerator {

    private static final DecimalFormat ROW_FMT = new DecimalFormat("#,##0.0");
    private static final DecimalFormat COST_FMT = new DecimalFormat("#,##0.0");

    /**
     * 生成完整执行计划文本。
     *
     * @param relNode RelNode 根节点
     * @return 执行计划文本
     */
    public String generate(RelNode relNode) {
        if (relNode == null) {
            return "== Empty Plan ==";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("== Optimized Logical Plan ==\n");
        appendNode(sb, relNode, 0);

        List<String> tables = relNode.collectTables();
        sb.append("\n== Table Access Order ==\n");
        if (tables.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (int i = 0; i < tables.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(tables.get(i)).append('\n');
            }
        }

        sb.append("\n== Estimated Cost ==\n");
        sb.append("  rows=").append(ROW_FMT.format(relNode.getEstimatedRows()))
                .append("  cost=").append(COST_FMT.format(relNode.getEstimatedCost())).append('\n');

        return sb.toString();
    }

    /**
     * 仅生成计划树部分（不含统计信息），用于 EXPLAIN 简化输出。
     *
     * @param relNode RelNode 根节点
     * @return 计划树文本
     */
    public String generatePlanTree(RelNode relNode) {
        if (relNode == null) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        appendNode(sb, relNode, 0);
        return sb.toString();
    }

    private void appendNode(StringBuilder sb, RelNode node, int depth) {
        String indent = "  ".repeat(depth);
        sb.append(indent).append(formatOp(node)).append('\n');
        for (RelNode child : node.getChildren()) {
            appendNode(sb, child, depth + 1);
        }
    }

    private String formatOp(RelNode node) {
        StringBuilder sb = new StringBuilder();
        switch (node.getOp()) {
            case TABLE_SCAN:
                sb.append("TableScan(table=").append(node.getTableName());
                if (node.getTableAlias() != null) {
                    sb.append(", AS=").append(node.getTableAlias());
                }
                sb.append(')');
                break;
            case FILTER:
                sb.append("Filter(cond=[").append(safe(node.getCondition())).append("])");
                break;
            case PROJECT:
                sb.append("Project(proj=").append(node.getProjects()).append(')');
                break;
            case JOIN:
                sb.append("Join(type=").append(safe(node.getJoinType()))
                        .append(", cond=[").append(safe(node.getCondition())).append("])");
                break;
            case AGGREGATE:
                sb.append("Aggregate(group=").append(node.getGroupKeys());
                if (!node.getAggFuncs().isEmpty()) {
                    sb.append(", aggs=").append(node.getAggFuncs());
                }
                sb.append(')');
                break;
            case SORT:
                sb.append("Sort(sort=").append(node.getSortKeys()).append(')');
                break;
            case LIMIT:
                sb.append("Limit(fetch=").append(node.getLimit());
                if (node.getOffset() >= 0) {
                    sb.append(", offset=").append(node.getOffset());
                }
                sb.append(')');
                break;
            case UNION:
                sb.append("Union");
                break;
            case VALUES:
                sb.append("Values");
                break;
            case SUBQUERY:
                sb.append("Subquery");
                if (node.getTableAlias() != null) {
                    sb.append("(AS=").append(node.getTableAlias()).append(')');
                }
                break;
            default:
                sb.append(node.getOp());
        }
        if (node.getRemark() != null) {
            sb.append("  // ").append(node.getRemark());
        }
        if (node.getEstimatedRows() > 0) {
            sb.append("  [rows=").append(ROW_FMT.format(node.getEstimatedRows())).append(']');
        }
        return sb.toString();
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 提取表访问顺序（深度优先）。
     *
     * @param relNode RelNode 根节点
     * @return 表名列表
     */
    public List<String> extractTableAccessOrder(RelNode relNode) {
        if (relNode == null) {
            return new ArrayList<>();
        }
        return relNode.collectTables();
    }
}