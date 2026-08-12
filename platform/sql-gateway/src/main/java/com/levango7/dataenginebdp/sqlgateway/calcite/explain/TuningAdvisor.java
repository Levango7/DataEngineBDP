package com.levango7.dataenginebdp.sqlgateway.calcite.explain;

import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.BaseAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.ProjectionStatistics;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PushDownStatistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 性能调优建议器——基于 EXPLAIN 结果、下推统计与 Cost 估算，生成可执行的调优建议。
 *
 * <p>本类是 EXPLAIN 可视化的智能分析层，根据以下信号生成建议：</p>
 * <ul>
 *   <li><b>下推率过低</b>：建议检查谓词是否可下推、UDF 是否可改写</li>
 *   <li><b>列裁剪率过低</b>：建议避免 SELECT *，只查询必要列</li>
 *   <li><b>Cost 瓶颈</b>：针对 CPU/IO/Network 瓶颈给出对应优化方向</li>
 *   <li><b>跨源 Join</b>：建议使用 Colocate Join 或预聚合物化视图</li>
 *   <li><b>嵌套投影未合并</b>：建议简化投影层级</li>
 *   <li><b>未下推节点</b>：列出未下推节点及原因</li>
 * </ul>
 *
 * <p>每条建议包含严重级别（INFO/WARN/CRITICAL）与具体描述，便于前端按级别展示。</p>
 *
 * <p>典型用法：</p>
 * <pre>
 *   TuningAdvisor advisor = new TuningAdvisor();
 *   List&lt;String&gt; suggestions = advisor.advise(relNode, predStats, projStats, costStats);
 * </pre>
 *
 * @author shuqing-bigdata
 */
public class TuningAdvisor {

    /** 下推率告警阈值（低于此值触发 WARN） */
    private final double pushDownWarnThreshold;
    /** 下推率严重阈值（低于此值触发 CRITICAL） */
    private final double pushDownCriticalThreshold;
    /** 列裁剪率告警阈值 */
    private final double projectionWarnThreshold;
    /** 跨源 Join 行数告警阈值 */
    private final double federatedRowsThreshold;

    /**
     * 构造调优建议器（默认阈值）。
     */
    public TuningAdvisor() {
        this(0.5, 0.2, 0.3, 1_000_000);
    }

    /**
     * 构造调优建议器（自定义阈值）。
     *
     * @param pushDownWarnThreshold      下推率告警阈值
     * @param pushDownCriticalThreshold  下推率严重阈值
     * @param projectionWarnThreshold    列裁剪率告警阈值
     * @param federatedRowsThreshold     跨源 Join 行数告警阈值
     */
    public TuningAdvisor(double pushDownWarnThreshold, double pushDownCriticalThreshold,
                         double projectionWarnThreshold, double federatedRowsThreshold) {
        this.pushDownWarnThreshold = pushDownWarnThreshold;
        this.pushDownCriticalThreshold = pushDownCriticalThreshold;
        this.projectionWarnThreshold = projectionWarnThreshold;
        this.federatedRowsThreshold = federatedRowsThreshold;
    }

    /**
     * 生成性能调优建议。
     *
     * @param relNode   优化后的 RelNode 树（null 跳过节点级建议）
     * @param predStats 谓词下推统计（null 跳过）
     * @param projStats 投影下推统计（null 跳过）
     * @param costStats Cost 估算指标（null 跳过）
     * @return 调优建议列表（按严重级别排序：CRITICAL → WARN → INFO）
     */
    public List<String> advise(CustomRelNode relNode,
                               PushDownStatistics predStats,
                               ProjectionStatistics projStats,
                               Map<String, Object> costStats) {
        List<Suggestion> suggestions = new ArrayList<>();

        // 1. 下推率建议
        addPushDownSuggestions(suggestions, predStats);

        // 2. 投影下推建议
        addProjectionSuggestions(suggestions, projStats);

        // 3. Cost 瓶颈建议
        addCostSuggestions(suggestions, costStats);

        // 4. 节点级建议
        addNodeSuggestions(suggestions, relNode);

        // 按严重级别排序
        suggestions.sort((a, b) -> Integer.compare(b.severity.priority, a.severity.priority));

        // 转为字符串列表
        List<String> result = new ArrayList<>(suggestions.size());
        for (Suggestion s : suggestions) {
            result.add(s.toString());
        }
        return result;
    }

