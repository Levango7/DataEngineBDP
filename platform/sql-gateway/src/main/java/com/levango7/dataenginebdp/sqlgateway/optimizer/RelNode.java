package com.levango7.dataenginebdp.sqlgateway.optimizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 关系代数节点（RelNode）—— Calcite {@code org.apache.calcite.rel.RelNode} 的简化实现。
 *
 * <p>本类是 AST 与执行计划之间的中间表示（IR），由 {@link AstToRelConverter} 从
 * {@code ASTNode} 转换而来，再由 {@code SqlOptimizerService} 应用优化规则改写，
 * 最终经 {@link ExecutionPlanGenerator} 输出可读执行计划。</p>
 *
 * <p>支持的节点类型对应 Calcite 的 LogicalXxx 系列：
 * <ul>
 *   <li>{@link #TABLE_SCAN}  → LogicalTableScan</li>
 *   <li>{@link #FILTER}     → LogicalFilter（σ）</li>
 *   <li>{@link #PROJECT}    → LogicalProject（π）</li>
 *   <li>{@link #JOIN}       → LogicalJoin（⋈）</li>
 *   <li>{@link #AGGREGATE}  → LogicalAggregate（γ）</li>
 *   <li>{@link #SORT}       → LogicalSort（τ）</li>
 *   <li>{@link #LIMIT}      → LogicalSort + Fetch</li>
 *   <li>{@link #UNION}      → LogicalUnion（∪）</li>
 *   <li>{@link #VALUES}     → LogicalValues</li>
 * </ul>
 * </p>
 *
 * @author shuqing-bigdata
 */
public class RelNode {

    /** 关系代数操作类型 */
    public enum Op {
        TABLE_SCAN, FILTER, PROJECT, JOIN, AGGREGATE, SORT, LIMIT, UNION, VALUES, SUBQUERY
    }

    private final Op op;
    private final List<RelNode> children;
    private String tableName;        // TABLE_SCAN：表名
    private String tableAlias;      // TABLE_SCAN：表别名
    private String condition;       // FILTER/JOIN：谓词条件
    private String joinType;        // JOIN：INNER/LEFT/RIGHT/FULL/CROSS
    private List<String> projects;  // PROJECT：投影列
    private List<String> groupKeys; // AGGREGATE：分组键
    private List<String> aggFuncs;  // AGGREGATE：聚合函数
    private List<String> sortKeys;  // SORT：排序键（含方向）
    private long limit = -1;        // LIMIT：行数
    private long offset = -1;       // LIMIT：偏移
    private double estimatedRows;   // 估算行数
    private double estimatedCost;   // 估算代价
    private String remark;          // 优化标记/来源

    public RelNode(Op op) {
        this.op = Objects.requireNonNull(op, "op");
        this.children = new ArrayList<>();
    }

    public static RelNode of(Op op) {
        return new RelNode(op);
    }

    public Op getOp() {
        return op;
    }

    public List<RelNode> getChildren() {
        return children;
    }

    public RelNode addChild(RelNode child) {
        if (child != null) {
            children.add(child);
        }
        return this;
    }

    public String getTableName() {
        return tableName;
    }

    public RelNode setTableName(String tableName) {
        this.tableName = tableName;
        return this;
    }

    public String getTableAlias() {
        return tableAlias;
    }

    public RelNode setTableAlias(String tableAlias) {
        this.tableAlias = tableAlias;
        return this;
    }

    public String getCondition() {
        return condition;
    }

    public RelNode setCondition(String condition) {
        this.condition = condition;
        return this;
    }

    public String getJoinType() {
        return joinType;
    }

    public RelNode setJoinType(String joinType) {
        this.joinType = joinType;
        return this;
    }

    public List<String> getProjects() {
        return projects == null ? Collections.emptyList() : projects;
    }

    public RelNode setProjects(List<String> projects) {
        this.projects = projects;
        return this;
    }

    public List<String> getGroupKeys() {
        return groupKeys == null ? Collections.emptyList() : groupKeys;
    }

    public RelNode setGroupKeys(List<String> groupKeys) {
        this.groupKeys = groupKeys;
        return this;
    }

    public List<String> getAggFuncs() {
        return aggFuncs == null ? Collections.emptyList() : aggFuncs;
    }

    public RelNode setAggFuncs(List<String> aggFuncs) {
        this.aggFuncs = aggFuncs;
        return this;
    }

    public List<String> getSortKeys() {
        return sortKeys == null ? Collections.emptyList() : sortKeys;
    }

    public RelNode setSortKeys(List<String> sortKeys) {
        this.sortKeys = sortKeys;
        return this;
    }

    public long getLimit() {
        return limit;
    }

    public RelNode setLimit(long limit) {
        this.limit = limit;
        return this;
    }

    public long getOffset() {
        return offset;
    }

    public RelNode setOffset(long offset) {
        this.offset = offset;
        return this;
    }

    public double getEstimatedRows() {
        return estimatedRows;
    }

    public RelNode setEstimatedRows(double estimatedRows) {
        this.estimatedRows = estimatedRows;
        return this;
    }

    public double getEstimatedCost() {
        return estimatedCost;
    }

    public RelNode setEstimatedCost(double estimatedCost) {
        this.estimatedCost = estimatedCost;
        return this;
    }

    public String getRemark() {
        return remark;
    }

    public RelNode setRemark(String remark) {
        this.remark = remark;
        return this;
    }

    /**
     * 收集该子树涉及的所有表名（用于表访问顺序分析）。
     *
     * @return 去重表名列表（深度优先顺序）
     */
    public List<String> collectTables() {
        Set<String> tables = new LinkedHashSet<>();
        collectTables(this, tables);
        return new ArrayList<>(tables);
    }

    private static void collectTables(RelNode node, Set<String> tables) {
        if (node == null) {
            return;
        }
        if (node.op == Op.TABLE_SCAN && node.tableName != null) {
            tables.add(node.tableName);
        }
        for (RelNode c : node.children) {
            collectTables(c, tables);
        }
    }

    /** 深度（叶子为 1） */
    public int depth() {
        if (children.isEmpty()) {
            return 1;
        }
        int max = 0;
        for (RelNode c : children) {
            max = Math.max(max, c.depth());
        }
        return max + 1;
    }

    @Override
    public String toString() {
        return toString(0);
    }

    private String toString(int indent) {
        StringBuilder sb = new StringBuilder();
        String pad = "  ".repeat(indent);
        sb.append(pad).append(op);
        if (tableName != null) {
            sb.append(" table=").append(tableName);
            if (tableAlias != null) {
                sb.append(" AS ").append(tableAlias);
            }
        }
        if (condition != null) {
            sb.append(" cond=[").append(condition).append("]");
        }
        if (joinType != null) {
            sb.append(" join=").append(joinType);
        }
        if (projects != null && !projects.isEmpty()) {
            sb.append(" proj=").append(projects);
        }
        if (groupKeys != null && !groupKeys.isEmpty()) {
            sb.append(" group=").append(groupKeys);
        }
        if (aggFuncs != null && !aggFuncs.isEmpty()) {
            sb.append(" agg=").append(aggFuncs);
        }
        if (sortKeys != null && !sortKeys.isEmpty()) {
            sb.append(" sort=").append(sortKeys);
        }
        if (limit >= 0) {
            sb.append(" limit=").append(limit);
            if (offset >= 0) {
                sb.append(" offset=").append(offset);
            }
        }
        if (remark != null) {
            sb.append(" // ").append(remark);
        }
        for (RelNode c : children) {
            sb.append('\n').append(c.toString(indent + 1));
        }
        return sb.toString();
    }
}