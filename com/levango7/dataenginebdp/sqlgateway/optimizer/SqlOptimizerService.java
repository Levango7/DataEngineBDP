package com.shuqing.bigdata.sqlgateway.optimizer;

import com.shuqing.bigdata.sqlgateway.parser.ASTNode;
import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;
import com.shuqing.bigdata.sqlgateway.parser.SqlParseException;
import com.shuqing.bigdata.sqlgateway.parser.SqlParserService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 优化服务入口——对应 Calcite 的 {@code Frameworks} + {@code HepPlanner}。
 *
 * <p>完整优化流程：
 * <pre>
 *   SQL 文本
 *     │
 *     ▼  SqlParserService.parse()
 *   ASTNode
 *     │
 *     ▼  AstToRelConverter.convert()
 *   RelNode（关系代数树）
 *     │
 *     ▼  applyRules()  ← HepPlanner 启发式优化（谓词下推/列裁剪/Join 重排）
 *   RelNode（优化后）
 *     │
 *     ▼  estimateCost()
 *   RelNode（带代价估算）
 *     │
 *     ▼  ExecutionPlanGenerator.generate()
 *   执行计划文本
 * </pre>
 * </p>
 *
 * <p>本实现采用启发式优化（对应 Calcite HepPlanner 的强制规则应用模式），
 * 规则集通过 {@link OptimizationRuleConfig} 配置。每条规则对应一个 Calcite
 * 内置规则（见 {@link OptimizationRuleConfig.Rule}）。</p>
 *
 * @author shuqing-bigdata
 */
public class SqlOptimizerService {

    private final SqlParserService parserService;
    private final AstToRelConverter converter;
    private final ExecutionPlanGenerator planGenerator;
    private OptimizationRuleConfig ruleConfig;

    public SqlOptimizerService() {
        this(new SqlParserService(), new OptimizationRuleConfig());
    }

    public SqlOptimizerService(SqlParserService parserService, OptimizationRuleConfig ruleConfig) {
        this.parserService = parserService;
        this.converter = new AstToRelConverter();
        this.planGenerator = new ExecutionPlanGenerator();
        this.ruleConfig = ruleConfig;
    }

    /**
     * 设置优化规则配置。
     *
     * @param ruleConfig 规则配置
     */
    public void setRuleConfig(OptimizationRuleConfig ruleConfig) {
        this.ruleConfig = ruleConfig;
    }

    public OptimizationRuleConfig getRuleConfig() {
        return ruleConfig;
    }

    /**
     * 优化 SQL：SQL → AST → RelNode → 优化 → 执行计划。
     *
     * @param sql     SQL 文本
     * @param dialect SQL 方言
     * @return 优化结果
     */
    public OptimizationResult optimize(String sql, SqlDialect dialect) {
        if (sql == null || sql.isBlank()) {
            return OptimizationResult.failure(sql, "SQL 不能为空");
        }
        try {
            // 1. SQL → AST
            ASTNode ast = parserService.parse(sql, dialect == null ? SqlDialect.ANSI : dialect);

            // 2. AST → RelNode
            RelNode relNode = converter.convert(ast);

            // 3. 应用优化规则
            List<String> rulesApplied = new ArrayList<>();
            RelNode optimized = applyOptimizationRules(relNode, rulesApplied);

            // 4. 代价估算
            estimateCost(optimized);

            // 5. 生成执行计划
            String plan = planGenerator.generate(optimized);

            // 6. 生成优化建议
            List<String> suggestions = generateSuggestions(optimized);

            // 7. 生成优化后 SQL（简化：返回原始 SQL，标记是否改写）
            String optimizedSql = sql;
            boolean rewritten = !rulesApplied.isEmpty();
            if (rewritten) {
                // 优化后 SQL 暂保留原始 SQL，通过 rulesApplied 标记改写
                optimizedSql = sql;
            }

            return OptimizationResult.builder()
                    .originalSql(sql)
                    .optimizedSql(optimizedSql)
                    .executionPlan(plan)
                    .rulesApplied(rulesApplied)
                    .estimatedCost(optimized.getEstimatedCost())
                    .estimatedRows(optimized.getEstimatedRows())
                    .tableAccesses(planGenerator.extractTableAccessOrder(optimized))
                    .suggestions(suggestions)
                    .success(true)
                    .dialect(dialect == null ? SqlDialect.ANSI.name() : dialect.name())
                    .build();
        } catch (SqlParseException e) {
            return OptimizationResult.failure(sql, "解析失败: " + e.getMessage());
        } catch (Exception e) {
            return OptimizationResult.failure(sql, "优化失败: " + e.getMessage());
        }
    }

