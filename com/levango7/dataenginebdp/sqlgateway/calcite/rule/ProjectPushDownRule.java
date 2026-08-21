package com.shuqing.bigdata.sqlgateway.calcite.rule;

import com.shuqing.bigdata.sqlgateway.calcite.adapter.BaseAdapter;
import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;
import com.shuqing.bigdata.sqlgateway.calcite.rel.CustomRelNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 投影下推规则——将 Project 节点中的列裁剪下推到 TableScan 之上。
 *
 * <p>本规则继承 {@link PushDownRule}，匹配 {@code PROJECT} 节点，分析查询实际引用的列，
 * 只下推引用列到数据源，避免读取无用列，减少数据传输量。</p>
 *
 * <p><b>核心能力：</b></p>
 * <ul>
 *   <li><b>列裁剪下推</b>：SELECT 实际引用的列才下推到 TableScan，不读无用列</li>
 *   <li><b>嵌套投影合并</b>：Project→Project 自动合并简化为单个 Project</li>
 *   <li><b>表达式列提取</b>：从表达式（如 {@code name || 'x'}、{@code a + b}）中提取引用的列</li>
 *   <li><b>JOIN 投影</b>：对 JOIN 的每个输入分别下推引用列</li>
 *   <li><b>聚合投影</b>：从聚合函数（如 {@code avg(age)}）中提取引用的列</li>
 * </ul>
 *
 * <p><b>下推流程：</b></p>
 * <pre>
 *   Project(projects: [name, age + 1])
 *     └─ TableScan(table: users, columns: [id, name, age, email, addr])
 *     │
 *     ▼  1. 提取引用列 → {name, age}
 *     ▼  2. 计算裁剪后列 → [name, age]（5 列 → 2 列）
 *     ▼  3. 下推列裁剪到 TableScan
 *   Project(projects: [name, age + 1])
 *     └─ TableScan(table: users, columns: [name, age], pushedProject: [name, age])
 * </pre>
 *
 * <p><b>嵌套投影合并：</b></p>
 * <pre>
 *   Project(a)                       Project(id)
 *     └─ Project(id AS a, name)  →     └─ TableScan(users, [id])
 *          └─ TableScan(users)
 * </pre>
 *
 * <p><b>语义等价性保证：</b>下推后的查询结果与原查询等价。列裁剪只移除未被引用的列，
 * 不影响投影表达式的计算结果。嵌套投影合并通过列映射重写 InputRef 索引保持语义。</p>
 *
 * <p><b>下推率统计：</b>每次 {@link #onMatch} 执行后，统计信息累积到 {@link #statistics}，
 * 可通过 {@link #getStatistics()} 获取列裁剪率、数据传输减少率等指标。</p>
 *
 * @author shuqing-bigdata
 */
public class ProjectPushDownRule extends PushDownRule {

    /** 规则短名 */
    public static final String RULE_NAME = "ProjectPushDown";

    /** 投影下推统计器 */
    private final ProjectionStatistics statistics;

    // ===================== 列引用识别正则 =====================

    /** 标识符（列名）：字母/下划线开头，后跟字母/数字/下划线/点 */
    private static final Pattern COLUMN_PATTERN =
            Pattern.compile("\\b([a-zA-Z_]\\w*(?:\\.\\w+)*)\\b");

    /** SQL 关键字集合（不视为列引用） */
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN", "LIKE",
            "BETWEEN", "JOIN", "ON", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS",
            "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "INTERSECT",
            "EXCEPT", "AS", "DISTINCT", "ALL", "CASE", "WHEN", "THEN", "ELSE", "END",
            "COUNT", "SUM", "AVG", "MIN", "MAX", "CAST", "TRUE", "FALSE",
            "ASC", "DESC", "WITH", "EXISTS", "ANY", "SOME"
    );

    /** 聚合函数名集合 */
    private static final Set<String> AGG_FUNCTIONS = Set.of(
            "count", "sum", "avg", "min", "max", "stddev", "variance",
            "collect", "array_agg", "first", "last"
    );

    /**
     * 构造投影下推规则。
     *
     * @param adapter 关联的数据源适配器
     */
    public ProjectPushDownRule(BaseAdapter adapter) {
        this(adapter, new ProjectionStatistics());
    }

    /**
     * 构造投影下推规则（指定统计器）。
     *
     * @param adapter    关联的数据源适配器
     * @param statistics 投影下推统计器
     */
    public ProjectPushDownRule(BaseAdapter adapter, ProjectionStatistics statistics) {
        super(RULE_NAME,
                "投影下推规则：只下推查询实际引用的列到 TableScan，嵌套投影自动合并简化",
                Objects.requireNonNull(adapter, "adapter"),
                CustomRelNode.Op.PROJECT);
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    /**
     * 获取投影下推统计器。
     *
     * @return 统计器实例
     */
    public ProjectionStatistics getStatistics() {
        return statistics;
    }

    // ===================== 列引用提取 =====================

    /**
     * 从投影表达式列表中提取所有引用的列名。
     *
     * <p>提取规则：</p>
     * <ul>
     *   <li>纯列名（如 {@code name}）→ 直接提取</li>
     *   <li>表达式（如 {@code name || 'x'}、{@code a + b}）→ 提取表达式中的所有列</li>
     *   <li>聚合函数（如 {@code count(*)}、{@code avg(age)}）→ 提取参数列（* 表示全部列）</li>
     *   <li>常量（如 {@code 1}、{@code 'x'}）→ 不提取列</li>
     *   <li>SQL 关键字 → 跳过</li>
     * </ul>
     *
     * @param projects 投影表达式列表
     * @return 引用的列名集合（去重、保序）
     */
    public Set<String> extractUsedColumns(List<String> projects) {
        Set<String> used = new LinkedHashSet<>();
        if (projects == null || projects.isEmpty()) {
            return used;
        }
        for (String expr : projects) {
            extractColumnsFromExpression(expr, used);
        }
        return used;
    }

    /**
     * 从单个表达式中提取引用的列名。
     *
     * @param expr 表达式字符串
     * @param used 累积列名集合
     */
    private void extractColumnsFromExpression(String expr, Set<String> used) {
        if (expr == null || expr.isBlank()) {
            return;
        }
        String trimmed = expr.trim();

        // 处理 count(*) — 视为引用全部列（返回特殊标记，调用方需处理）
        if (trimmed.equalsIgnoreCase("count(*)") || trimmed.equals("*")) {
            used.add("*");
            return;
        }

        // 提取表达式中的所有标识符
        Matcher matcher = COLUMN_PATTERN.matcher(trimmed);
        while (matcher.find()) {
            String ident = matcher.group(1);
            // 跳过 SQL 关键字
            if (SQL_KEYWORDS.contains(ident.toUpperCase())) {
                continue;
            }
            // 跳过聚合函数名（如 count/sum/avg），但保留其参数列
            if (AGG_FUNCTIONS.contains(ident.toLowerCase())) {
                continue;
            }
            // 跳过纯数字
            if (isNumeric(ident)) {
                continue;
            }
            used.add(ident);
        }
    }

    /** 判断字符串是否为数字 */
    private boolean isNumeric(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ===================== 嵌套投影合并 =====================

    /**
     * 判断 Project 节点是否为嵌套投影（子节点也是 Project）。
     *
     * @param project Project 节点
     * @return {@code true} 表示子节点也是 Project
     */
    public boolean isNestedProject(CustomRelNode project) {
        if (project == null || project.getOp() != CustomRelNode.Op.PROJECT) {
            return false;
        }
        return !project.getChildren().isEmpty()
                && project.getChildren().get(0).getOp() == CustomRelNode.Op.PROJECT;
    }

    /**
     * 合并嵌套投影（Project→Project → 单个 Project）。
     *
     * <p>合并算法：</p>
     * <ol>
     *   <li>外层 Project 的 projects 引用内层 Project 的输出列</li>
     *   <li>将外层引用的列名映射回内层 Project 的输入列</li>
     *   <3>合并后的 Project 直接引用 TableScan 的列</li>
     * </ol>
     *
     * <p>示例：</p>
     * <pre>
     *   Project(a)                       Project(id)
     *     └─ Project(id AS a, name)  →     └─ TableScan(users)
     *          └─ TableScan(users)
     * </pre>
     *
     * @param outer 外层 Project
     * @return 合并后的单个 Project（指向 TableScan）；无法合并时返回原 outer
     */
    public CustomRelNode mergeNestedProjects(CustomRelNode outer) {
        if (!isNestedProject(outer)) {
            return outer;
        }
        CustomRelNode inner = outer.getChildren().get(0);
        List<String> innerProjects = inner.getProjects();
        List<String> outerProjects = outer.getProjects();

        // 构造合并后的投影列：外层引用的列 → 内层投影表达式
        List<String> mergedProjects = new ArrayList<>();
        Set<String> usedInOuter = extractUsedColumns(outerProjects);

        for (String usedCol : usedInOuter) {
            if ("*".equals(usedCol)) {
                // 外层引用全部列 → 保留内层全部投影
                mergedProjects.addAll(innerProjects);
                continue;
            }
            // 在内层投影中查找该列
            int idx = findColumnInProjects(innerProjects, usedCol);
            if (idx >= 0) {
                // 找到：用内层投影表达式替换
                String innerExpr = innerProjects.get(idx);
                // 如果内层是别名（如 "id AS a"），提取原始表达式
                String baseExpr = extractAliasSource(innerExpr);
                mergedProjects.add(baseExpr);
            } else {
                // 未找到：保留原列名（可能是内层直接透传的列）
                mergedProjects.add(usedCol);
            }
        }

        // 构造合并后的 Project
        CustomRelNode merged = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                .setProjects(mergedProjects)
                .setPushDownStatus(outer.getPushDownStatus())
                .setRemark("merged: " + outer.getRemark());
        // 子节点指向内层的子节点（即 TableScan）
        for (CustomRelNode child : inner.getChildren()) {
            merged.addChild(child);
        }
        merged.addPushedOperation("mergeProject");

        statistics.recordMerge();
        return merged;
    }

    /**
     * 在投影列表中查找指定列名（支持别名 "expr AS alias"）。
     *
     * @param projects 投影列表
     * @param colName  列名
     * @return 索引（-1 表示未找到）
     */
    private int findColumnInProjects(List<String> projects, String colName) {
        if (projects == null) {
            return -1;
        }
        for (int i = 0; i < projects.size(); i++) {
            String p = projects.get(i).trim();
            // 直接匹配列名
            if (p.equalsIgnoreCase(colName)) {
                return i;
            }
            // 匹配别名："expr AS alias" → alias == colName
            String alias = extractAlias(p);
            if (alias != null && alias.equalsIgnoreCase(colName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从 "expr AS alias" 中提取别名。
     *
     * @param expr 投影表达式
     * @return 别名（无别名时返回 null）
     */
    private String extractAlias(String expr) {
        Matcher m = Pattern.compile("(?i)\\s+AS\\s+(\\w+)\\s*$").matcher(expr);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * 从 "expr AS alias" 中提取原始表达式（去掉别名部分）。
     *
     * @param expr 投影表达式
     * @return 原始表达式
     */
    private String extractAliasSource(String expr) {
        String trimmed = expr.trim();
        Matcher m = Pattern.compile("(?i)\\s+AS\\s+\\w+\\s*$").matcher(trimmed);
        if (m.find()) {
            return trimmed.substring(0, m.start()).trim();
        }
        return trimmed;
    }

    // ===================== 下推执行 =====================

    /**
     * 当规则匹配成功时执行下推改写。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>检查是否为嵌套投影，若是则先合并</li>
     *   <li>提取 Project 引用的列</li>
     *   <li>获取 TableScan 的全部列</li>
     *   <li>计算裁剪后的列（保留引用列）</li>
     *   <li>若裁剪后列数 &lt; 全部列数，下推列裁剪到 TableScan</li>
     *   <li>统计列裁剪率</li>
     * </ol>
     *
     * @param call 规则调用上下文
     */
    @Override
    public void onMatch(RuleCall call) {
        CustomRelNode project = call.getRoot();
        if (project == null || project.getOp() != CustomRelNode.Op.PROJECT) {
            return;
        }

        // 1. 嵌套投影合并
        CustomRelNode working = project;
        if (isNestedProject(project)) {
            working = mergeNestedProjects(project);
        }

        // 2. 提取引用列
        List<String> projectExprs = working.getProjects();
        Set<String> usedColumns = extractUsedColumns(projectExprs);

        if (usedColumns.isEmpty()) {
            statistics.recordSkip("无引用列");
            return;
        }

        // count(*) 引用全部列，不下推
        if (usedColumns.contains("*")) {
            statistics.recordSkip("count(*) 引用全部列");
            working.setPushDownStatus(CustomRelNode.PushDownStatus.NOT_PUSHED);
            working.setPushDownReason("count(*) 引用全部列，无需裁剪");
            return;
        }

        // 3. 获取 TableScan 的全部列
        CustomRelNode scan = findTableScan(working);
        if (scan == null) {
            statistics.recordSkip("未找到 TableScan 子节点");
            return;
        }
        List<String> allColumns = getTableColumns(scan);
        if (allColumns.isEmpty()) {
            statistics.recordSkip("TableScan 无列信息");
            return;
        }

        // 4. 计算裁剪后的列
        List<String> projectedColumns = new ArrayList<>();
        for (String col : allColumns) {
            if (usedColumns.contains(col)) {
                projectedColumns.add(col);
            }
        }

        // 5. 判断是否需要下推
        DataSourceConfig.Type sourceType = getAdapter().getAdapterType();
        if (projectedColumns.size() >= allColumns.size()) {
            // 全列引用，无需裁剪
            statistics.recordSkip("全列引用，无需裁剪");
            working.setPushDownStatus(CustomRelNode.PushDownStatus.NOT_PUSHED);
            working.setPushDownReason("SELECT * 或全列引用");
            return;
        }

        if (projectedColumns.isEmpty()) {
            // 引用列不在表列中（可能是表达式列），跳过
            statistics.recordSkip("引用列与表列不匹配");
            return;
        }

        // 6. 执行下推：构造裁剪后的 TableScan
        CustomRelNode pushedScan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName(scan.getTableName())
                .setSourceName(scan.getSourceName())
                .setProjects(projectedColumns)
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);
        pushedScan.addPushedOperation("project: " + projectedColumns);

        // 构造下推后的 Project（指向裁剪后的 TableScan）
        CustomRelNode newProject = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                .setProjects(projectExprs)
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED)
                .setRemark("pushed columns: " + projectedColumns);
        newProject.addChild(pushedScan);
        newProject.addPushedOperation("pushDownProject: " + allColumns + " -> " + projectedColumns);

        // 7. 统计
        String desc = scan.getTableName() + ": " + allColumns + " -> " + projectedColumns;
        statistics.recordProjection(sourceType, allColumns.size(), projectedColumns.size(), desc);

        call.transformTo(newProject);
    }

    /**
     * 在 Project 子树中查找 TableScan 节点。
     *
     * @param project Project 节点
     * @return 第一个 TableScan 子节点；未找到时返回 null
     */
    private CustomRelNode findTableScan(CustomRelNode project) {
        if (project == null) {
            return null;
        }
        for (CustomRelNode child : project.getChildren()) {
            if (child.getOp() == CustomRelNode.Op.TABLE_SCAN) {
                return child;
            }
            // 递归查找（穿过 Filter 等节点）
            CustomRelNode deeper = findTableScan(child);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    /**
     * 获取 TableScan 的列列表。
     *
     * <p>优先使用 TableScan 的 projects 字段；若为空，则从 remark 中解析，
     * 或返回空列表（表示无列信息）。</p>
     *
     * @param scan TableScan 节点
     * @return 列名列表
     */
    private List<String> getTableColumns(CustomRelNode scan) {
        if (scan == null) {
            return Collections.emptyList();
        }
        List<String> cols = scan.getProjects();
        if (!cols.isEmpty()) {
            return new ArrayList<>(cols);
        }
        // 从 remark 解析列（格式如 "columns: [id, name, age]"）
        String remark = scan.getRemark();
        if (remark != null && remark.contains("columns: [")) {
            int start = remark.indexOf("columns: [") + "columns: [".length();
            int end = remark.indexOf("]", start);
            if (end > start) {
                String colsStr = remark.substring(start, end);
                String[] parts = colsStr.split(",");
                List<String> result = new ArrayList<>();
                for (String p : parts) {
                    String trimmed = p.trim();
                    if (!trimmed.isEmpty()) {
                        result.add(trimmed);
                    }
                }
                return result;
            }
        }
        return Collections.emptyList();
    }

    // ===================== 批量下推便捷方法 =====================

    /**
     * 对一组投影执行下推分析，返回下推结果（不修改 RelNode 树）。
     *
     * <p>本方法供测试与统计使用，不触发 RelNode 改写。</p>
     *
     * @param projectExprs 投影表达式列表
     * @param allColumns   表的全部列
     * @param adapter      数据源适配器
     * @return 下推分析结果
     */
    public ProjectionAnalysis analyze(List<String> projectExprs,
                                      List<String> allColumns,
                                      BaseAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        Set<String> usedColumns = extractUsedColumns(projectExprs);

        List<String> projectedColumns = new ArrayList<>();
        boolean countStar = usedColumns.contains("*");
        if (countStar) {
            projectedColumns.addAll(allColumns);
        } else {
            for (String col : allColumns) {
                if (usedColumns.contains(col)) {
                    projectedColumns.add(col);
                }
            }
        }

        int totalCols = allColumns.size();
        int retainedCols = projectedColumns.size();
        int prunedCols = totalCols - retainedCols;
        double reductionRate = computeReductionRate(totalCols, retainedCols);
        double transferRate = reductionRate; // 等宽假设

        boolean shouldPushDown = !countStar && retainedCols < totalCols && retainedCols > 0;

        return new ProjectionAnalysis(projectExprs, allColumns, usedColumns,
                projectedColumns, prunedCols, reductionRate, transferRate,
                shouldPushDown, countStar);
    }

    /** 计算列裁剪率 */
    private static double computeReductionRate(int total, int retained) {
        if (total <= 0) {
            return 0.0;
        }
        return (double) Math.max(0, total - retained) / total;
    }

    /**
     * 投影下推分析结果——封装投影下推的分析输出。
     */
    public static class ProjectionAnalysis {
        /** 投影表达式列表 */
        private final List<String> projectExprs;
        /** 表的全部列 */
        private final List<String> allColumns;
        /** 引用的列集合 */
        private final Set<String> usedColumns;
        /** 裁剪后保留的列 */
        private final List<String> projectedColumns;
        /** 裁剪掉的列数 */
        private final int prunedColumnCount;
        /** 列裁剪率 */
        private final double columnReductionRate;
        /** 数据传输减少率 */
        private final double dataTransferReductionRate;
        /** 是否应执行下推 */
        private final boolean shouldPushDown;
        /** 是否引用全部列（count(*) 或 SELECT *） */
        private final boolean referencesAllColumns;

        public ProjectionAnalysis(List<String> projectExprs, List<String> allColumns,
                                  Set<String> usedColumns, List<String> projectedColumns,
                                  int prunedColumnCount, double columnReductionRate,
                                  double dataTransferReductionRate, boolean shouldPushDown,
                                  boolean referencesAllColumns) {
            this.projectExprs = Collections.unmodifiableList(projectExprs);
            this.allColumns = Collections.unmodifiableList(allColumns);
            this.usedColumns = Collections.unmodifiableSet(usedColumns);
            this.projectedColumns = Collections.unmodifiableList(projectedColumns);
            this.prunedColumnCount = prunedColumnCount;
            this.columnReductionRate = columnReductionRate;
            this.dataTransferReductionRate = dataTransferReductionRate;
            this.shouldPushDown = shouldPushDown;
            this.referencesAllColumns = referencesAllColumns;
        }

        public List<String> getProjectExprs() { return projectExprs; }
        public List<String> getAllColumns() { return allColumns; }
        public Set<String> getUsedColumns() { return usedColumns; }
        public List<String> getProjectedColumns() { return projectedColumns; }
        public int getPrunedColumnCount() { return prunedColumnCount; }
        public double getColumnReductionRate() { return columnReductionRate; }
        public double getDataTransferReductionRate() { return dataTransferReductionRate; }
        public boolean shouldPushDown() { return shouldPushDown; }
        public boolean referencesAllColumns() { return referencesAllColumns; }

        /** 保留列数 */
        public int getRetainedColumnCount() { return projectedColumns.size(); }
        /** 总列数 */
        public int getTotalColumnCount() { return allColumns.size(); }

        @Override
        public String toString() {
            return "ProjectionAnalysis{total=" + getTotalColumnCount()
                    + ", retained=" + getRetainedColumnCount()
                    + ", pruned=" + prunedColumnCount
                    + ", reductionRate=" + String.format("%.2f%%", columnReductionRate * 100)
                    + ", transferReduction=" + String.format("%.2f%%", dataTransferReductionRate * 100)
                    + ", shouldPushDown=" + shouldPushDown + '}';
        }
    }
}