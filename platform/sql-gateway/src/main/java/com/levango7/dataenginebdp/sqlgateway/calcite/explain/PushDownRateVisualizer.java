package com.levango7.dataenginebdp.sqlgateway.calcite.explain;

import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.BaseAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PredicateType;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.ProjectionStatistics;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PushDownStatistics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 下推率统计可视化器——将 {@link PushDownStatistics} 与 {@link ProjectionStatistics}
 * 转换为结构化指标 Map，供 {@link ExplainFormatter} 渲染。
 *
 * <p>本类聚合两类下推统计：</p>
 * <ul>
 *   <li><b>谓词下推统计</b>（{@link PushDownStatistics}）：总体下推率、按谓词类型/数据源分类下推率</li>
 *   <li><b>投影下推统计</b>（{@link ProjectionStatistics}）：列裁剪率、数据传输减少率、合并次数</li>
 * </ul>
 *
 * <p>输出指标键采用点分路径（如 {@code pushDown.predicate.byType.EQUALITY}），
 * 可通过 {@link ExplainFormatter#nest} 转为分层结构。</p>
 *
 * <p>典型用法：</p>
 * <pre>
 *   PushDownRateVisualizer visualizer = new PushDownRateVisualizer();
 *   Map&lt;String, Object&gt; stats = visualizer.visualize(predStats, projStats, relNode);
 * </pre>
 *
 * @author shuqing-bigdata
 */
public class PushDownRateVisualizer {

    /** 进度条字符块（从空到满） */
    private static final char[] BAR_BLOCKS = {' ', '▏', '▎', '▍', '▌', '▋', '▊', '▉', '█'};

    /**
     * 可视化下推率统计。
     *
     * @param predStats 谓词下推统计（null 视为空统计）
     * @param projStats 投影下推统计（null 视为空统计）
     * @param relNode   优化后的 RelNode 树（用于节点级下推统计，null 跳过）
     * @return 结构化指标 Map
     */
    public Map<String, Object> visualize(PushDownStatistics predStats,
                                         ProjectionStatistics projStats,
                                         CustomRelNode relNode) {
        Map<String, Object> stats = new LinkedHashMap<>();

        // 谓词下推统计
        putPredicateStats(stats, predStats);

        // 投影下推统计
        putProjectionStats(stats, projStats);

        // 节点级下推统计（来自 RelNode 树）
        putNodeStats(stats, relNode);

        // 综合下推率（谓词下推率与投影下推率的几何平均）
        double predRate = predStats == null ? 0.0 : predStats.getPushDownRate();
        double projRate = projStats == null ? 0.0 : projStats.getPushDownRate();
        double overall = geometricMean(predRate, projRate);
        stats.put("pushDown.overallRate", overall);
        stats.put("pushDown.overallRatePct", formatPct(overall));
        stats.put("pushDown.overallBar", progressBar(overall, 20));

        return stats;
    }

    /**
     * 仅可视化谓词下推统计（便捷方法）。
     *
     * @param predStats 谓词下推统计
     * @return 结构化指标 Map
     */
    public Map<String, Object> visualizePredicate(PushDownStatistics predStats) {
        return visualize(predStats, null, null);
    }

    /**
     * 仅可视化投影下推统计（便捷方法）。
     *
     * @param projStats 投影下推统计
     * @return 结构化指标 Map
     */
    public Map<String, Object> visualizeProjection(ProjectionStatistics projStats) {
        return visualize(null, projStats, null);
    }

    // ===================== 谓词下推统计 =====================

    /**
     * 将谓词下推统计写入指标 Map。
     *
     * @param stats     指标 Map
     * @param predStats 谓词下推统计
     */
    private void putPredicateStats(Map<String, Object> stats, PushDownStatistics predStats) {
        String prefix = "pushDown.predicate";
        if (predStats == null) {
            stats.put(prefix + ".total", 0);
            stats.put(prefix + ".pushed", 0);
            stats.put(prefix + ".remaining", 0);
            stats.put(prefix + ".rate", 0.0);
            stats.put(prefix + ".ratePct", "0.00%");
            stats.put(prefix + ".rateBar", progressBar(0, 20));
            return;
        }

        stats.put(prefix + ".total", predStats.getTotalPredicates());
        stats.put(prefix + ".pushed", predStats.getPushedPredicates());
        stats.put(prefix + ".remaining", predStats.getRemainingPredicates());

        double rate = predStats.getPushDownRate();
        stats.put(prefix + ".rate", rate);
        stats.put(prefix + ".ratePct", formatPct(rate));
        stats.put(prefix + ".rateBar", progressBar(rate, 20));

        // 按谓词类型分类
        Map<PredicateType, int[]> typeStats = predStats.getTypeStats();
        for (Map.Entry<PredicateType, int[]> entry : typeStats.entrySet()) {
            if (entry.getValue()[0] > 0) {
                String key = prefix + ".byType." + entry.getKey().name();
                double typeRate = (double) entry.getValue()[1] / entry.getValue()[0];
                stats.put(key + ".total", entry.getValue()[0]);
                stats.put(key + ".pushed", entry.getValue()[1]);
                stats.put(key + ".rate", typeRate);
                stats.put(key + ".ratePct", formatPct(typeRate));
            }
        }

        // 按数据源类型分类
        Map<DataSourceConfig.Type, int[]> sourceStats = predStats.getSourceStats();
        for (Map.Entry<DataSourceConfig.Type, int[]> entry : sourceStats.entrySet()) {
            if (entry.getValue()[0] > 0) {
                String key = prefix + ".bySource." + entry.getKey().name();
                double srcRate = (double) entry.getValue()[1] / entry.getValue()[0];
                stats.put(key + ".total", entry.getValue()[0]);
                stats.put(key + ".pushed", entry.getValue()[1]);
                stats.put(key + ".rate", srcRate);
                stats.put(key + ".ratePct", formatPct(srcRate));
            }
        }

        // 保留原因
        List<String> reasons = predStats.getRemainingReasons();
        if (!reasons.isEmpty()) {
            stats.put(prefix + ".remainingReasons", reasons);
        }

        // 已下推谓词描述
        List<String> pushedDescs = predStats.getPushedDescriptions();
        if (!pushedDescs.isEmpty()) {
            stats.put(prefix + ".pushedDescriptions", pushedDescs);
        }
    }

    // ===================== 投影下推统计 =====================

    /**
     * 将投影下推统计写入指标 Map。
     *
     * @param stats     指标 Map
     * @param projStats 投影下推统计
     */
    private void putProjectionStats(Map<String, Object> stats, ProjectionStatistics projStats) {
        String prefix = "pushDown.projection";
        if (projStats == null) {
            stats.put(prefix + ".totalCols", 0);
            stats.put(prefix + ".retainedCols", 0);
            stats.put(prefix + ".prunedCols", 0);
            stats.put(prefix + ".reductionRate", 0.0);
            stats.put(prefix + ".reductionRatePct", "0.00%");
            stats.put(prefix + ".reductionBar", progressBar(0, 20));
            stats.put(prefix + ".pushDownCount", 0);
            stats.put(prefix + ".mergeCount", 0);
            stats.put(prefix + ".skipCount", 0);
            stats.put(prefix + ".pushDownRate", 0.0);
            return;
        }

        stats.put(prefix + ".totalCols", projStats.getTotalColumns());
        stats.put(prefix + ".retainedCols", projStats.getRetainedColumns());
        stats.put(prefix + ".prunedCols", projStats.getPrunedColumns());

        double reductionRate = projStats.getColumnReductionRate();
        stats.put(prefix + ".reductionRate", reductionRate);
        stats.put(prefix + ".reductionRatePct", formatPct(reductionRate));
        stats.put(prefix + ".reductionBar", progressBar(reductionRate, 20));

        double transferRate = projStats.getDataTransferReductionRate();
        stats.put(prefix + ".transferReductionRate", transferRate);
        stats.put(prefix + ".transferReductionRatePct", formatPct(transferRate));

        stats.put(prefix + ".pushDownCount", projStats.getPushDownCount());
        stats.put(prefix + ".mergeCount", projStats.getMergeCount());
        stats.put(prefix + ".skipCount", projStats.getSkipCount());

        double pushDownRate = projStats.getPushDownRate();
        stats.put(prefix + ".pushDownRate", pushDownRate);
        stats.put(prefix + ".pushDownRatePct", formatPct(pushDownRate));

        // 按数据源分类
        Map<DataSourceConfig.Type, int[]> sourceStats = projStats.getSourceStats();
        for (Map.Entry<DataSourceConfig.Type, int[]> entry : sourceStats.entrySet()) {
            if (entry.getValue()[0] > 0) {
                String key = prefix + ".bySource." + entry.getKey().name();
                int total = entry.getValue()[0];
                int retained = entry.getValue()[1];
                double rate = (double) (total - retained) / total;
                stats.put(key + ".totalCols", total);
                stats.put(key + ".retainedCols", retained);
                stats.put(key + ".reductionRate", rate);
                stats.put(key + ".reductionRatePct", formatPct(rate));
            }
        }
    }

    // ===================== 节点级下推统计 =====================

    /**
     * 从 RelNode 树统计节点级下推情况。
     *
     * @param stats   指标 Map
     * @param relNode RelNode 树
     */
    private void putNodeStats(Map<String, Object> stats, CustomRelNode relNode) {
        String prefix = "pushDown.node";
        if (relNode == null) {
            stats.put(prefix + ".total", 0);
            stats.put(prefix + ".pushed", 0);
            stats.put(prefix + ".notPushed", 0);
            stats.put(prefix + ".partiallyPushed", 0);
            stats.put(prefix + ".notApplicable", 0);
            stats.put(prefix + ".rate", 0.0);
            stats.put(prefix + ".ratePct", "0.00%");
            return;
        }

        int[] counts = new int[5]; // total, pushed, notPushed, partiallyPushed, notApplicable
        countNodes(relNode, counts);

        stats.put(prefix + ".total", counts[0]);
        stats.put(prefix + ".pushed", counts[1]);
        stats.put(prefix + ".notPushed", counts[2]);
        stats.put(prefix + ".partiallyPushed", counts[3]);
        stats.put(prefix + ".notApplicable", counts[4]);

        double rate = counts[0] == 0 ? 0.0 : (double) counts[1] / counts[0];
        stats.put(prefix + ".rate", rate);
        stats.put(prefix + ".ratePct", formatPct(rate));
        stats.put(prefix + ".rateBar", progressBar(rate, 20));

        // 跨源标记
        Set<String> sources = relNode.collectSourceNames();
        stats.put(prefix + ".federated", relNode.isFederated());
        stats.put(prefix + ".sourceCount", sources.size());
        if (!sources.isEmpty()) {
            stats.put(prefix + ".sources", String.join(", ", sources));
        }
    }

    /**
     * 递归统计节点下推状态。
     *
     * @param node   当前节点
     * @param counts 计数数组
     */
    private void countNodes(CustomRelNode node, int[] counts) {
        if (node == null) {
            return;
        }
        counts[0]++;
        switch (node.getPushDownStatus()) {
            case PUSHED -> counts[1]++;
            case NOT_PUSHED -> counts[2]++;
            case PARTIALLY_PUSHED -> counts[3]++;
            case NOT_APPLICABLE -> counts[4]++;
        }
        for (CustomRelNode child : node.getChildren()) {
            countNodes(child, counts);
        }
    }

    // ===================== 辅助方法 =====================

    /**
     * 生成进度条字符串。
     *
     * @param rate   比率 [0, 1]
     * @param length 进度条长度
     * @return 进度条字符串（如 "[████████░░░░░░░░░░░░] 40%"）
     */
    public static String progressBar(double rate, int length) {
        double clamped = Math.max(0, Math.min(1, rate));
        int filled = (int) (clamped * length);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < length; i++) {
            sb.append(i < filled ? BAR_BLOCKS[8] : BAR_BLOCKS[0]);
        }
        sb.append("] ").append(formatPct(clamped));
        return sb.toString();
    }

    /**
     * 格式化百分比。
     *
     * @param rate 比率
     * @return 百分比字符串（如 "75.00%"）
     */
    public static String formatPct(double rate) {
        return String.format("%.2f%%", rate * 100);
    }

    /**
     * 计算两个比率的几何平均（用于综合下推率）。
     *
     * @param a 比率 A
     * @param b 比率 B
     * @return 几何平均（若任一为 0，返回算术平均）
     */
    private static double geometricMean(double a, double b) {
        if (a == 0 || b == 0) {
            return (a + b) / 2;
        }
        return Math.sqrt(a * b);
    }
}