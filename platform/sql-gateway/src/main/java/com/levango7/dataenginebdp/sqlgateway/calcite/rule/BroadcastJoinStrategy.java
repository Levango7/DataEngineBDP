package com.levango7.dataenginebdp.sqlgateway.calcite.rule;

import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Broadcast Join 策略——为跨源 Join 选择最优的物理执行策略。
 *
 * <p>当 {@link CrossSourceJoinDetector} 判定 Join 为跨源（CROSS_SOURCE）时，
 * 本策略基于两侧表的统计信息（行数、平均行大小）选择：</p>
 * <ul>
 *   <li><b>BROADCAST</b>：小表（估算大小 &lt; {@link #broadcastThreshold}）自动 Broadcast
 *       到所有执行节点，与大表做本地 MapJoin，避免 Shuffle 网络开销</li>
 *   <li><b>SHUFFLE</b>：两表均较大时，按 Join Key Hash Shuffle 后做 PartitionJoin</li>
 *   <li><b>REPLICATED</b>：极小表（&lt; threshold/10）直接复制到每个节点，
 *       与 BROADCAST 类似但语义更明确（用于星型查询的维度表）</li>
 * </ul>
 *
 * <p><b>策略选择算法：</b></p>
 * <pre>
 *   leftSize  = leftRows  × leftAvgRowSize
 *   rightSize = rightRows × rightAvgRowSize
 *
 *   if (min(leftSize, rightSize) &lt; threshold / 10) → REPLICATED（极小表）
 *   else if (min(leftSize, rightSize) &lt; threshold) → BROADCAST（小表）
 *   else → SHUFFLE（大表）
 *
 *   broadcastSide = (leftSize &lt; rightSize) ? LEFT : RIGHT
 * </pre>
 *
 * <p><b>典型场景：</b></p>
 * <ul>
 *   <li>事实表（千万级）JOIN 维度表（&lt;100MB）→ BROADCAST 维度表</li>
 *   <li>大表 JOIN 大表 → SHUFFLE</li>
 *   <li>大表 JOIN 配置表（&lt;10MB）→ REPLICATED 配置表</li>
 * </ul>
 *
 * <p><b>统计信息来源：</b>本策略通过 {@link TableStatistics} 接口获取表的行数与平均行大小。
 * 若统计信息缺失，默认按大表处理（保守选择 SHUFFLE）。</p>
 *
 * <p><b>可配置阈值：</b>{@link #broadcastThreshold} 默认 100MB（{@link #DEFAULT_BROADCAST_THRESHOLD}），
 * 可通过构造函数或配置文件调整。{@link #replicatedThreshold} 默认为 broadcastThreshold / 10。</p>
 *
 * @author shuqing-bigdata
 */
public class BroadcastJoinStrategy {

    /** 默认 Broadcast 阈值：100MB（单位：字节） */
    public static final long DEFAULT_BROADCAST_THRESHOLD = 100L * 1024 * 1024;

    /** 默认平均行大小（当统计信息缺失时使用）：256 字节 */
    public static final long DEFAULT_AVG_ROW_SIZE = 256L;

    /** Broadcast 阈值（字节） */
    private final long broadcastThreshold;

    /** Replicated 阈值（字节），小于此值使用 REPLICATED 策略 */
    private final long replicatedThreshold;

    /** 表统计信息提供者 */
    private final TableStatistics tableStatistics;

    /** 策略统计器 */
    private final StrategyStatistics statistics;

    /**
     * 构造 BroadcastJoin 策略（默认阈值 100MB）。
     *
     * @param tableStatistics 表统计信息提供者
     */
    public BroadcastJoinStrategy(TableStatistics tableStatistics) {
        this(tableStatistics, DEFAULT_BROADCAST_THRESHOLD, new StrategyStatistics());
    }

    /**
     * 构造 BroadcastJoin 策略（指定阈值）。
     *
     * @param tableStatistics    表统计信息提供者
     * @param broadcastThreshold Broadcast 阈值（字节）
     */
    public BroadcastJoinStrategy(TableStatistics tableStatistics, long broadcastThreshold) {
        this(tableStatistics, broadcastThreshold, new StrategyStatistics());
    }

    /**
     * 构造 BroadcastJoin 策略（完整参数）。
     *
     * @param tableStatistics    表统计信息提供者
     * @param broadcastThreshold Broadcast 阈值（字节）
     * @param statistics         策略统计器
     */
    public BroadcastJoinStrategy(TableStatistics tableStatistics, long broadcastThreshold,
                                 StrategyStatistics statistics) {
        this.tableStatistics = Objects.requireNonNull(tableStatistics, "tableStatistics");
        if (broadcastThreshold <= 0) {
            throw new IllegalArgumentException("broadcastThreshold 必须为正: " + broadcastThreshold);
        }
        this.broadcastThreshold = broadcastThreshold;
        this.replicatedThreshold = broadcastThreshold / 10;
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    /**
     * 获取 Broadcast 阈值（字节）。
     *
     * @return 阈值
     */
    public long getBroadcastThreshold() {
        return broadcastThreshold;
    }

    /**
     * 获取 Replicated 阈值（字节）。
     *
     * @return 阈值
     */
    public long getReplicatedThreshold() {
        return replicatedThreshold;
    }

    /**
     * 获取策略统计器。
     *
     * @return 统计器
     */
    public StrategyStatistics getStatistics() {
        return statistics;
    }

    /**
     * 为 Join 节点选择最优执行策略。
     *
     * <p>本方法假设传入节点为 {@code JOIN} 操作，且已判定为跨源 Join。
     * 基于 {@link #tableStatistics} 获取左右表的行数与平均行大小，按策略算法选择
     * BROADCAST / SHUFFLE / REPLICATED。</p>
     *
     * @param joinNode Join 节点
     * @return 策略选择结果
     */
    public StrategyResult chooseStrategy(CustomRelNode joinNode) {
        if (joinNode == null || joinNode.getOp() != CustomRelNode.Op.JOIN) {
            return StrategyResult.failure("节点为 null 或非 JOIN 操作");
        }
        List<CustomRelNode> children = joinNode.getChildren();
        if (children.size() < 2) {
            return StrategyResult.failure("Join 子节点不足 2 个");
        }

        CustomRelNode left = children.get(0);
        CustomRelNode right = children.get(1);

        TableStat leftStat = estimateTableStat(left);
        TableStat rightStat = estimateTableStat(right);

        long leftSize = leftStat.estimatedSize();
        long rightSize = rightStat.estimatedSize();

        long minSize = Math.min(leftSize, rightSize);
        long maxSize = Math.max(leftSize, rightSize);

        JoinStrategy strategy;
        BroadcastSide broadcastSide;

        if (minSize < replicatedThreshold) {
            // 极小表 → REPLICATED
            strategy = JoinStrategy.REPLICATED;
            broadcastSide = (leftSize <= rightSize) ? BroadcastSide.LEFT : BroadcastSide.RIGHT;
        } else if (minSize < broadcastThreshold) {
            // 小表 → BROADCAST
            strategy = JoinStrategy.BROADCAST;
            broadcastSide = (leftSize <= rightSize) ? BroadcastSide.LEFT : BroadcastSide.RIGHT;
        } else {
            // 大表 → SHUFFLE
            strategy = JoinStrategy.SHUFFLE;
            broadcastSide = BroadcastSide.NONE;
        }

        String reason = String.format(
                "leftSize=%d bytes (%d rows × %d avgRowSize), rightSize=%d bytes (%d rows × %d avgRowSize), "
                        + "minSize=%d, threshold=%d → %s",
                leftSize, leftStat.rows, leftStat.avgRowSize,
                rightSize, rightStat.rows, rightStat.avgRowSize,
                minSize, broadcastThreshold, strategy);

        statistics.recordStrategy(strategy, broadcastSide, maxSize, minSize);

        return new StrategyResult(strategy, broadcastSide,
                leftStat, rightStat, leftSize, rightSize, reason, true, null);
    }

    /**
     * 估算 RelNode 子树的表统计信息。
     *
     * <p>对 TableScan 节点通过 {@link #tableStatistics} 查询；对其他节点
     * 递归向下找第一个 TableScan；找不到时使用默认值。</p>
     *
     * @param node RelNode 子树
     * @return 表统计信息
     */
    private TableStat estimateTableStat(CustomRelNode node) {
        if (node == null) {
            return TableStat.unknown();
        }
        if (node.getOp() == CustomRelNode.Op.TABLE_SCAN && node.getTableName() != null) {
            long rows = tableStatistics.getRowCount(node.getTableName());
            long avgRowSize = tableStatistics.getAvgRowSize(node.getTableName());
            if (rows < 0) {
                rows = 1_000_000L; // 默认 100 万行
            }
            if (avgRowSize < 0) {
                avgRowSize = DEFAULT_AVG_ROW_SIZE;
            }
            return new TableStat(node.getTableName(), rows, avgRowSize);
        }
        // 递归向下找第一个 TableScan
        for (CustomRelNode child : node.getChildren()) {
            TableStat stat = estimateTableStat(child);
            if (stat != TableStat.UNKNOWN) {
                return stat;
            }
        }
        return TableStat.unknown();
    }

    /**
     * 判断指定表是否适合 Broadcast（小于阈值）。
     *
     * @param tableName 表名
     * @return {@code true} 表示该表估算大小 &lt; broadcastThreshold
     */
    public boolean isBroadcastable(String tableName) {
        long rows = tableStatistics.getRowCount(tableName);
        long avgRowSize = tableStatistics.getAvgRowSize(tableName);
        if (rows < 0 || avgRowSize < 0) {
            return false;
        }
        return rows * avgRowSize < broadcastThreshold;
    }

    /**
     * 估算表大小（字节）。
     *
     * @param tableName 表名
     * @return 估算大小，统计信息缺失时返回 -1
     */
    public long estimateTableSize(String tableName) {
        long rows = tableStatistics.getRowCount(tableName);
        long avgRowSize = tableStatistics.getAvgRowSize(tableName);
        if (rows < 0 || avgRowSize < 0) {
            return -1;
        }
        return rows * avgRowSize;
    }

    // ===================== 枚举与内部类 =====================

    /** Join 物理执行策略 */
    public enum JoinStrategy {
        /** Broadcast 小表到所有节点，本地 MapJoin */
        BROADCAST("Broadcast 小表到所有节点，与大表做本地 Join"),
        /** Shuffle 两表按 Join Key Hash，PartitionJoin */
        SHUFFLE("两表均较大，按 Join Key Hash Shuffle 后做 PartitionJoin"),
        /** Replicated 极小表复制到每个节点（星型查询维度表） */
        REPLICATED("极小表复制到每个节点，类似 Broadcast 但语义更明确");

        private final String description;

        JoinStrategy(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }

        /** 是否为 Broadcast 类策略（含 REPLICATED） */
        public boolean isBroadcastLike() {
            return this == BROADCAST || this == REPLICATED;
        }
    }

    /** Broadcast 侧 */
    public enum BroadcastSide {
        /** 广播左表 */
        LEFT,
        /** 广播右表 */
        RIGHT,
        /** 不广播（SHUFFLE 策略） */
        NONE
    }

    /** 表统计信息快照 */
    public static class TableStat {
        public static final TableStat UNKNOWN = new TableStat(null, -1, -1);

        private final String tableName;
        private final long rows;
        private final long avgRowSize;

        public TableStat(String tableName, long rows, long avgRowSize) {
            this.tableName = tableName;
            this.rows = rows;
            this.avgRowSize = avgRowSize;
        }

        public static TableStat unknown() {
            return UNKNOWN;
        }

        public String getTableName() {
            return tableName;
        }

        public long getRows() {
            return rows;
        }

        public long getAvgRowSize() {
            return avgRowSize;
        }

        /** 估算表大小（字节） */
        public long estimatedSize() {
            if (rows < 0 || avgRowSize < 0) {
                return Long.MAX_VALUE; // 未知时按极大值处理，触发 SHUFFLE
            }
            return rows * avgRowSize;
        }

        public boolean isUnknown() {
            return rows < 0 || avgRowSize < 0;
        }

        @Override
        public String toString() {
            return "TableStat{" + tableName + ", rows=" + rows + ", avgRowSize=" + avgRowSize + '}';
        }
    }

    /** 策略选择结果 */
    public static class StrategyResult {
        private final JoinStrategy strategy;
        private final BroadcastSide broadcastSide;
        private final TableStat leftStat;
        private final TableStat rightStat;
        private final long leftSize;
        private final long rightSize;
        private final String reason;
        private final boolean success;
        private final String failureReason;

        public StrategyResult(JoinStrategy strategy, BroadcastSide broadcastSide,
                              TableStat leftStat, TableStat rightStat,
                              long leftSize, long rightSize,
                              String reason, boolean success, String failureReason) {
            this.strategy = strategy;
            this.broadcastSide = broadcastSide;
            this.leftStat = leftStat;
            this.rightStat = rightStat;
            this.leftSize = leftSize;
            this.rightSize = rightSize;
            this.reason = reason;
            this.success = success;
            this.failureReason = failureReason;
        }

        public static StrategyResult failure(String reason) {
            return new StrategyResult(null, BroadcastSide.NONE, null, null,
                    0, 0, null, false, reason);
        }

        public JoinStrategy getStrategy() {
            return strategy;
        }

        public BroadcastSide getBroadcastSide() {
            return broadcastSide;
        }

        public TableStat getLeftStat() {
            return leftStat;
        }

        public TableStat getRightStat() {
            return rightStat;
        }

        public long getLeftSize() {
            return leftSize;
        }

        public long getRightSize() {
            return rightSize;
        }

        public String getReason() {
            return reason;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getFailureReason() {
            return failureReason;
        }

        /** 是否为 Broadcast 类策略 */
        public boolean isBroadcast() {
            return success && strategy.isBroadcastLike();
        }

        @Override
        public String toString() {
            if (!success) {
                return "StrategyResult{FAILURE: " + failureReason + "}";
            }
            return "StrategyResult{" + strategy
                    + ", side=" + broadcastSide
                    + ", leftSize=" + leftSize
                    + ", rightSize=" + rightSize + '}';
        }
    }

    /**
     * 表统计信息接口——提供行数与平均行大小查询。
     *
     * <p>实现可对接 Calcite {@code RelMetadataQuery}、数据源统计表、
     * 或外部统计服务（如 Hive Metastore、Doris FE 统计信息）。</p>
     *
     * @author shuqing-bigdata
     */
    public interface TableStatistics {
        /**
         * 获取表行数估算。
         *
         * @param tableName 表名
         * @return 行数，缺失时返回 -1
         */
        long getRowCount(String tableName);

        /**
         * 获取表平均行大小（字节）。
         *
         * @param tableName 表名
         * @return 平均行大小，缺失时返回 -1
         */
        long getAvgRowSize(String tableName);
    }

    /**
     * 策略统计器——记录策略选择过程中的统计信息。
     *
     * @author shuqing-bigdata
     */
    public static class StrategyStatistics {
        private volatile int totalDecisions = 0;
        private volatile int broadcastCount = 0;
        private volatile int shuffleCount = 0;
        private volatile int replicatedCount = 0;
        private volatile long maxBroadcastSize = 0;
        private volatile long minShuffleSize = Long.MAX_VALUE;
        private final Map<JoinStrategy, Integer> strategyCount = new LinkedHashMap<>();

        public StrategyStatistics() {
            for (JoinStrategy s : JoinStrategy.values()) {
                strategyCount.put(s, 0);
            }
        }

        public synchronized void recordStrategy(JoinStrategy strategy, BroadcastSide side,
                                                long maxSize, long minSize) {
            totalDecisions++;
            strategyCount.merge(strategy, 1, Integer::sum);
            switch (strategy) {
                case BROADCAST -> {
                    broadcastCount++;
                    if (minSize > maxBroadcastSize) {
                        maxBroadcastSize = minSize;
                    }
                }
                case SHUFFLE -> {
                    shuffleCount++;
                    if (maxSize < minShuffleSize) {
                        minShuffleSize = maxSize;
                    }
                }
                case REPLICATED -> replicatedCount++;
            }
        }

        /** Broadcast 使用率 */
        public double getBroadcastRate() {
            if (totalDecisions == 0) {
                return 0.0;
            }
            return (double) (broadcastCount + replicatedCount) / totalDecisions;
        }

        public int getTotalDecisions() {
            return totalDecisions;
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

        public long getMaxBroadcastSize() {
            return maxBroadcastSize;
        }

        public long getMinShuffleSize() {
            return minShuffleSize == Long.MAX_VALUE ? 0 : minShuffleSize;
        }

        public Map<JoinStrategy, Integer> getStrategyCount() {
            return Collections.unmodifiableMap(strategyCount);
        }

        public synchronized void reset() {
            totalDecisions = 0;
            broadcastCount = 0;
            shuffleCount = 0;
            replicatedCount = 0;
            maxBroadcastSize = 0;
            minShuffleSize = Long.MAX_VALUE;
            for (JoinStrategy s : JoinStrategy.values()) {
                strategyCount.put(s, 0);
            }
        }

        @Override
        public String toString() {
            return "StrategyStatistics{total=" + totalDecisions
                    + ", broadcast=" + broadcastCount
                    + ", shuffle=" + shuffleCount
                    + ", replicated=" + replicatedCount
                    + ", broadcastRate=" + String.format("%.2f%%", getBroadcastRate() * 100) + '}';
        }
    }
}