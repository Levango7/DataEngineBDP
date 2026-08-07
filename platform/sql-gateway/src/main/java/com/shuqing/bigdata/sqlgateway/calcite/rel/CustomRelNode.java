package com.shuqing.bigdata.sqlgateway.calcite.rel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * RelNode 扩展类——在 Calcite 原生 {@code org.apache.calcite.rel.RelNode} 之上
 * 增加联邦查询所需的跨源标记与下推标注。
 *
 * <p>本类是 {@code CalciteOptimizer} 在联邦优化过程中使用的 IR 节点，每个节点
 * 携带以下扩展信息：</p>
 * <ul>
 *   <li>{@link #sourceName}：数据源标识，用于跨源标记（同一子树内多源即联邦）</li>
 *   <li>{@link #pushDownStatus}：下推状态（已下推/未下推/部分下推）</li>
 *   <li>{@link #pushDownReason}：未下推原因（如"谓词引用跨源列"）</li>
 *   <li>{@link #pushedOperations}：已下推的操作列表（filter/project/limit/agg）</li>
 * </ul>
 *
 * <p>跨源标记算法：自底向上传播 {@code sourceName}，当某节点的子节点来自不同源时，
 * 该节点被标记为联邦节点（{@link #isFederated()}）。</p>
 *
 * @author shuqing-bigdata
 */
public class CustomRelNode {

    /** 下推状态枚举 */
    public enum PushDownStatus {
        /** 未下推（保留在联邦层执行） */
        NOT_PUSHED,
        /** 已完全下推到数据源 */
        PUSHED,
        /** 部分下推（如谓词中部分条件下推，部分保留） */
        PARTIALLY_PUSHED,
        /** 不适用下推（如跨源 Join 节点本身） */
        NOT_APPLICABLE
    }

    /** 关系代数操作类型（与 optimizer.RelNode.Op 对齐） */
    public enum Op {
        TABLE_SCAN, FILTER, PROJECT, JOIN, AGGREGATE, SORT, LIMIT, UNION, VALUES
    }

    private final Op op;
    private final List<CustomRelNode> children = new ArrayList<>();

    /** 表名（TABLE_SCAN 用） */
    private String tableName;
    /** 数据源标识名（对应 DataSourceConfig.name） */
    private String sourceName;
    /** 谓词条件（FILTER/JOIN 用） */
    private String condition;
    /** 投影列（PROJECT 用） */
    private List<String> projects;
    /** 下推状态 */
    private PushDownStatus pushDownStatus = PushDownStatus.NOT_PUSHED;
    /** 未下推原因 */
    private String pushDownReason;
    /** 已下推操作列表 */
    private List<String> pushedOperations = new ArrayList<>();
    /** 估算行数 */
    private double estimatedRows;
    /** 估算代价 */
    private double estimatedCost;
    /** 备注 */
    private String remark;

    public CustomRelNode(Op op) {
        this.op = Objects.requireNonNull(op, "op");
    }

    public static CustomRelNode of(Op op) {
        return new CustomRelNode(op);
    }

    public Op getOp() {
        return op;
    }

    public List<CustomRelNode> getChildren() {
        return children;
    }

    public CustomRelNode addChild(CustomRelNode child) {
        if (child != null) {
            children.add(child);
        }
        return this;
    }

    public String getTableName() {
        return tableName;
    }

    public CustomRelNode setTableName(String tableName) {
        this.tableName = tableName;
        return this;
    }

    public String getSourceName() {
        return sourceName;
    }

    public CustomRelNode setSourceName(String sourceName) {
        this.sourceName = sourceName;
        return this;
    }

    public String getCondition() {
        return condition;
    }

    public CustomRelNode setCondition(String condition) {
        this.condition = condition;
        return this;
    }

    public List<String> getProjects() {
        return projects == null ? Collections.emptyList() : projects;
    }

    public CustomRelNode setProjects(List<String> projects) {
        this.projects = projects;
        return this;
    }

    public PushDownStatus getPushDownStatus() {
        return pushDownStatus;
    }

    public CustomRelNode setPushDownStatus(PushDownStatus pushDownStatus) {
        this.pushDownStatus = Objects.requireNonNull(pushDownStatus);
        return this;
    }

    public String getPushDownReason() {
        return pushDownReason;
    }

    public CustomRelNode setPushDownReason(String pushDownReason) {
        this.pushDownReason = pushDownReason;
        return this;
    }

    public List<String> getPushedOperations() {
        return pushedOperations == null ? Collections.emptyList() : pushedOperations;
    }

    public CustomRelNode setPushedOperations(List<String> pushedOperations) {
        this.pushedOperations = pushedOperations == null ? new ArrayList<>() : pushedOperations;
        return this;
    }

    public CustomRelNode addPushedOperation(String operation) {
        if (this.pushedOperations == null) {
            this.pushedOperations = new ArrayList<>();
        }
        this.pushedOperations.add(operation);
        return this;
    }

    public double getEstimatedRows() {
        return estimatedRows;
    }

    public CustomRelNode setEstimatedRows(double estimatedRows) {
        this.estimatedRows = estimatedRows;
        return this;
    }

    public double getEstimatedCost() {
        return estimatedCost;
    }

    public CustomRelNode setEstimatedCost(double estimatedCost) {
        this.estimatedCost = estimatedCost;
        return this;
    }

    public String getRemark() {
        return remark;
    }

    public CustomRelNode setRemark(String remark) {
        this.remark = remark;
        return this;
    }

    /**
     * 判断该子树是否为联邦查询（含多个不同数据源）。
     *
     * @return {@code true} 表示子树跨源
     */
    public boolean isFederated() {
        Set<String> sources = new LinkedHashSet<>();
        collectSources(this, sources);
        sources.remove(null);
        return sources.size() > 1;
    }

    private static void collectSources(CustomRelNode node, Set<String> sources) {
        if (node == null) {
            return;
        }
        if (node.sourceName != null) {
            sources.add(node.sourceName);
        }
        for (CustomRelNode c : node.children) {
            collectSources(c, sources);
        }
    }

    /**
     * 收集子树涉及的所有数据源名。
     *
     * @return 数据源名集合（去重、保序）
     */
    public Set<String> collectSourceNames() {
        Set<String> sources = new LinkedHashSet<>();
        collectSources(this, sources);
        sources.remove(null);
        return sources;
    }

    /**
     * 收集子树涉及的所有表名。
     *
     * @return 表名集合（去重、保序）
     */
    public List<String> collectTableNames() {
        Set<String> tables = new LinkedHashSet<>();
        collectTables(this, tables);
        return new ArrayList<>(tables);
    }

    private static void collectTables(CustomRelNode node, Set<String> tables) {
        if (node == null) {
            return;
        }
        if (node.op == Op.TABLE_SCAN && node.tableName != null) {
            tables.add(node.tableName);
        }
        for (CustomRelNode c : node.children) {
            collectTables(c, tables);
        }
    }

    /**
     * 标记节点为已下推。
     *
     * @param operation 下推的操作名（如 "filter: age>18"）
     * @return 当前节点
     */
    public CustomRelNode markPushed(String operation) {
        this.pushDownStatus = PushDownStatus.PUSHED;
        addPushedOperation(operation);
        return this;
    }

    /**
     * 标记节点未下推并记录原因。
     *
     * @param reason 未下推原因
     * @return 当前节点
     */
    public CustomRelNode markNotPushed(String reason) {
        this.pushDownStatus = PushDownStatus.NOT_PUSHED;
        this.pushDownReason = reason;
        return this;
    }

    /** 子树深度（叶子为 1） */
    public int depth() {
        if (children.isEmpty()) {
            return 1;
        }
        int max = 0;
        for (CustomRelNode c : children) {
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
        }
        if (sourceName != null) {
            sb.append(" source=").append(sourceName);
        }
        if (condition != null) {
            sb.append(" cond=[").append(condition).append("]");
        }
        if (projects != null && !projects.isEmpty()) {
            sb.append(" proj=").append(projects);
        }
        sb.append(" pushDown=").append(pushDownStatus);
        if (pushDownReason != null) {
            sb.append(" reason=").append(pushDownReason);
        }
        if (pushedOperations != null && !pushedOperations.isEmpty()) {
            sb.append(" pushed=").append(pushedOperations);
        }
        if (remark != null) {
            sb.append(" // ").append(remark);
        }
        for (CustomRelNode c : children) {
            sb.append('\n').append(c.toString(indent + 1));
        }
        return sb.toString();
    }
}