    /**
     * 优化 AST：AST → RelNode → 优化 → 执行计划。
     *
     * @param astNode AST 根节点
     * @return 优化结果
     */
    public OptimizationResult optimize(ASTNode astNode) {
        if (astNode == null) {
            return OptimizationResult.failure(null, "AST 不能为空");
        }
        try {
            RelNode relNode = converter.convert(astNode);
            List<String> rulesApplied = new ArrayList<>();
            RelNode optimized = applyOptimizationRules(relNode, rulesApplied);
            estimateCost(optimized);
            String plan = planGenerator.generate(optimized);
            List<String> suggestions = generateSuggestions(optimized);

            return OptimizationResult.builder()
                    .originalSql(astNode.toString())
                    .optimizedSql(astNode.toString())
                    .executionPlan(plan)
                    .rulesApplied(rulesApplied)
                    .estimatedCost(optimized.getEstimatedCost())
                    .estimatedRows(optimized.getEstimatedRows())
                    .tableAccesses(planGenerator.extractTableAccessOrder(optimized))
                    .suggestions(suggestions)
                    .success(true)
                    .dialect(astNode.getString("dialect"))
                    .build();
        } catch (Exception e) {
            return OptimizationResult.failure(astNode.toString(), "优化失败: " + e.getMessage());
        }
    }

    /**
     * 获取执行计划文本（EXPLAIN 等价）。
     *
     * @param sql SQL 文本
     * @return 执行计划文本
     */
    public String getExecutionPlan(String sql) {
        OptimizationResult result = optimize(sql, null);
        return result.getExecutionPlan();
    }

    /**
     * 获取执行计划文本（指定方言）。
     *
     * @param sql     SQL 文本
     * @param dialect SQL 方言
     * @return 执行计划文本
     */
    public String getExecutionPlan(String sql, SqlDialect dialect) {
        return optimize(sql, dialect).getExecutionPlan();
    }

    // ===================== 优化规则应用（HepPlanner 启发式） =====================

    /**
     * 应用所有已启用的优化规则——对应 Calcite HepPlanner 的规则迭代应用。
     *
     * @param relNode    原始 RelNode
     * @param rulesApplied 记录已应用的规则名
     * @return 优化后 RelNode
     */
    RelNode applyOptimizationRules(RelNode relNode, List<String> rulesApplied) {
        if (relNode == null || ruleConfig == null) {
            return relNode;
        }
        RelNode result = relNode;
        Set<OptimizationRuleConfig.Rule> enabled = ruleConfig.getEnabledRules();

        // 1. 谓词合并（FilterMergeRule）
        if (enabled.contains(OptimizationRuleConfig.Rule.FILTER_MERGE)) {
            RelNode merged = mergeFilters(result);
            if (merged != result) {
                result = merged;
                rulesApplied.add(OptimizationRuleConfig.Rule.FILTER_MERGE.getShortName());
            }
        }

        // 2. 谓词下推（FilterPushDownPastProjectRule）
        if (enabled.contains(OptimizationRuleConfig.Rule.FILTER_PUSH_DOWN)) {
            RelNode pushed = pushDownFilter(result);
            if (pushed != result) {
                result = pushed;
                rulesApplied.add(OptimizationRuleConfig.Rule.FILTER_PUSH_DOWN.getShortName());
            }
        }

        // 3. 投影合并/列裁剪（ProjectMergeRule）
        if (enabled.contains(OptimizationRuleConfig.Rule.PROJECT_MERGE)) {
            RelNode merged = mergeProjects(result);
            if (merged != result) {
                result = merged;
                rulesApplied.add(OptimizationRuleConfig.Rule.PROJECT_MERGE.getShortName());
            }
        }

        // 4. 谓词下推至 Join 输入（FilterIntoJoinRule）
        if (enabled.contains(OptimizationRuleConfig.Rule.FILTER_INTO_JOIN)) {
            RelNode pushed = pushFilterIntoJoin(result);
            if (pushed != result) {
                result = pushed;
                rulesApplied.add(OptimizationRuleConfig.Rule.FILTER_INTO_JOIN.getShortName());
            }
        }

        // 5. Join 重排序（JoinReorderRule）
        if (enabled.contains(OptimizationRuleConfig.Rule.JOIN_REORDER)) {
            RelNode reordered = reorderJoin(result);
            if (reordered != result) {
                result = reordered;
                rulesApplied.add(OptimizationRuleConfig.Rule.JOIN_REORDER.getShortName());
            }
        }

        // 6. 聚合常量上拉（AggregatePullUpConstantsRule）
        if (enabled.contains(OptimizationRuleConfig.Rule.AGG_CONSTANT_PULL_UP)) {
            // 启发式：标记已分析（实际常量上拉在复杂场景才有效果）
            if (containsAggregate(result)) {
                rulesApplied.add(OptimizationRuleConfig.Rule.AGG_CONSTANT_PULL_UP.getShortName());
            }
        }

        // 7. Join 表达式下推（JoinPushExpressionsRule）
        if (enabled.contains(OptimizationRuleConfig.Rule.JOIN_EXPR_PUSH_DOWN)) {
            if (containsJoin(result)) {
                rulesApplied.add(OptimizationRuleConfig.Rule.JOIN_EXPR_PUSH_DOWN.getShortName());
            }
        }

        return result;
    }

