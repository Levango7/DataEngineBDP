package com.levango7.dataenginebdp.sqlgateway.calcite.explain;

import com.levango7.dataenginebdp.sqlgateway.calcite.CalciteOptimizer;
import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.BaseAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.OptimizerConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PredicatePushDownRule;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.ProjectionStatistics;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.ProjectPushDownRule;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PushDownStatistics;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * EXPLAIN 可视化统一入口——聚合 {@link CalciteOptimizer}、下推统计、Cost 估算与调优建议，
 * 生成结构化的 {@link ExplainResult}，并按指定格式渲染输出。
 *
 * <p>本类是 T012-6 EXPLAIN 可视化与性能调优的对外门面，封装完整流程：</p>
 * <pre>
 *   SQL 文本
 *     │
 *     ▼  CalciteOptimizer.optimize()      → RelNode（优化后）
 *     │
 *     ▼  CalciteOptimizer.toCustomRel()   → CustomRelNode（含下推标注）
 *     │
 *     ▼  CalciteOptimizer.applyCustomRules() → CustomRelNode（应用自定义下推规则）
 *     │
 *     ▼  PushDownRateVisualizer           → 下推率统计指标
 *     │
 *     ▼  CostVisualizer                   → Cost 估算指标
 *     │
 *     ▼  TuningAdvisor                    → 性能调优建议
 *     │
 *     ▼  ExplainResult                    → 结构化结果
 *     │
 *     ▼  ExplainFormatter.format()        → 树形/JSON/表格式文本
 * </pre>
 *
 * <p>典型用法：</p>
 * <pre>
 *   ExplainVisualizer visualizer = new ExplainVisualizer(optimizer);
 *   ExplainResult result = visualizer.explain(sql);
 *   String tree = visualizer.explain(sql, ExplainFormat.TREE);
 *   String json = visualizer.explain(sql, ExplainFormat.JSON);
 *   String table = visualizer.explain(sql, ExplainFormat.TABLE);
 * </pre>
 *
 * @author shuqing-bigdata
 */
public class ExplainVisualizer {

    /** Calcite 优化器 */
    private final CalciteOptimizer optimizer;
    /** 下推率可视化器 */
    private final PushDownRateVisualizer pushDownVisualizer;
    /** Cost 可视化器 */
    private final CostVisualizer costVisualizer;
    /** 调优建议器 */
    private final TuningAdvisor tuningAdvisor;

    /**
     * 构造 EXPLAIN 可视化器。
     *
     * @param optimizer Calcite 优化器（非空）
     */
    public ExplainVisualizer(CalciteOptimizer optimizer) {
        this.optimizer = Objects.requireNonNull(optimizer, "optimizer");
        OptimizerConfig config = optimizer.getConfig();
        this.pushDownVisualizer = new PushDownRateVisualizer();
        this.costVisualizer = new CostVisualizer(config);
        this.tuningAdvisor = new TuningAdvisor();
    }

    /**
     * 构造 EXPLAIN 可视化器（指定子组件）。
     *
     * @param optimizer          Calcite 优化器
     * @param pushDownVisualizer 下推率可视化器
     * @param costVisualizer     Cost 可视化器
     * @param tuningAdvisor      调优建议器
     */
    public ExplainVisualizer(CalciteOptimizer optimizer,
                             PushDownRateVisualizer pushDownVisualizer,
                             CostVisualizer costVisualizer,
                             TuningAdvisor tuningAdvisor) {
        this.optimizer = Objects.requireNonNull(optimizer, "optimizer");
        this.pushDownVisualizer = Objects.requireNonNull(pushDownVisualizer);
        this.costVisualizer = Objects.requireNonNull(costVisualizer);
        this.tuningAdvisor = Objects.requireNonNull(tuningAdvisor);
    }

    /**
     * 生成 EXPLAIN 结构化结果。
     *
     * <p>流程：SQL → optimize → toCustomRel → applyCustomRules → 统计 → 调优建议 → ExplainResult。</p>
     *
     * @param sql SQL 文本
     * @return EXPLAIN 结果（含 RelNode 树、下推统计、Cost、调优建议）
     */
    public ExplainResult explain(String sql) {
        if (sql == null || sql.isBlank()) {
            return ExplainResult.failure(sql, "SQL 不能为空");
        }

        try {
            // 1. Calcite 优化
            RelNode relNode = optimizer.optimize(sql);
            String relText = RelOptUtil.toString(relNode);
            double rowCount = estimateRowCount(relNode);
            int depth = relDepth(relNode);
            List<String> rulesApplied = collectAppliedRules();

            // 2. 转为 CustomRelNode
            CustomRelNode customRel = optimizer.toCustomRel(relNode);

            // 3. 应用自定义下推规则
            if (customRel != null) {
                customRel = optimizer.applyCustomRules(customRel);
            }

            // 4. 收集下推统计
            PushDownStatistics predStats = findPredicateStatistics();
            ProjectionStatistics projStats = findProjectionStatistics();

            // 5. 下推率可视化
            java.util.Map<String, Object> pushDownStats =
                    pushDownVisualizer.visualize(predStats, projStats, customRel);

            // 6. Cost 估算可视化
            BaseAdapter.Cost cost = estimateCost(customRel);
            java.util.Map<String, Object> costStats =
                    costVisualizer.visualize(cost, customRel, optimizer.getAdapters());

            // 7. 调优建议
            List<String> suggestions =
                    tuningAdvisor.advise(customRel, predStats, projStats, costStats);

            return ExplainResult.success(sql, customRel, rowCount, depth,
                    rulesApplied, pushDownStats, costStats, suggestions);

        } catch (CalciteOptimizer.CalciteOptimizeException e) {
            return ExplainResult.failure(sql, e.getMessage());
        } catch (Exception e) {
            return ExplainResult.failure(sql, "EXPLAIN 失败: " + e.getMessage());
        }
    }