    /**
     * 生成调优建议（便捷方法，不含统计信息）。
     *
     * @param relNode RelNode 树
     * @return 调优建议列表
     */
    public List<String> advise(CustomRelNode relNode) {
        return advise(relNode, null, null, null);
    }

    // ===================== 下推率建议 =====================

    /**
     * 基于谓词下推统计生成建议。
     *
     * @param suggestions 建议列表
     * @param predStats   谓词下推统计
     */
    private void addPushDownSuggestions(List<Suggestion> suggestions,
                                        PushDownStatistics predStats) {
        if (predStats == null || predStats.getTotalPredicates() == 0) {
            return;
        }

        double rate = predStats.getPushDownRate();
        int total = predStats.getTotalPredicates();
        int remaining = predStats.getRemainingPredicates();

        if (rate < pushDownCriticalThreshold) {
            suggestions.add(new Suggestion(Severity.CRITICAL,
                    "谓词下推率仅 " + PushDownRateVisualizer.formatPct(rate)
                            + "（" + remaining + "/" + total + " 谓词未下推），"
                            + "大量谓词在联邦层执行，建议检查谓词是否含 UDF/OR/子查询"
                            + "并改写为可下推形式"));
        } else if (rate < pushDownWarnThreshold) {
            suggestions.add(new Suggestion(Severity.WARN,
                    "谓词下推率 " + PushDownRateVisualizer.formatPct(rate)
                            + "（" + remaining + "/" + total + " 谓词未下推），"
                            + "建议检查未下推谓词原因并尝试改写"));
        } else {
            suggestions.add(new Suggestion(Severity.INFO,
                    "谓词下推率 " + PushDownRateVisualizer.formatPct(rate)
                            + "，下推效果良好"));
        }

        // 按谓词类型分析未下推原因
        Map<com.levango7.dataenginebdp.sqlgateway.calcite.rule.PredicateType, int[]> typeStats =
                predStats.getTypeStats();
        for (Map.Entry<com.levango7.dataenginebdp.sqlgateway.calcite.rule.PredicateType, int[]> entry :
                typeStats.entrySet()) {
            int[] counts = entry.getValue();
            if (counts[0] > 0 && counts[1] == 0
                    && entry.getKey() == com.levango7.dataenginebdp.sqlgateway.calcite.rule.PredicateType.UNSUPPORTED) {
                suggestions.add(new Suggestion(Severity.WARN,
                        "存在 " + counts[0] + " 个不支持谓词（UDF/OR/子查询），"
                                + "建议改写为等值/范围/IN/LIKE 等可下推形式"));
            }
        }

        // 保留原因
        List<String> reasons = predStats.getRemainingReasons();
        if (!reasons.isEmpty() && reasons.size() <= 5) {
            suggestions.add(new Suggestion(Severity.INFO,
                    "未下推原因: " + String.join("; ", reasons)));
        }
    }

    // ===================== 投影下推建议 =====================