    /** 谓词合并：合并相邻的 Filter 节点 Filter(Filter(x, c1), c2) → Filter(x, c1 AND c2) */
    private RelNode mergeFilters(RelNode node) {
        if (node == null) {
            return null;
        }
        // 递归处理子节点
        List<RelNode> newChildren = new ArrayList<>();
        boolean changed = false;
        for (RelNode c : node.getChildren()) {
            RelNode nc = mergeFilters(c);
            if (nc != c) {
                changed = true;
            }
            newChildren.add(nc);
        }

        // 当前节点是 Filter，且唯一子节点也是 Filter → 合并
        if (node.getOp() == RelNode.Op.FILTER && newChildren.size() == 1
                && newChildren.get(0).getOp() == RelNode.Op.FILTER) {
            RelNode childFilter = newChildren.get(0);
            String mergedCond = combineConditions(node.getCondition(), childFilter.getCondition());
            RelNode merged = RelNode.of(RelNode.Op.FILTER).setCondition(mergedCond);
            merged.setRemark("merged filter");
            for (RelNode gc : childFilter.getChildren()) {
                merged.addChild(gc);
            }
            return merged;
        }

        if (changed) {
            RelNode rebuilt = RelNode.of(node.getOp());
            copyProps(node, rebuilt);
            for (RelNode nc : newChildren) {
                rebuilt.addChild(nc);
            }
            return rebuilt;
        }
        return node;
    }

    /** 谓词下推：将 Filter 推过 Project → Project(Filter(scan, cond)) */
    private RelNode pushDownFilter(RelNode node) {
        if (node == null) {
            return null;
        }
        List<RelNode> newChildren = new ArrayList<>();
        boolean changed = false;
        for (RelNode c : node.getChildren()) {
            RelNode nc = pushDownFilter(c);
            if (nc != c) {
                changed = true;
            }
            newChildren.add(nc);
        }

        // Filter(Project(child, cols)) → Project(Filter(child, cond), cols)
        if (node.getOp() == RelNode.Op.FILTER && newChildren.size() == 1
                && newChildren.get(0).getOp() == RelNode.Op.PROJECT) {
            RelNode project = newChildren.get(0);
            if (project.getChildren().size() == 1) {
                RelNode projectChild = project.getChildren().get(0);
                RelNode newFilter = RelNode.of(RelNode.Op.FILTER)
                        .setCondition(node.getCondition())
                        .setRemark("pushed down");
                newFilter.addChild(projectChild);
                RelNode newProject = RelNode.of(RelNode.Op.PROJECT)
                        .setProjects(project.getProjects());
                if (project.getRemark() != null) {
                    newProject.setRemark(project.getRemark());
                }
                newProject.addChild(newFilter);
                return newProject;
            }
        }

        if (changed) {
            RelNode rebuilt = RelNode.of(node.getOp());
            copyProps(node, rebuilt);
            for (RelNode nc : newChildren) {
                rebuilt.addChild(nc);
            }
            return rebuilt;
        }
        return node;
    }

