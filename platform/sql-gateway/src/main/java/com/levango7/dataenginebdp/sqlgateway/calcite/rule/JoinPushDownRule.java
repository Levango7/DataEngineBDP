package com.levango7.dataenginebdp.sqlgateway.calcite.rule;

import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.BaseAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Join 下推规则——将同源 Join 下推到数据源执行，跨源 Join 保留在联邦层并选择最优物理策略。
 *
 * <p>本规则继承 {@link PushDownRule}，匹配 {@code JOIN} 节点，是 T012-4 的核心组件。
 * 它整合了三个协作组件：</p>
 * <ul>
 *   <li>{@link CrossSourceJoinDetector}：识别 Join 是同源还是跨源</li>
 *   <li>{@link BroadcastJoinStrategy}：为跨源 Join 选择 BROADCAST/SHUFFLE/REPLICATED 策略</li>
 *   <li>{@link JoinReorderOptimizer}：对多表 Join 进行重排序优化</li>
 * </ul>
 *
 * <p><b>下推流程：</b></p>
 * <pre>
 *   Join(condition: t1.id = t2.id)
 *     ├─ left:  TableScan(t1, source=iceberg_lake)
 *     └─ right: TableScan(t2, source=iceberg_lake)
 *     │
 *     ▼  1. CrossSourceJoinDetector.detect(join)
 *   DetectionResult{type=SAME_SOURCE, source=iceberg_lake}
 *     │
 *     ▼  2. 同源 → 调用 adapter.pushDown(join)
 *   PushDownResult{pushedSql="SELECT * FROM t1 JOIN t2 ON t1.id=t2.id", success=true}
 *     │
 *     ▼  3. 标记 Join 为 PUSHED
 *   Join(pushDown=PUSHED, pushedOps=["join: t1.id=t2.id"])
 * </pre>
 *
 * <p><b>跨源 Join 处理：</b></p>
 * <pre>
 *   Join(condition: t1.id = t2.id)
 *     ├─ left:  TableScan(t1, source=iceberg_lake)  [大表 1TB]
 *     └─ right: TableScan(t2, source=doris_olap)    [小表 50MB]
 *     │
 *     ▼  1. detect → CROSS_SOURCE
 *     ▼  2. BroadcastJoinStrategy.chooseStrategy → BROADCAST right
 *     ▼  3. 保留在联邦层，标记 NOT_APPLICABLE + 策略标注
 *   Join(pushDown=NOT_APPLICABLE, remark="BROADCAST right (50MB < 100MB)")
 * </pre>
 *
 * <p><b>多表 Join 重排序：</b>当 Join 树涉及 3+ 表时，先调用
 * {@link JoinReorderOptimizer#reorder} 选择最优顺序，再按新顺序下推。</p>
 *
 * <p><b>语义等价性保证：</b>同源 Join 下推到数据源执行结果与联邦层执行等价；
 * 跨源 Join 保留在联邦层，由联邦层负责协调跨源数据交换，结果与原查询等价。</p>
 *
 * <p><b>下推率统计：</b>每次 {@link #onMatch} 执行后，统计信息累积到 {@link #statistics}，
 * 可通过 {@link #getStatistics()} 获取同源/跨源 Join 数、下推率、Broadcast 使用率等指标。</p>
 *
 * @author shuqing-bigdata
 */
public class JoinPushDownRule extends PushDownRule {

    /** 规则短名 */
    public static final String RULE_NAME = "JoinPushDown";

    /** 跨源 Join 识别器 */
    private final CrossSourceJoinDetector detector;

    /** Broadcast Join 策略 */
    private final BroadcastJoinStrategy broadcastStrategy;

    /** Join 重排序优化器 */
    private final JoinReorderOptimizer reorderOptimizer;

    /** Join 下推统计器 */
    private final JoinPushDownStatistics statistics;

    /**
     * 构造 Join 下推规则（完整参数）。
     *
     * @param adapter           关联的数据源适配器（用于基类匹配判定）
     * @param detector          跨源 Join 识别器
     * @param broadcastStrategy Broadcast Join 策略
     * @param reorderOptimizer  Join 重排序优化器
     * @param statistics        统计器
     */
    public JoinPushDownRule(BaseAdapter adapter,
                            CrossSourceJoinDetector detector,
                            BroadcastJoinStrategy broadcastStrategy,
                            JoinReorderOptimizer reorderOptimizer,
                            JoinPushDownStatistics statistics) {
        super(RULE_NAME,
                "Join 下推规则：同源 Join 下推到数据源，跨源 Join 保留联邦层并选择最优物理策略",
                Objects.requireNonNull(adapter, "adapter"),
                CustomRelNode.Op.JOIN);
        this.detector = Objects.requireNonNull(detector, "detector");
        this.broadcastStrategy = Objects.requireNonNull(broadcastStrategy, "broadcastStrategy");
        this.reorderOptimizer = Objects.requireNonNull(reorderOptimizer, "reorderOptimizer");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    /**
     * 构造 Join 下推规则（默认统计器）。
     *
     * @param adapter           关联的数据源适配器
     * @param detector          跨源 Join 识别器
     * @param broadcastStrategy Broadcast Join 策略
     * @param reorderOptimizer  Join 重排序优化器
     */
    public JoinPushDownRule(BaseAdapter adapter,
                            CrossSourceJoinDetector detector,
                            BroadcastJoinStrategy broadcastStrategy,
                            JoinReorderOptimizer reorderOptimizer) {
        this(adapter, detector, broadcastStrategy, reorderOptimizer, new JoinPushDownStatistics());
    }

    /**
     * 获取统计器。
     *
     * @return 统计器
     */
    public JoinPushDownStatistics getStatistics() {
        return statistics;
    }

    /**
     * 获取跨源 Join 识别器。
     *
     * @return 识别器
     */
    public CrossSourceJoinDetector getDetector() {
        return detector;
    }

    /**
     * 获取 Broadcast Join 策略。
     *
     * @return 策略
     */
    public BroadcastJoinStrategy getBroadcastStrategy() {
        return broadcastStrategy;
    }

    /**
     * 获取 Join 重排序优化器。
     *
     * @return 优化器
     */
    public JoinReorderOptimizer getReorderOptimizer() {
        return reorderOptimizer;
    }

    @Override
    public boolean matches(CustomRelNode relNode) {
        if (!isEnabled() || relNode == null) {
            return false;
        }
        if (relNode.getOp() != CustomRelNode.Op.JOIN) {
            return false;
        }
        // Join 节点至少 2 个子节点
        return relNode.getChildren().size() >= 2;
    }

    @Override
    public void onMatch(RuleCall call) {
        CustomRelNode joinNode = call.getRoot();
        if (!matches(joinNode)) {
            return;
        }

        // 1. 识别同源/跨源
        CrossSourceJoinDetector.DetectionResult detection = detector.detect(joinNode);

        CustomRelNode result;
        if (detection.isPushable()) {
            // 2a. 同源 Join → 下推到数据源
            result = handleSameSourceJoin(joinNode, detection);
        } else if (detection.isCrossSource()) {
            // 2b. 跨源 Join → 保留联邦层，选择物理策略
            result = handleCrossSourceJoin(joinNode, detection);
        } else {
            // 2c. 未知 → 保留联邦层
            result = handleUnknownJoin(joinNode, detection);
        }

        call.transformTo(result);
    }

    /**
     * 处理同源 Join——下推到数据源执行。
     *
     * @param joinNode   Join 节点
     * @param detection  识别结果
     * @return 改写后的 RelNode
     */
    private CustomRelNode handleSameSourceJoin(CustomRelNode joinNode,
                                               CrossSourceJoinDetector.DetectionResult detection) {
        String source = detection.getSource();
        DataSourceConfig.Type sourceType = resolveSourceType(source);

        // 调用适配器下推
        BaseAdapter adapter = detector.findAdapter(source);
        String pushedSql = null;
        boolean pushSuccess = false;
        if (adapter != null) {
            try {
                BaseAdapter.PushDownResult pr = adapter.pushDown(
                        joinNode, new BaseAdapter.PushDownContext(
                                new ArrayList<>(), adapter.getDialect(), 1.0, 1.0, 1.0));
                if (pr.isSuccess()) {
                    pushedSql = pr.getPushedSql();
                    pushSuccess = true;
                }
            } catch (Exception e) {
                pushSuccess = false;
            }
        }

        // 构造下推后的 Join 节点
        CustomRelNode result = cloneJoin(joinNode);
        if (pushSuccess) {
            result.setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED)
                    .addPushedOperation("join: " + joinNode.getCondition()
                            + " → " + source)
                    .setRemark("同源 Join 下推到 " + source
                            + (pushedSql != null ? ", SQL=" + pushedSql : ""));
            statistics.recordSameSource(sourceType, source, true);
        } else {
            result.setPushDownStatus(CustomRelNode.PushDownStatus.NOT_PUSHED)
                    .setPushDownReason("同源 Join 但适配器下推失败: " + source);
            statistics.recordSameSource(sourceType, source, false);
        }

        return result;
    }

    /**
     * 处理跨源 Join——保留联邦层，选择物理策略。
     *
     * @param joinNode   Join 节点
     * @param detection  识别结果
     * @return 改写后的 RelNode
     */
    private CustomRelNode handleCrossSourceJoin(CustomRelNode joinNode,
                                                CrossSourceJoinDetector.DetectionResult detection) {
        // 选择 Broadcast/Shuffle 策略
        BroadcastJoinStrategy.StrategyResult strategyResult =
                broadcastStrategy.chooseStrategy(joinNode);

        CustomRelNode result = cloneJoin(joinNode);
        result.setPushDownStatus(CustomRelNode.PushDownStatus.NOT_APPLICABLE)
                .setPushDownReason("跨源 Join 保留联邦层: " + detection.getReason());

        if (strategyResult.isSuccess()) {
            BroadcastJoinStrategy.JoinStrategy strategy = strategyResult.getStrategy();
            BroadcastJoinStrategy.BroadcastSide side = strategyResult.getBroadcastSide();

            String remark = String.format("跨源 Join 策略=%s, broadcastSide=%s, "
                            + "leftSize=%d, rightSize=%d",
                    strategy, side,
                    strategyResult.getLeftSize(), strategyResult.getRightSize());
            result.setRemark(remark);

            // 记录统计
            DataSourceConfig.Type leftType = resolveSourceType(detection.getLeftSource());
            DataSourceConfig.Type rightType = resolveSourceType(detection.getRightSource());
            statistics.recordCrossSource(leftType, rightType,
                    detection.getLeftSource(), detection.getRightSource(),
                    strategy, true);
        } else {
            statistics.recordCrossSource(null, null,
                    detection.getLeftSource(), detection.getRightSource(),
                    null, false);
        }

        return result;
    }

    /**
     * 处理未知 Join——保守保留联邦层。
     *
     * @param joinNode   Join 节点
     * @param detection  识别结果
     * @return 改写后的 RelNode
     */
    private CustomRelNode handleUnknownJoin(CustomRelNode joinNode,
                                            CrossSourceJoinDetector.DetectionResult detection) {
        CustomRelNode result = cloneJoin(joinNode);
        result.setPushDownStatus(CustomRelNode.PushDownStatus.NOT_APPLICABLE)
                .setPushDownReason("未知 Join 保留联邦层: " + detection.getReason())
                .setRemark("UNKNOWN");
        statistics.recordUnknown(detection.getReason());
        return result;
    }

    /**
     * 克隆 Join 节点（保留子节点与 condition）。
     *
     * @param original 原 Join 节点
     * @return 克隆的 Join 节点
     */
    private CustomRelNode cloneJoin(CustomRelNode original) {
        CustomRelNode clone = CustomRelNode.of(CustomRelNode.Op.JOIN)
                .setCondition(original.getCondition())
                .setProjects(original.getProjects())
                .setEstimatedRows(original.getEstimatedRows())
                .setEstimatedCost(original.getEstimatedCost());
        for (CustomRelNode child : original.getChildren()) {
            clone.addChild(child);
        }
        return clone;
    }

    /**
     * 根据数据源名解析其类型。
     *
     * @param sourceName 数据源名
     * @return 数据源类型，未找到时返回 null
     */
    private DataSourceConfig.Type resolveSourceType(String sourceName) {
        if (sourceName == null) {
            return null;
        }
        BaseAdapter adapter = detector.findAdapter(sourceName);
        if (adapter != null && adapter.getDataSourceConfig() != null) {
            return adapter.getDataSourceConfig().getType();
        }
        return null;
    }

    /**
     * 对多表 Join 树进行重排序优化。
     *
     * <p>提取 Join 树涉及的所有表名，调用 {@link JoinReorderOptimizer#reorder}
     * 选择最优顺序，返回重排序结果。本方法不直接改写 RelNode 树，
     * 仅返回最优顺序供上层应用。</p>
     *
     * @param joinNode Join 树根节点
     * @return 重排序结果
     */
    public JoinReorderOptimizer.ReorderResult reorderJoinOrder(CustomRelNode joinNode) {
        List<String> tables = collectJoinTables(joinNode);
        return reorderOptimizer.reorder(tables);
    }

    /**
     * 收集 Join 树涉及的所有表名。
     *
     * @param node RelNode 子树
     * @return 表名列表（去重、保序）
     */
    public List<String> collectJoinTables(CustomRelNode node) {
        Set<String> tables = new LinkedHashSet<>();
        collectTablesRecursive(node, tables);
        return new ArrayList<>(tables);
    }

    private void collectTablesRecursive(CustomRelNode node, Set<String> tables) {
        if (node == null) {
            return;
        }
        if (node.getOp() == CustomRelNode.Op.TABLE_SCAN && node.getTableName() != null) {
            tables.add(node.getTableName());
        }
        for (CustomRelNode child : node.getChildren()) {
            collectTablesRecursive(child, tables);
        }
    }

    /**
     * 获取参与下推统计的所有数据源类型。
     *
     * @return 数据源类型集合
     */
    public Set<DataSourceConfig.Type> getActiveSourceTypes() {
        return statistics.getActiveSourceTypes();
    }

    // ===================== 统计器 =====================

    /**
     * Join 下推统计器——记录 Join 下推规则的执行统计信息。
     *
     * <p>提供以下维度指标：</p>
     * <ul>
     *   <li><b>总体下推率</b>：同源 Join 数 / 总 Join 数</li>
     *   <li><b>同源 Join 下推率</b>：成功下推的同源 Join / 同源 Join 总数</li>
     *   <li><b>按数据源分类</b>：各数据源的 Join 数与下推率</li>
     *   <li><b>Broadcast 使用率</b>：Broadcast 策略 / 跨源 Join 数</li>
     *   <li><b>跨源 Join 原因列表</b>：便于排查</li>
     * </ul>
     *
     * @author shuqing-bigdata
     */
    public static class JoinPushDownStatistics {
        private volatile int totalJoins = 0;
        private volatile int sameSourceJoins = 0;
        private volatile int sameSourcePushed = 0;
        private volatile int crossSourceJoins = 0;
        private volatile int unknownJoins = 0;
        private volatile int broadcastCount = 0;
        private volatile int shuffleCount = 0;
        private volatile int replicatedCount = 0;

        /** 按数据源类型统计：类型 → [同源 Join 数, 下推数] */
        private final Map<DataSourceConfig.Type, int[]> sourceStats = new LinkedHashMap<>();
        /** 跨源 Join 原因列表 */
        private final List<String> crossSourceReasons = Collections.synchronizedList(new ArrayList<>());
        /** 已下推 Join 描述列表 */
        private final List<String> pushedDescriptions = Collections.synchronizedList(new ArrayList<>());

        public JoinPushDownStatistics() {
            for (DataSourceConfig.Type type : DataSourceConfig.Type.values()) {
                sourceStats.put(type, new int[]{0, 0});
            }
        }

        /** 记录同源 Join 下推结果 */
        public synchronized void recordSameSource(DataSourceConfig.Type sourceType,
                                                  String sourceName, boolean pushed) {
            totalJoins++;
            sameSourceJoins++;
            if (pushed) {
                sameSourcePushed++;
                if (sourceName != null) {
                    pushedDescriptions.add("sameSource: " + sourceName);
                }
            }
            if (sourceType != null) {
                int[] count = sourceStats.get(sourceType);
                if (count != null) {
                    count[0]++;
                    if (pushed) {
                        count[1]++;
                    }
                }
            }
        }

        /** 记录跨源 Join */
        public synchronized void recordCrossSource(DataSourceConfig.Type leftType,
                                                   DataSourceConfig.Type rightType,
                                                   String leftSource, String rightSource,
                                                   BroadcastJoinStrategy.JoinStrategy strategy,
                                                   boolean success) {
            totalJoins++;
            crossSourceJoins++;
            if (strategy != null) {
                switch (strategy) {
                    case BROADCAST -> broadcastCount++;
                    case SHUFFLE -> shuffleCount++;
                    case REPLICATED -> replicatedCount++;
                }
            }
            String reason = String.format("crossSource: %s(%s) ⋈ %s(%s) → %s",
                    leftSource, leftType, rightSource, rightType,
                    strategy == null ? "FAILED" : strategy);
            crossSourceReasons.add(reason);
        }

        /** 记录未知 Join */
        public synchronized void recordUnknown(String reason) {
            totalJoins++;
            unknownJoins++;
            if (reason != null) {
                crossSourceReasons.add("[UNKNOWN] " + reason);
            }
        }

        /** 总体下推率 = 同源下推成功数 / 总 Join 数 */
        public double getPushDownRate() {
            if (totalJoins == 0) {
                return 0.0;
            }
            return (double) sameSourcePushed / totalJoins;
        }

        /** 同源 Join 下推率 = 同源下推成功数 / 同源 Join 数 */
        public double getSameSourcePushDownRate() {
            if (sameSourceJoins == 0) {
                return 0.0;
            }
            return (double) sameSourcePushed / sameSourceJoins;
        }

        /** Broadcast 使用率 = (Broadcast + Replicated) / 跨源 Join 数 */
        public double getBroadcastRate() {
            if (crossSourceJoins == 0) {
                return 0.0;
            }
            return (double) (broadcastCount + replicatedCount) / crossSourceJoins;
        }

        /** 指定数据源的下推率 */
        public double getPushDownRate(DataSourceConfig.Type sourceType) {
            int[] count = sourceStats.get(sourceType);
            if (count == null || count[0] == 0) {
                return 0.0;
            }
            return (double) count[1] / count[0];
        }

        /** 获取参与统计的数据源类型集合 */
        public Set<DataSourceConfig.Type> getActiveSourceTypes() {
            Set<DataSourceConfig.Type> active = new LinkedHashSet<>();
            for (Map.Entry<DataSourceConfig.Type, int[]> entry : sourceStats.entrySet()) {
                if (entry.getValue()[0] > 0) {
                    active.add(entry.getKey());
                }
            }
            return active;
        }

        public int getTotalJoins() {
            return totalJoins;
        }

        public int getSameSourceJoins() {
            return sameSourceJoins;
        }

        public int getSameSourcePushed() {
            return sameSourcePushed;
        }

        public int getCrossSourceJoins() {
            return crossSourceJoins;
        }

        public int getUnknownJoins() {
            return unknownJoins;
        }

        public int getBroadcastCount() {
            return broadcastCount;
        }

        public int getShuffleCount() {
            return shuffleCount;
        }

        public int getReplicatedCount() {
            return replicatedCount;
        }

        public Map<DataSourceConfig.Type, int[]> getSourceStats() {
            Map<DataSourceConfig.Type, int[]> snapshot = new LinkedHashMap<>();
            for (Map.Entry<DataSourceConfig.Type, int[]> entry : sourceStats.entrySet()) {
                snapshot.put(entry.getKey(), entry.getValue().clone());
            }
            return Collections.unmodifiableMap(snapshot);
        }

        public List<String> getCrossSourceReasons() {
            return Collections.unmodifiableList(crossSourceReasons);
        }

        public List<String> getPushedDescriptions() {
            return Collections.unmodifiableList(pushedDescriptions);
        }

        /** 重置统计 */
        public synchronized void reset() {
            totalJoins = 0;
            sameSourceJoins = 0;
            sameSourcePushed = 0;
            crossSourceJoins = 0;
            unknownJoins = 0;
            broadcastCount = 0;
            shuffleCount = 0;
            replicatedCount = 0;
            for (int[] count : sourceStats.values()) {
                count[0] = 0;
                count[1] = 0;
            }
            crossSourceReasons.clear();
            pushedDescriptions.clear();
        }

        /** 生成统计摘要 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("JoinPushDownStatistics{");
            sb.append("total=").append(totalJoins);
            sb.append(", sameSource=").append(sameSourceJoins)
                    .append("(pushed=").append(sameSourcePushed).append(")");
            sb.append(", crossSource=").append(crossSourceJoins);
            sb.append(", unknown=").append(unknownJoins);
            sb.append(", pushDownRate=").append(String.format("%.2f%%", getPushDownRate() * 100));
            sb.append(", broadcastRate=").append(String.format("%.2f%%", getBroadcastRate() * 100));
            sb.append("\n  bySource:");
            for (Map.Entry<DataSourceConfig.Type, int[]> entry : sourceStats.entrySet()) {
                if (entry.getValue()[0] > 0) {
                    double rate = (double) entry.getValue()[1] / entry.getValue()[0];
                    sb.append("\n    ").append(entry.getKey())
                            .append(": total=").append(entry.getValue()[0])
                            .append(", pushed=").append(entry.getValue()[1])
                            .append(", rate=").append(String.format("%.2f%%", rate * 100));
                }
            }
            sb.append("\n}");
            return sb.toString();
        }

        @Override
        public String toString() {
            return "JoinPushDownStatistics{total=" + totalJoins
                    + ", same=" + sameSourceJoins + "(pushed=" + sameSourcePushed + ")"
                    + ", cross=" + crossSourceJoins
                    + ", unknown=" + unknownJoins
                    + ", rate=" + String.format("%.4f", getPushDownRate()) + '}';
        }
    }
}