    /**
     * 基于投影下推统计生成建议。
     *
     * @param suggestions 建议列表
     * @param projStats   投影下推统计
     */
    private void addProjectionSuggestions(List<Suggestion> suggestions,
                                          ProjectionStatistics projStats) {
        if (projStats == null || projStats.getTotalColumns() == 0) {
            return;
        }

        double reductionRate = projStats.getColumnReductionRate();
        int totalCols = projStats.getTotalColumns();
        int prunedCols = projStats.getPrunedColumns();

        if (reductionRate < projectionWarnThreshold && totalCols > 5) {
            suggestions.add(new Suggestion(Severity.WARN,
                    "列裁剪率仅 " + PushDownRateVisualizer.formatPct(reductionRate)
                            + "（" + prunedCols + "/" + totalCols + " 列被裁剪），"
                            + "查询引用了大部分列，建议避免 SELECT * 只查询必要列"));
        } else if (reductionRate >= 0.5) {
            suggestions.add(new Suggestion(Severity.INFO,
                    "列裁剪率 " + PushDownRateVisualizer.formatPct(reductionRate)
                            + "，数据传输量显著减少"));
        }

        // 嵌套投影合并
        if (projStats.getMergeCount() > 0) {
            suggestions.add(new Suggestion(Severity.INFO,
                    "嵌套投影合并 " + projStats.getMergeCount() + " 次，"
                            + "已简化投影层级"));
        }

        // 跳过次数
        if (projStats.getSkipCount() > 3) {
            suggestions.add(new Suggestion(Severity.INFO,
                    "投影下推跳过 " + projStats.getSkipCount() + " 次，"
                            + "可能存在 SELECT * 或全列引用"));
        }
    }

    // ===================== Cost 瓶颈建议 =====================

    /**
     * 基于 Cost 估算指标生成建议。
     *
     * @param suggestions 建议列表
     * @param costStats   Cost 指标
     */
    private void addCostSuggestions(List<Suggestion> suggestions,
                                    Map<String, Object> costStats) {
        if (costStats == null || costStats.isEmpty()) {
            return;
        }

        Object bottleneck = costStats.get("cost.bottleneck");
        Object totalObj = costStats.get("cost.total");
        double total = totalObj instanceof Number ? ((Number) totalObj).doubleValue() : 0;

        if (bottleneck == null || "NONE".equals(bottleneck)) {
            return;
        }

        String bottleneckStr = bottleneck.toString();
        switch (bottleneckStr) {
            case "NETWORK" -> {
                Object netShare = costStats.get("cost.share.networkPct");
                suggestions.add(new Suggestion(Severity.WARN,
                        "Cost 瓶颈为 NETWORK（占比 " + Objects.requireNonNullElse(netShare, "?")
                                + "），建议增加数据本地化、使用 Colocate Join 减少 Shuffle，"
                                + "或下推更多过滤条件减少数据传输量"));
            }
            case "IO" -> {
                Object ioShare = costStats.get("cost.share.ioPct");
                suggestions.add(new Suggestion(Severity.WARN,
                        "Cost 瓶颈为 IO（占比 " + Objects.requireNonNullElse(ioShare, "?")
                                + "），建议使用列存格式（Iceberg/Doris）、"
                                + "增加分区裁剪、或使用物化视图预聚合"));
            }
            case "CPU" -> {
                Object cpuShare = costStats.get("cost.share.cpuPct");
                suggestions.add(new Suggestion(Severity.WARN,
                        "Cost 瓶颈为 CPU（占比 " + Objects.requireNonNullElse(cpuShare, "?")
                                + "），建议简化表达式、避免复杂 UDF，"
                                + "或使用向量化执行引擎（Doris/Trino）"));
            }
        }

        // 总 Cost 过高
        if (total > 10_000_000) {
            suggestions.add(new Suggestion(Severity.WARN,
                    "总 Cost 较高（" + CostVisualizer.humanReadable(total)
                            + "），建议检查是否缺少过滤条件、"
                            + "是否可使用物化视图或预聚合加速"));
        }
    }

    // ===================== 节点级建议 =====================