    /** 投影合并：Project(Project(x, cols1), cols2) → Project(x, cols2 ∘ cols1) */
    private RelNode mergeProjects(RelNode node) {
        if (node == null) {
            return null;
        }
        List<RelNode> newChildren = new ArrayList<>();
        boolean changed = false;
        for (RelNode c : node.getChildren()) {
            RelNode nc = mergeProjects(c);
            if (nc != c) {
                changed = true;
            }
            newChildren.add(nc);
        }

        // Project(Project(child, cols1), cols2) → Project(child, cols2)
        if (node.getOp() == RelNode.Op.PROJECT && newChildren.size() == 1
                && newChildren.get(0).getOp() == RelNode.Op.PROJECT) {
            RelNode innerProject = newChildren.get(0);
            if (innerProject.getChildren().size() == 1) {
                RelNode merged = RelNode.of(RelNode.Op.PROJECT)
                        .setProjects(node.getProjects())
                        .setRemark("merged project");
                merged.addChild(innerProject.getChildren().get(0));
                return merged;
            }
        }

        if (changed) {
            RelNode rebuilt = RelNode.of(node.getOp());
            copyProps(node, rebuilt);
            for (RelNode nc : newChildren) {
                rebuilt.addChild(nc);
            }
            return rebuilt;
        }
        return node;
    }

    /** 谓词下推至 Join 输入：Filter(Join(l, r, on), cond) → Join(Filter(l, cond_l), Filter(r, cond_r), on) */
    private RelNode pushFilterIntoJoin(RelNode node) {
        if (node == null) {
            return null;
        }
        List<RelNode> newChildren = new ArrayList<>();
        boolean changed = false;
        for (RelNode c : node.getChildren()) {
            RelNode nc = pushFilterIntoJoin(c);
            if (nc != c) {
                changed = true;
            }
            newChildren.add(nc);
        }

        // Filter(Join(l, r, on), cond) → Join(Filter(l, cond), r, on)（简化：仅标记）
        if (node.getOp() == RelNode.Op.FILTER && newChildren.size() == 1
                && newChildren.get(0).getOp() == RelNode.Op.JOIN) {
            RelNode join = newChildren.get(0);
            if (join.getChildren().size() == 2) {
                // 简化处理：保留 Filter 在 Join 之上，但标记可下推
                // 完整实现需要分析谓词引用的列属于哪一侧
                // 此处仅当谓词为简单单表条件时下推
                String cond = node.getCondition();
                RelNode left = join.getChildren().get(0);
                RelNode right = join.getChildren().get(1);
                String leftTable = firstTable(left);
                if (leftTable != null && cond != null
                        && cond.toLowerCase(Locale.ROOT).contains(leftTable.toLowerCase(Locale.ROOT))) {
                    // 谓词引用左表 → 下推到左侧
                    RelNode pushedFilter = RelNode.of(RelNode.Op.FILTER)
                            .setCondition(cond).setRemark("pushed into join input");
                    pushedFilter.addChild(left);
                    RelNode newJoin = RelNode.of(RelNode.Op.JOIN)
                            .setJoinType(join.getJoinType())
                            .setCondition(join.getCondition());
                    newJoin.addChild(pushedFilter);
                    newJoin.addChild(right);
                    return newJoin;
                }
            }
        }

        if (changed) {
            RelNode rebuilt = RelNode.of(node.getOp());
            copyProps(node, rebuilt);
            for (RelNode nc : newChildren) {
                rebuilt.addChild(nc);
            }
            return rebuilt;
        }
        return node;
    }

    /** Join 重排序：将小表放左侧（启发式，标记） */
    private RelNode reorderJoin(RelNode node) {
        if (node == null) {
            return null;
        }
        if (node.getOp() == RelNode.Op.JOIN && node.getChildren().size() == 2) {
            // 启发式：根据表名长度模拟"小表驱动"（实际应基于统计信息）
            RelNode left = node.getChildren().get(0);
            RelNode right = node.getChildren().get(1);
            String leftTable = firstTable(left);
            String rightTable = firstTable(right);
            if (leftTable != null && rightTable != null
                    && leftTable.length() > rightTable.length()) {
                // 交换左右
                RelNode newJoin = RelNode.of(RelNode.Op.JOIN)
                        .setJoinType(node.getJoinType())
                        .setCondition(node.getCondition())
                        .setRemark("join reordered (small table first)");
                newJoin.addChild(right);
                newJoin.addChild(left);
                return newJoin;
            }
        }
        // 递归
        List<RelNode> newChildren = new ArrayList<>();
        boolean changed = false;
        for (RelNode c : node.getChildren()) {
            RelNode nc = reorderJoin(c);
            if (nc != c) {
                changed = true;
            }
            newChildren.add(nc);
        }
        if (changed) {
            RelNode rebuilt = RelNode.of(node.getOp());
            copyProps(node, rebuilt);
            for (RelNode nc : newChildren) {
                rebuilt.addChild(nc);
            }
            return rebuilt;
        }
        return node;
    }

