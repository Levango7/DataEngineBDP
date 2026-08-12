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
 * 跨源 Join 识别器——分析 Join 的左右输入 RelNode，判定其数据源归属，
 * 决定 Join 可下推到数据源执行（同源）或必须保留在联邦层执行（跨源）。
 *
 * <p>本类是 {@link JoinPushDownRule} 的核心依赖组件，对应 Calcite 联邦优化中
 * "source affinity analysis"阶段。识别逻辑基于 {@link CustomRelNode#collectSourceNames()}
 * 自底向上传播的数据源标识：</p>
 * <ul>
 *   <li><b>同源 Join（SAME_SOURCE）</b>：左右输入的所有数据源标识完全一致且唯一，
 *       Join 可整体下推到该数据源执行</li>
 *   <li><b>跨源 Join（CROSS_SOURCE）</b>：左右输入涉及至少两个不同数据源，
 *       Join 必须保留在联邦层，由联邦层选择 BroadcastJoin 或 ShuffleJoin 执行</li>
 *   <li><b>未知（UNKNOWN）</b>：左右输入均无数据源标识（如 Values 节点），
 *       默认按跨源处理以保守保证正确性</li>
 * </ul>
 *
 * <p><b>识别流程：</b></p>
 * <pre>
 *   Join(condition: t1.id = t2.id)
 *     ├─ left:  TableScan(t1, source=iceberg_lake)
 *     └─ right: TableScan(t2, source=doris_olap)
 *     │
 *     ▼  1. 收集 left 数据源 → {iceberg_lake}
 *     ▼  2. 收集 right 数据源 → {doris_olap}
 *     ▼  3. 合并去重 → {iceberg_lake, doris_olap}（size=2）
 *     ▼  4. 判定：size &gt; 1 → CROSS_SOURCE
 *   DetectionResult{type=CROSS_SOURCE, left=iceberg_lake, right=doris_olap}
 * </pre>
 *
 * <p><b>适配器映射：</b>本识别器持有一组 {@link BaseAdapter}，通过
 * {@link BaseAdapter#getDataSourceConfig()} 的 name 与 RelNode 的 sourceName 匹配，
 * 找到对应适配器后可进一步调用 {@link BaseAdapter#canPushDown} 做数据源特定的下推限制
 * （如 IoTDB 不支持复杂 Join）。</p>
 *
 * <p><b>统计信息：</b>每次 {@link #detect} 调用后累积统计到 {@link #statistics}，
 * 可通过 {@link #getStatistics()} 获取同源/跨源 Join 数量与下推率。</p>
 *
 * @author shuqing-bigdata
 */
public class CrossSourceJoinDetector {

    /** Join 数据源归属类型 */
    public enum JoinType {
        /** 同源 Join：左右输入来自同一数据源，可下推 */
        SAME_SOURCE("同源 Join，可下推到数据源执行"),
        /** 跨源 Join：左右输入来自不同数据源，保留在联邦层 */
        CROSS_SOURCE("跨源 Join，保留在联邦层执行"),
        /** 未知：左右输入无数据源标识，默认按跨源处理 */
        UNKNOWN("未知数据源，保守按跨源处理");

        private final String description;

        JoinType(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }

        /** 是否可下推到数据源执行 */
        public boolean isPushable() {
            return this == SAME_SOURCE;
        }
    }

    /** 已注册的数据源适配器：数据源名 → 适配器 */
    private final Map<String, BaseAdapter> adapterRegistry;

    /** 跨源 Join 识别统计器 */
    private final DetectorStatistics statistics;

    /**
     * 构造跨源 Join 识别器。
     *
     * @param adapters 已注册的数据源适配器列表（不可为 null）
     */
    public CrossSourceJoinDetector(List<BaseAdapter> adapters) {
        this(adapters, new DetectorStatistics());
    }

    /**
     * 构造跨源 Join 识别器（指定统计器）。
     *
     * @param adapters   已注册的数据源适配器列表
     * @param statistics 统计器
     */
    public CrossSourceJoinDetector(List<BaseAdapter> adapters, DetectorStatistics statistics) {
        Objects.requireNonNull(adapters, "adapters");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.adapterRegistry = new LinkedHashMap<>();
        for (BaseAdapter adapter : adapters) {
            if (adapter != null && adapter.getDataSourceConfig() != null
                    && adapter.getDataSourceConfig().getName() != null) {
                adapterRegistry.put(adapter.getDataSourceConfig().getName(), adapter);
            }
        }
    }

    /**
     * 获取已注册的适配器映射（只读视图）。
     *
     * @return 数据源名 → 适配器 的不可变映射
     */
    public Map<String, BaseAdapter> getAdapterRegistry() {
        return Collections.unmodifiableMap(adapterRegistry);
    }

    /**
     * 获取识别统计器。
     *
     * @return 统计器
     */
    public DetectorStatistics getStatistics() {
        return statistics;
    }

    /**
     * 根据 RelNode 子树收集其涉及的数据源名集合。
     *
     * <p>对 null 节点返回空集；对非 Join 节点直接调用
     * {@link CustomRelNode#collectSourceNames()}；对 Join 节点分别收集左右输入。</p>
     *
     * @param node RelNode 子树
     * @return 数据源名集合（去重、保序）
     */
    public Set<String> collectSources(CustomRelNode node) {
        if (node == null) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(node.collectSourceNames());
    }

    /**
     * 识别 Join 节点的数据源归属类型。
     *
     * <p>本方法假设传入节点为 {@code JOIN} 操作。若节点非 Join 或子节点不足 2 个，
     * 返回 {@link JoinType#UNKNOWN}。</p>
     *
     * @param joinNode Join 节点
     * @return 识别结果
     */
    public DetectionResult detect(CustomRelNode joinNode) {
        if (joinNode == null) {
            statistics.recordUnknown("Join 节点为 null");
            return DetectionResult.unknown("Join 节点为 null");
        }
        if (joinNode.getOp() != CustomRelNode.Op.JOIN) {
            statistics.recordUnknown("节点非 JOIN 操作: " + joinNode.getOp());
            return DetectionResult.unknown("节点非 JOIN 操作: " + joinNode.getOp());
        }
        List<CustomRelNode> children = joinNode.getChildren();
        if (children.size() < 2) {
            statistics.recordUnknown("Join 子节点不足 2 个: " + children.size());
            return DetectionResult.unknown("Join 子节点不足 2 个: " + children.size());
        }

        CustomRelNode left = children.get(0);
        CustomRelNode right = children.get(1);

        Set<String> leftSources = collectSources(left);
        Set<String> rightSources = collectSources(right);

        // 合并去重
        Set<String> allSources = new LinkedHashSet<>(leftSources);
        allSources.addAll(rightSources);

        DetectionResult result;
        if (leftSources.isEmpty() && rightSources.isEmpty()) {
            // 两边都没有数据源标识，按未知处理
            result = DetectionResult.unknown("左右输入均无数据源标识");
            statistics.recordUnknown(result.getReason());
        } else if (allSources.size() == 1) {
            // 只有一个数据源 → 同源 Join
            String source = allSources.iterator().next();
            BaseAdapter adapter = adapterRegistry.get(source);
            // 进一步检查适配器是否支持该 Join 下推
            if (adapter != null && !adapter.canPushDown(joinNode)) {
                result = DetectionResult.crossSource(source, null,
                        "数据源 " + source + " 适配器拒绝下推该 Join");
                statistics.recordCrossSource(source, null, result.getReason());
            } else {
                result = DetectionResult.sameSource(source, leftSources, rightSources);
                statistics.recordSameSource(source);
            }
        } else {
            // 多个数据源 → 跨源 Join
            String leftPrimary = leftSources.isEmpty() ? null : leftSources.iterator().next();
            String rightPrimary = rightSources.isEmpty() ? null : rightSources.iterator().next();
            result = DetectionResult.crossSource(leftPrimary, rightPrimary,
                    "左右输入涉及 " + allSources.size() + " 个数据源: " + allSources);
            statistics.recordCrossSource(leftPrimary, rightPrimary, result.getReason());
        }

        return result;
    }

    /**
     * 批量识别多个 Join 节点，返回每个 Join 的识别结果。
     *
     * @param joinNodes Join 节点列表
     * @return 识别结果列表（与输入一一对应）
     */
    public List<DetectionResult> detectAll(List<CustomRelNode> joinNodes) {
        if (joinNodes == null || joinNodes.isEmpty()) {
            return Collections.emptyList();
        }
        List<DetectionResult> results = new ArrayList<>(joinNodes.size());
        for (CustomRelNode join : joinNodes) {
            results.add(detect(join));
        }
        return results;
    }

    /**
     * 查找指定数据源名对应的适配器。
     *
     * @param sourceName 数据源名
     * @return 适配器，未注册时返回 null
     */
    public BaseAdapter findAdapter(String sourceName) {
        if (sourceName == null) {
            return null;
        }
        return adapterRegistry.get(sourceName);
    }

    /**
     * 判断两个 RelNode 子树是否来自同一数据源。
     *
     * @param left  左子树
     * @param right 右子树
     * @return {@code true} 表示同源（且非空）
     */
    public boolean isSameSource(CustomRelNode left, CustomRelNode right) {
        Set<String> leftSources = collectSources(left);
        Set<String> rightSources = collectSources(right);
        if (leftSources.isEmpty() || rightSources.isEmpty()) {
            return false;
        }
        Set<String> all = new LinkedHashSet<>(leftSources);
        all.addAll(rightSources);
        return all.size() == 1;
    }

    /**
     * Join 识别结果——封装数据源归属判定结论。
     *
     * @author shuqing-bigdata
     */
    public static class DetectionResult {
        /** Join 类型 */
        private final JoinType type;
        /** 左输入主数据源（可能为 null） */
        private final String leftSource;
        /** 右输入主数据源（可能为 null） */
        private final String rightSource;
        /** 唯一数据源（同源时非 null） */
        private final String source;
        /** 判定原因 */
        private final String reason;
        /** 左输入全部数据源 */
        private final Set<String> leftSources;
        /** 右输入全部数据源 */
        private final Set<String> rightSources;

        private DetectionResult(JoinType type, String leftSource, String rightSource,
                                String source, String reason,
                                Set<String> leftSources, Set<String> rightSources) {
            this.type = type;
            this.leftSource = leftSource;
            this.rightSource = rightSource;
            this.source = source;
            this.reason = reason;
            this.leftSources = leftSources == null ? Collections.emptySet() : leftSources;
            this.rightSources = rightSources == null ? Collections.emptySet() : rightSources;
        }

        /** 构造同源 Join 结果 */
        public static DetectionResult sameSource(String source,
                                                 Set<String> leftSources,
                                                 Set<String> rightSources) {
            return new DetectionResult(JoinType.SAME_SOURCE, source, source, source,
                    "同源 Join，数据源=" + source,
                    leftSources, rightSources);
        }

        /** 构造跨源 Join 结果 */
        public static DetectionResult crossSource(String leftSource, String rightSource,
                                                  String reason) {
            return new DetectionResult(JoinType.CROSS_SOURCE, leftSource, rightSource, null,
                    reason, Collections.emptySet(), Collections.emptySet());
        }

        /** 构造未知 Join 结果 */
        public static DetectionResult unknown(String reason) {
            return new DetectionResult(JoinType.UNKNOWN, null, null, null,
                    reason, Collections.emptySet(), Collections.emptySet());
        }

        public JoinType getType() {
            return type;
        }

        public String getLeftSource() {
            return leftSource;
        }

        public String getRightSource() {
            return rightSource;
        }

        /** 同源时返回唯一数据源名，跨源/未知返回 null */
        public String getSource() {
            return source;
        }

        public String getReason() {
            return reason;
        }

        public Set<String> getLeftSources() {
            return Collections.unmodifiableSet(leftSources);
        }

        public Set<String> getRightSources() {
            return Collections.unmodifiableSet(rightSources);
        }

        /** 是否同源可下推 */
        public boolean isPushable() {
            return type.isPushable();
        }

        /** 是否跨源 */
        public boolean isCrossSource() {
            return type == JoinType.CROSS_SOURCE;
        }

        @Override
        public String toString() {
            return "DetectionResult{type=" + type
                    + ", left=" + leftSource
                    + ", right=" + rightSource
                    + ", source=" + source
                    + ", reason='" + reason + "'}";
        }
    }

    /**
     * 跨源 Join 识别统计器——记录识别过程中的同源/跨源/未知 Join 数量。
     *
     * <p>提供以下维度指标：</p>
     * <ul>
     *   <li>总体下推率：同源 Join 数 / 总 Join 数</li>
     *   <li>按数据源分类：各数据源的同源 Join 数</li>
     *   <li>跨源原因列表：便于排查联邦层 Join</li>
     * </ul>
     *
     * @author shuqing-bigdata
     */
    public static class DetectorStatistics {
        private volatile int totalJoins = 0;
        private volatile int sameSourceJoins = 0;
        private volatile int crossSourceJoins = 0;
        private volatile int unknownJoins = 0;
        private final Map<String, Integer> sameSourceByAdapter = new LinkedHashMap<>();
        private final List<String> crossSourceReasons = Collections.synchronizedList(new ArrayList<>());

        /** 记录同源 Join */
        public synchronized void recordSameSource(String source) {
            totalJoins++;
            sameSourceJoins++;
            sameSourceByAdapter.merge(source, 1, Integer::sum);
        }

        /** 记录跨源 Join */
        public synchronized void recordCrossSource(String left, String right, String reason) {
            totalJoins++;
            crossSourceJoins++;
            if (reason != null) {
                crossSourceReasons.add(reason);
            }
        }

        /** 记录未知 Join */
        public synchronized void recordUnknown(String reason) {
            totalJoins++;
            unknownJoins++;
            if (reason != null) {
                crossSourceReasons.add("[UNKNOWN] " + reason);
            }
        }

        /** 总体下推率 = 同源 Join / 总 Join */
        public double getPushDownRate() {
            if (totalJoins == 0) {
                return 0.0;
            }
            return (double) sameSourceJoins / totalJoins;
        }

        public int getTotalJoins() {
            return totalJoins;
        }

        public int getSameSourceJoins() {
            return sameSourceJoins;
        }

        public int getCrossSourceJoins() {
            return crossSourceJoins;
        }

        public int getUnknownJoins() {
            return unknownJoins;
        }

        public Map<String, Integer> getSameSourceByAdapter() {
            return Collections.unmodifiableMap(sameSourceByAdapter);
        }

        public List<String> getCrossSourceReasons() {
            return Collections.unmodifiableList(crossSourceReasons);
        }

        /** 重置统计 */
        public synchronized void reset() {
            totalJoins = 0;
            sameSourceJoins = 0;
            crossSourceJoins = 0;
            unknownJoins = 0;
            sameSourceByAdapter.clear();
            crossSourceReasons.clear();
        }

        @Override
        public String toString() {
            return "DetectorStatistics{total=" + totalJoins
                    + ", same=" + sameSourceJoins
                    + ", cross=" + crossSourceJoins
                    + ", unknown=" + unknownJoins
                    + ", rate=" + String.format("%.4f", getPushDownRate()) + '}';
        }
    }
}