    /**
     * 基于 RelNode 树结构生成建议。
     *
     * @param suggestions 建议列表
     * @param relNode     RelNode 树
     */
    private void addNodeSuggestions(List<Suggestion> suggestions, CustomRelNode relNode) {
        if (relNode == null) {
            return;
        }

        // 跨源 Join 建议
        if (relNode.isFederated()) {
            int joinCount = countOp(relNode, CustomRelNode.Op.JOIN);
            if (joinCount > 0) {
                suggestions.add(new Suggestion(Severity.WARN,
                        "检测到 " + joinCount + " 个跨源 Join，"
                                + "联邦层 Shuffle 开销较大，建议："
                                + "(1) 使用 Colocate Join 同源共置；"
                                + "(2) 使用广播 Join 减少 Shuffle（小表广播）；"
                                + "(3) 使用物化视图预聚合跨源结果"));
            }
        }

        // 未下推节点建议
        int notPushedCount = countPushDownStatus(relNode,
                CustomRelNode.PushDownStatus.NOT_PUSHED);
        if (notPushedCount > 3) {
            suggestions.add(new Suggestion(Severity.WARN,
                    "有 " + notPushedCount + " 个节点未下推，"
                            + "建议检查下推规则配置与谓词可下推性"));
        }

        // 深度过深建议
        int depth = relNode.depth();
        if (depth > 6) {
            suggestions.add(new Suggestion(Severity.INFO,
                    "执行计划深度 " + depth + " 层，"
                            + "查询较复杂，建议拆分为多个子查询或使用临时表"));
        }

        // 收集未下推节点的具体原因
        List<String> reasons = collectNotPushedReasons(relNode);
        if (!reasons.isEmpty() && reasons.size() <= 3) {
            for (String reason : reasons) {
                suggestions.add(new Suggestion(Severity.INFO,
                        "未下推节点: " + reason));
            }
        }
    }

    /**
     * 统计指定操作类型的节点数。
     *
     * @param node RelNode 树
     * @param op   操作类型
     * @return 节点数
     */
    private int countOp(CustomRelNode node, CustomRelNode.Op op) {
        if (node == null) {
            return 0;
        }
        int count = node.getOp() == op ? 1 : 0;
        for (CustomRelNode child : node.getChildren()) {
            count += countOp(child, op);
        }
        return count;
    }

    /**
     * 统计指定下推状态的节点数。
     *
     * @param node   RelNode 树
     * @param status 下推状态
     * @return 节点数
     */
    private int countPushDownStatus(CustomRelNode node,
                                    CustomRelNode.PushDownStatus status) {
        if (node == null) {
            return 0;
        }
        int count = node.getPushDownStatus() == status ? 1 : 0;
        for (CustomRelNode child : node.getChildren()) {
            count += countPushDownStatus(child, status);
        }
        return count;
    }

    /**
     * 收集未下推节点的原因（最多 3 个）。
     *
     * @param node RelNode 树
     * @return 原因列表
     */
    private List<String> collectNotPushedReasons(CustomRelNode node) {
        List<String> reasons = new ArrayList<>();
        collectNotPushedReasons(node, reasons, 3);
        return reasons;
    }

    /**
     * 递归收集未下推节点原因。
     *
     * @param node    当前节点
     * @param reasons 原因列表
     * @param max     最大收集数
     */
    private void collectNotPushedReasons(CustomRelNode node, List<String> reasons, int max) {
        if (node == null || reasons.size() >= max) {
            return;
        }
        if (node.getPushDownStatus() == CustomRelNode.PushDownStatus.NOT_PUSHED) {
            StringBuilder sb = new StringBuilder();
            sb.append(node.getOp());
            if (node.getTableName() != null) {
                sb.append("(").append(node.getTableName()).append(")");
            }
            if (node.getPushDownReason() != null) {
                sb.append(" - ").append(node.getPushDownReason());
            }
            reasons.add(sb.toString());
        }
        for (CustomRelNode child : node.getChildren()) {
            collectNotPushedReasons(child, reasons, max);
        }
    }

    // ===================== 内部类 =====================

    /**
     * 建议严重级别。
     */
    public enum Severity {
        /** 信息（正常情况） */
        INFO(1),
        /** 警告（可优化） */
        WARN(2),
        /** 严重（强烈建议优化） */
        CRITICAL(3);

        final int priority;

        Severity(int priority) {
            this.priority = priority;
        }
    }

    /**
     * 调优建议（含严重级别与描述）。
     */
    public static class Suggestion {
        private final Severity severity;
        private final String message;

        public Suggestion(Severity severity, String message) {
            this.severity = Objects.requireNonNull(severity);
            this.message = Objects.requireNonNull(message);
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return "[" + severity + "] " + message;
        }
    }
}