    /**
     * 生成 EXPLAIN 并按指定格式渲染输出。
     *
     * @param sql    SQL 文本
     * @param format 输出格式（null 视为 TREE）
     * @return 格式化文本
     */
    public String explain(String sql, ExplainFormat format) {
        ExplainResult result = explain(sql);
        return ExplainFormatter.format(result, format);
    }

    /**
     * 生成树形格式 EXPLAIN。
     *
     * @param sql SQL 文本
     * @return 树形文本
     */
    public String explainTree(String sql) {
        return explain(sql, ExplainFormat.TREE);
    }

    /**
     * 生成 JSON 格式 EXPLAIN。
     *
     * @param sql SQL 文本
     * @return JSON 文本
     */
    public String explainJson(String sql) {
        return explain(sql, ExplainFormat.JSON);
    }

    /**
     * 生成表格格式 EXPLAIN。
     *
     * @param sql SQL 文本
     * @return 表格文本
     */
    public String explainTable(String sql) {
        return explain(sql, ExplainFormat.TABLE);
    }

    /**
     * 仅生成下推率可视化（不执行完整 EXPLAIN）。
     *
     * @param sql SQL 文本
     * @return 下推率指标 Map
     */
    public java.util.Map<String, Object> visualizePushDown(String sql) {
        ExplainResult result = explain(sql);
        return result.getPushDownStats();
    }

    /**
     * 仅生成 Cost 可视化（不执行完整 EXPLAIN）。
     *
     * @param sql SQL 文本
     * @return Cost 指标 Map
     */
    public java.util.Map<String, Object> visualizeCost(String sql) {
        ExplainResult result = explain(sql);
        return result.getCostStats();
    }

    /**
     * 仅生成调优建议（不执行完整 EXPLAIN）。
     *
     * @param sql SQL 文本
     * @return 调优建议列表
     */
    public List<String> advise(String sql) {
        ExplainResult result = explain(sql);
        return result.getTuningSuggestions();
    }

    // ===================== 内部辅助方法 =====================

    /**
     * 从已注册的规则中查找谓词下推统计器。
     *
     * @return 谓词下推统计器（null 表示未注册）
     */
    private PushDownStatistics findPredicateStatistics() {
        for (var rule : optimizer.getCustomRules()) {
            if (rule instanceof PredicatePushDownRule predRule) {
                return predRule.getStatistics();
            }
        }
        return null;
    }

    /**
     * 从已注册的规则中查找投影下推统计器。
     *
     * @return 投影下推统计器（null 表示未注册）
     */
    private ProjectionStatistics findProjectionStatistics() {
        for (var rule : optimizer.getCustomRules()) {
            if (rule instanceof ProjectPushDownRule projRule) {
                return projRule.getStatistics();
            }
        }
        return null;
    }

    /**
     * 估算 RelNode 树的行数。
     *
     * @param relNode RelNode
     * @return 估算行数
     */
    private double estimateRowCount(RelNode relNode) {
        if (relNode == null || relNode.getCluster() == null
                || relNode.getCluster().getMetadataQuery() == null) {
            return 0.0;
        }
        try {
            Double rows = relNode.getCluster().getMetadataQuery().getRowCount(relNode);
            return rows == null ? 0.0 : rows;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * 计算 RelNode 树深度。
     *
     * @param relNode RelNode
     * @return 深度
     */
    private int relDepth(RelNode relNode) {
        if (relNode == null) {
            return 0;
        }
        if (relNode.getInputs().isEmpty()) {
            return 1;
        }
        int max = 0;
        for (RelNode input : relNode.getInputs()) {
            max = Math.max(max, relDepth(input));
        }
        return max + 1;
    }

    /**
     * 收集已应用的规则名列表。
     *
     * @return 规则名列表
     */
    private List<String> collectAppliedRules() {
        List<String> applied = new ArrayList<>();
        for (var rule : optimizer.getCustomRules()) {
            if (rule.isEnabled()) {
                applied.add(rule.getRuleName());
            }
        }
        return applied;
    }

    /**
     * 估算 CustomRelNode 树的 Cost（累加各数据源适配器估算结果）。
     *
     * @param relNode CustomRelNode 树
     * @return 累加 Cost
     */
    private BaseAdapter.Cost estimateCost(CustomRelNode relNode) {
        if (relNode == null || optimizer.getAdapters().isEmpty()) {
            return BaseAdapter.Cost.zero();
        }
        double totalCpu = 0, totalIo = 0, totalNet = 0, totalRows = 0;
        for (BaseAdapter adapter : optimizer.getAdapters()) {
            BaseAdapter.Cost cost = adapter.costEstimate(relNode);
            totalCpu += cost.getCpuCost();
            totalIo += cost.getIoCost();
            totalNet += cost.getNetworkCost();
            totalRows += cost.getRows();
        }
        return new BaseAdapter.Cost(totalCpu, totalIo, totalNet, totalRows);
    }

    // ===================== Getter =====================

    public CalciteOptimizer getOptimizer() {
        return optimizer;
    }

    public PushDownRateVisualizer getPushDownVisualizer() {
        return pushDownVisualizer;
    }

    public CostVisualizer getCostVisualizer() {
        return costVisualizer;
    }

    public TuningAdvisor getTuningAdvisor() {
        return tuningAdvisor;
    }
}