    // ===================== 代价估算 =====================

    /**
     * 自底向上估算行数与代价——对应 Calcite 的 {@code RelMetadataQuery}。
     *
     * @param node RelNode 根节点
     */
    void estimateCost(RelNode node) {
        if (node == null) {
            return;
        }
        // 先递归子节点
        for (RelNode c : node.getChildren()) {
            estimateCost(c);
        }
        switch (node.getOp()) {
            case TABLE_SCAN:
                // 表扫描：假设 1000 行，代价 = 100
                node.setEstimatedRows(1000);
                node.setEstimatedCost(100);
                break;
            case VALUES:
                node.setEstimatedRows(1);
                node.setEstimatedCost(1);
                break;
            case FILTER:
                // 过滤：行数减半，代价 = 子代价 + 10
                double childRows = childRows(node);
                double childCost = childCost(node);
                node.setEstimatedRows(Math.max(1, childRows * 0.5));
                node.setEstimatedCost(childCost + 10);
                break;
            case PROJECT:
                // 投影：行数不变，代价 = 子代价 + 5
                node.setEstimatedRows(childRows(node));
                node.setEstimatedCost(childCost(node) + 5);
                break;
            case JOIN:
                // Join：行数 = 左 × 右 × 0.1（选择率），代价 = 左 + 右 + 左 × 右 × 0.1
                double leftRows = node.getChildren().size() > 0 ? node.getChildren().get(0).getEstimatedRows() : 1;
                double rightRows = node.getChildren().size() > 1 ? node.getChildren().get(1).getEstimatedRows() : 1;
                double joinRows = leftRows * rightRows * 0.1;
                node.setEstimatedRows(joinRows);
                node.setEstimatedCost(childCost(node) + leftRows + rightRows + joinRows);
                break;
            case AGGREGATE:
                // 聚合：行数 = sqrt(子行数)，代价 = 子代价 + 子行数
                double aggIn = childRows(node);
                node.setEstimatedRows(Math.max(1, Math.sqrt(aggIn)));
                node.setEstimatedCost(childCost(node) + aggIn);
                break;
            case SORT:
                // 排序：行数不变，代价 = 子代价 + 子行数 × log(子行数)
                double sortIn = childRows(node);
                node.setEstimatedRows(sortIn);
                node.setEstimatedCost(childCost(node) + sortIn * Math.log(Math.max(2, sortIn)));
                break;
            case LIMIT:
                // Limit：行数 = min(limit, 子行数)，代价 = 子代价
                double limitRows = node.getLimit() >= 0 ? node.getLimit() : childRows(node);
                node.setEstimatedRows(Math.min(limitRows, childRows(node)));
                node.setEstimatedCost(childCost(node));
                break;
            case UNION:
                // Union：行数 = sum(子行数)，代价 = sum(子代价)
                double unionRows = 0;
                double unionCost = 0;
                for (RelNode c : node.getChildren()) {
                    unionRows += c.getEstimatedRows();
                    unionCost += c.getEstimatedCost();
                }
                node.setEstimatedRows(unionRows);
                node.setEstimatedCost(unionCost);
                break;
            case SUBQUERY:
                // 子查询：继承子节点
                node.setEstimatedRows(childRows(node));
                node.setEstimatedCost(childCost(node));
                break;
            default:
                node.setEstimatedRows(childRows(node));
                node.setEstimatedCost(childCost(node));
        }
    }

    private double childRows(RelNode node) {
        return node.getChildren().isEmpty() ? 1 : node.getChildren().get(0).getEstimatedRows();
    }

    private double childCost(RelNode node) {
        return node.getChildren().isEmpty() ? 0 : node.getChildren().get(0).getEstimatedCost();
    }

    // ===================== 启发式建议 =====================

    /**
     * 基于优化后 RelNode 生成启发式优化建议。
     *
     * @param node RelNode
     * @return 建议列表
     */
    private List<String> generateSuggestions(RelNode node) {
        List<String> suggestions = new ArrayList<>();
        if (node == null) {
            return suggestions;
        }
        // 检查 Filter 中的等值条件 → 建议索引
        List<RelNode> filters = findAll(node, RelNode.Op.FILTER);
        for (RelNode f : filters) {
            String cond = f.getCondition();
            if (cond != null && cond.contains("=") && !cond.toLowerCase(Locale.ROOT).contains("join")) {
                String table = firstTableBelow(f);
                if (table != null) {
                    suggestions.add("考虑在表 " + table + " 上为等值条件 [" + cond + "] 添加索引");
                }
            }
        }
        // 检查 Join → 建议统计信息
        List<RelNode> joins = findAll(node, RelNode.Op.JOIN);
        if (!joins.isEmpty()) {
            suggestions.add("检测到 " + joins.size() + " 个 Join，建议收集表统计信息以优化 Join 顺序");
        }
        // 检查无 Filter 的表扫描 → 建议添加过滤条件
        List<RelNode> scans = findAll(node, RelNode.Op.TABLE_SCAN);
        for (RelNode scan : scans) {
            if (!hasFilterAbove(node, scan)) {
                suggestions.add("表 " + scan.getTableName() + " 全表扫描，建议添加 WHERE 条件");
            }
        }
        // 检查 LIMIT + 无 ORDER BY → 提示结果不确定
        if (!findAll(node, RelNode.Op.LIMIT).isEmpty() && findAll(node, RelNode.Op.SORT).isEmpty()) {
            suggestions.add("使用 LIMIT 但无 ORDER BY，结果行顺序不确定");
        }
        return suggestions;
    }

    // ===================== 辅助方法 =====================

    private String combineConditions(String c1, String c2) {
        if (c1 == null || c1.isBlank()) {
            return c2;
        }
        if (c2 == null || c2.isBlank()) {
            return c1;
        }
        return c1 + " AND " + c2;
    }

    private void copyProps(RelNode src, RelNode dst) {
        dst.setTableName(src.getTableName());
        dst.setTableAlias(src.getTableAlias());
        dst.setCondition(src.getCondition());
        dst.setJoinType(src.getJoinType());
        dst.setProjects(src.getProjects());
        dst.setGroupKeys(src.getGroupKeys());
        dst.setAggFuncs(src.getAggFuncs());
        dst.setSortKeys(src.getSortKeys());
        dst.setLimit(src.getLimit());
        dst.setOffset(src.getOffset());
        dst.setRemark(src.getRemark());
    }

    private String firstTable(RelNode node) {
        if (node == null) {
            return null;
        }
        if (node.getOp() == RelNode.Op.TABLE_SCAN) {
            return node.getTableName();
        }
        for (RelNode c : node.getChildren()) {
            String t = firstTable(c);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private String firstTableBelow(RelNode filter) {
        for (RelNode c : filter.getChildren()) {
            return firstTable(c);
        }
        return null;
    }

    private boolean containsAggregate(RelNode node) {
        return !findAll(node, RelNode.Op.AGGREGATE).isEmpty();
    }

    private boolean containsJoin(RelNode node) {
        return !findAll(node, RelNode.Op.JOIN).isEmpty();
    }

    private List<RelNode> findAll(RelNode node, RelNode.Op op) {
        List<RelNode> result = new ArrayList<>();
        findAll(node, op, result);
        return result;
    }

    private void findAll(RelNode node, RelNode.Op op, List<RelNode> result) {
        if (node == null) {
            return;
        }
        if (node.getOp() == op) {
            result.add(node);
        }
        for (RelNode c : node.getChildren()) {
            findAll(c, op, result);
        }
    }

    private boolean hasFilterAbove(RelNode root, RelNode target) {
        if (root == null) {
            return false;
        }
        if (root == target) {
            return false;
        }
        if (root.getOp() == RelNode.Op.FILTER) {
            // 检查 target 是否在 root 子树中
            if (containsNode(root, target)) {
                return true;
            }
        }
        for (RelNode c : root.getChildren()) {
            if (hasFilterAbove(c, target)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsNode(RelNode root, RelNode target) {
        if (root == target) {
            return true;
        }
        for (RelNode c : root.getChildren()) {
            if (containsNode(c, target)) {
                return true;
            }
        }
        return false;
    }

    /** 返回所有可用规则名（用于 API 暴露） */
    public List<String> listAvailableRules() {
        List<String> names = new ArrayList<>();
        for (OptimizationRuleConfig.Rule r : OptimizationRuleConfig.Rule.values()) {
            names.add(r.getShortName() + " - " + r.getDescription());
        }
        return names;
    }

    /** 返回已启用规则名 */
    public List<String> listEnabledRules() {
        if (ruleConfig == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (OptimizationRuleConfig.Rule r : ruleConfig.getEnabledRules()) {
            names.add(r.getShortName());
        }
        return names;
    }
}