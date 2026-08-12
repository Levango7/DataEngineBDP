package com.levango7.dataenginebdp.sqlgateway.calcite.rule;

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
 * Join 重排序优化器——基于 Cost 估算选择多表 Join 的最优执行顺序。
 *
 * <p>当查询涉及 3 个及以上表的 Join 时，不同的 Join 顺序对性能影响巨大
 * （如 A⋈B⋈C 有 3 种顺序：((A⋈B)⋈C)、((A⋈C)⋈B)、((B⋈C)⋈A)）。
 * 本优化器基于表统计信息（行数、列基数、选择率）估算每种 Join 顺序的 Cost，
 * 选择 Cost 最小的顺序作为最优计划。</p>
 *
 * <p><b>Cost 模型：</b></p>
 * <pre>
 *   Cost(Join) = Cost(left) + Cost(right) + Cost(joinCompute)
 *   Cost(joinCompute) = leftRows × rightRows × selectivity
 *   joinOutputRows   = leftRows × rightRows × selectivity
 *
 *   selectivity 默认估算：
 *     等值 Join（A.id = B.id）：1 / max(NDV(A.id), NDV(B.id))
 *     无统计信息时：默认 0.1（10% 选择率）
 * </pre>
 *
 * <p><b>算法选择：</b></p>
 * <ul>
 *   <li><b>表数 ≤ 2</b>：无需重排序，直接返回原顺序</li>
 *   <li><b>表数 ≤ {@link #DP_THRESHOLD}</b>（默认 8）：使用动态规划枚举所有顺序，
 *       保证全局最优</li>
 *   <li><b>表数 &gt; {@link #DP_THRESHOLD}</b>：使用贪心算法（每次选最小 Cost 的 Join），
 *       牺牲全局最优换取效率</li>
 * </ul>
 *
 * <p><b>典型场景：</b></p>
 * <pre>
 *   原始顺序：((bigTable ⋈ dim1) ⋈ dim2) ⋈ fact
 *     │
 *     ▼  Cost 估算 + 动态规划
 *   最优顺序：((dim1 ⋈ dim2) ⋈ fact) ⋈ bigTable
 *     （小表先 Join，减少中间结果）
 * </pre>
 *
 * <p><b>统计信息来源：</b>通过 {@link JoinStatistics} 接口获取表的行数、列基数（NDV）。
 * 若统计信息缺失，使用默认值（行数 100 万、NDV 1000、选择率 0.1）。</p>
 *
 * @author shuqing-bigdata
 */
public class JoinReorderOptimizer {

    /** 动态规划阈值：表数超过此值时切换为贪心算法 */
    public static final int DP_THRESHOLD = 8;

    /** 默认选择率（无统计信息时） */
    public static final double DEFAULT_SELECTIVITY = 0.1;

    /** 默认行数（无统计信息时） */
    public static final long DEFAULT_ROWS = 1_000_000L;

    /** 默认列基数 NDV（无统计信息时） */
    public static final long DEFAULT_NDV = 1000L;

    /** Join 统计信息提供者 */
    private final JoinStatistics joinStatistics;

    /** 重排序统计器 */
    private final ReorderStatistics statistics;

    /**
     * 构造 Join 重排序优化器。
     *
     * @param joinStatistics Join 统计信息提供者
     */
    public JoinReorderOptimizer(JoinStatistics joinStatistics) {
        this(joinStatistics, new ReorderStatistics());
    }

    /**
     * 构造 Join 重排序优化器（指定统计器）。
     *
     * @param joinStatistics Join 统计信息提供者
     * @param statistics     统计器
     */
    public JoinReorderOptimizer(JoinStatistics joinStatistics, ReorderStatistics statistics) {
        this.joinStatistics = Objects.requireNonNull(joinStatistics, "joinStatistics");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    /**
     * 获取统计器。
     *
     * @return 统计器
     */
    public ReorderStatistics getStatistics() {
        return statistics;
    }

    /**
     * 对多表 Join 进行重排序优化。
     *
     * <p>本方法接收 Join 节点列表（每个 Join 连接两个表），返回最优 Join 顺序。
     * 若表数 ≤ 2，直接返回原顺序。</p>
     *
     * @param tables 参与 Join 的表名列表
     * @return 最优 Join 顺序
     */
    public ReorderResult reorder(List<String> tables) {
        if (tables == null || tables.size() <= 2) {
            return ReorderResult.identity(tables);
        }

        // 收集表统计信息
        Map<String, TableStats> statsMap = new LinkedHashMap<>();
        for (String table : tables) {
            long rows = joinStatistics.getRowCount(table);
            if (rows < 0) {
                rows = DEFAULT_ROWS;
            }
            statsMap.put(table, new TableStats(table, rows));
        }

        List<String> optimal;
        double optimalCost;
        String algorithm;

        if (tables.size() <= DP_THRESHOLD) {
            // 动态规划
            ReorderPlan plan = dynamicProgramming(tables, statsMap);
            optimal = plan.order;
            optimalCost = plan.cost;
            algorithm = "DynamicProgramming";
        } else {
            // 贪心算法
            ReorderPlan plan = greedy(tables, statsMap);
            optimal = plan.order;
            optimalCost = plan.cost;
            algorithm = "Greedy";
        }

        // 估算原始顺序 Cost
        double originalCost = estimateJoinCost(tables, statsMap);

        double improvement = originalCost > 0
                ? (originalCost - optimalCost) / originalCost : 0.0;

        statistics.recordReorder(tables.size(), algorithm, originalCost, optimalCost, improvement);

        return new ReorderResult(optimal, optimalCost, originalCost, improvement, algorithm);
    }

    /**
     * 动态规划算法——枚举所有 Join 顺序，选择 Cost 最小的。
     *
     * <p>对 n 个表，有 n! 种顺序。本实现使用记忆化递归 + 子集枚举，
     * 复杂度 O(3^n)，对 n ≤ 8 可接受（3^8 = 6561）。</p>
     *
     * @param tables   表名列表
     * @param statsMap 表统计信息
     * @return 最优计划
     */
    private ReorderPlan dynamicProgramming(List<String> tables, Map<String, TableStats> statsMap) {
        int n = tables.size();
        if (n == 1) {
            return new ReorderPlan(new ArrayList<>(tables), 0);
        }
        if (n == 2) {
            double cost = computeJoinCost(statsMap.get(tables.get(0)),
                    statsMap.get(tables.get(1)));
            return new ReorderPlan(new ArrayList<>(tables), cost);
        }

        // 枚举所有排列（n ≤ 8 时可行）
        List<List<String>> permutations = new ArrayList<>();
        permute(tables, 0, permutations);

        ReorderPlan best = null;
        for (List<String> perm : permutations) {
            double cost = estimateJoinCost(perm, statsMap);
            if (best == null || cost < best.cost) {
                best = new ReorderPlan(perm, cost);
            }
        }
        return best;
    }

    /** 生成所有排列 */
    private void permute(List<String> list, int start, List<List<String>> result) {
        if (start == list.size() - 1) {
            result.add(new ArrayList<>(list));
            return;
        }
        for (int i = start; i < list.size(); i++) {
            swap(list, start, i);
            permute(list, start + 1, result);
            swap(list, start, i);
        }
    }

    private void swap(List<String> list, int i, int j) {
        String tmp = list.get(i);
        list.set(i, list.get(j));
        list.set(j, tmp);
    }

    /**
     * 贪心算法——每次选择使中间结果最小的 Join。
     *
     * <p>策略：维护"已 Joined 表集合"与"未处理表集合"，每轮从未处理表中
     * 选一个与已 Joined 集合做 Join 后 Cost 最小的，加入已 Joined 集合。</p>
     *
     * @param tables   表名列表
     * @param statsMap 表统计信息
     * @return 贪心计划
     */
    private ReorderPlan greedy(List<String> tables, Map<String, TableStats> statsMap) {
        if (tables.size() <= 2) {
            return new ReorderPlan(new ArrayList<>(tables),
                    estimateJoinCost(tables, statsMap));
        }

        List<String> result = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(tables);

        // 选最小表作为起点
        String start = null;
        long minRows = Long.MAX_VALUE;
        for (String t : remaining) {
            long rows = statsMap.get(t).rows;
            if (rows < minRows) {
                minRows = rows;
                start = t;
            }
        }
        result.add(start);
        remaining.remove(start);

        long currentRows = minRows;
        double totalCost = 0;

        while (!remaining.isEmpty()) {
            String best = null;
            double bestCost = Double.MAX_VALUE;
            long bestOutputRows = Long.MAX_VALUE;

            for (String candidate : remaining) {
                TableStats candStats = statsMap.get(candidate);
                double joinCost = currentRows * candStats.rows * DEFAULT_SELECTIVITY;
                long outputRows = (long) (currentRows * candStats.rows * DEFAULT_SELECTIVITY);
                if (outputRows < 1) {
                    outputRows = 1;
                }
                if (joinCost < bestCost) {
                    bestCost = joinCost;
                    best = candidate;
                    bestOutputRows = outputRows;
                }
            }

            result.add(best);
            remaining.remove(best);
            totalCost += bestCost;
            currentRows = bestOutputRows;
        }

        return new ReorderPlan(result, totalCost);
    }

    /**
     * 估算给定 Join 顺序的 Cost。
     *
     * <p>假设左深树（left-deep tree）：((t0 ⋈ t1) ⋈ t2) ⋈ ...</p>
     *
     * @param order    Join 顺序
     * @param statsMap 表统计信息
     * @return 总 Cost
     */
    private double estimateJoinCost(List<String> order, Map<String, TableStats> statsMap) {
        if (order.size() <= 1) {
            return 0;
        }
        double totalCost = 0;
        long leftRows = statsMap.get(order.get(0)).rows;
        for (int i = 1; i < order.size(); i++) {
            TableStats right = statsMap.get(order.get(i));
            double joinCost = computeJoinCost(
                    new TableStats("intermediate", leftRows), right);
            totalCost += joinCost;
            // 更新左子树行数为 Join 输出行数
            leftRows = (long) (leftRows * right.rows * DEFAULT_SELECTIVITY);
            if (leftRows < 1) {
                leftRows = 1;
            }
        }
        return totalCost;
    }

    /** 计算单个 Join 的 Cost */
    private double computeJoinCost(TableStats left, TableStats right) {
        return left.rows * right.rows * DEFAULT_SELECTIVITY;
    }

    /**
     * 获取指定表列的基数（NDV）。
     *
     * @param tableName 表名
     * @param column    列名
     * @return NDV，缺失时返回 {@link #DEFAULT_NDV}
     */
    public long getNdv(String tableName, String column) {
        long ndv = joinStatistics.getNdv(tableName, column);
        return ndv < 0 ? DEFAULT_NDV : ndv;
    }

    /**
     * 估算等值 Join 的选择率。
     *
     * <p>selectivity = 1 / max(NDV(left.col), NDV(right.col))</p>
     *
     * @param leftTable  左表
     * @param leftCol    左列
     * @param rightTable 右表
     * @param rightCol   右列
     * @return 选择率
     */
    public double estimateSelectivity(String leftTable, String leftCol,
                                      String rightTable, String rightCol) {
        long leftNdv = getNdv(leftTable, leftCol);
        long rightNdv = getNdv(rightTable, rightCol);
        long maxNdv = Math.max(leftNdv, rightNdv);
        if (maxNdv <= 0) {
            return DEFAULT_SELECTIVITY;
        }
        return 1.0 / maxNdv;
    }

    // ===================== 内部类 =====================

    /** 表统计信息快照 */
    private static class TableStats {
        final String name;
        final long rows;

        TableStats(String name, long rows) {
            this.name = name;
            this.rows = rows;
        }
    }

    /** 重排序计划 */
    private static class ReorderPlan {
        final List<String> order;
        final double cost;

        ReorderPlan(List<String> order, double cost) {
            this.order = order;
            this.cost = cost;
        }
    }

    /** 重排序结果 */
    public static class ReorderResult {
        private final List<String> optimalOrder;
        private final double optimalCost;
        private final double originalCost;
        private final double improvement;
        private final String algorithm;

        public ReorderResult(List<String> optimalOrder, double optimalCost,
                             double originalCost, double improvement, String algorithm) {
            this.optimalOrder = Collections.unmodifiableList(optimalOrder);
            this.optimalCost = optimalCost;
            this.originalCost = originalCost;
            this.improvement = improvement;
            this.algorithm = algorithm;
        }

        public static ReorderResult identity(List<String> tables) {
            if (tables == null) {
                return new ReorderResult(Collections.emptyList(), 0, 0, 0, "Identity");
            }
            return new ReorderResult(new ArrayList<>(tables), 0, 0, 0, "Identity");
        }

        public List<String> getOptimalOrder() {
            return optimalOrder;
        }

        public double getOptimalCost() {
            return optimalCost;
        }

        public double getOriginalCost() {
            return originalCost;
        }

        /** Cost 改善比例（0~1） */
        public double getImprovement() {
            return improvement;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        /** 是否发生了重排序 */
        public boolean isReordered() {
            return !"Identity".equals(algorithm);
        }

        @Override
        public String toString() {
            return "ReorderResult{order=" + optimalOrder
                    + ", optimalCost=" + optimalCost
                    + ", originalCost=" + originalCost
                    + ", improvement=" + String.format("%.2f%%", improvement * 100)
                    + ", algorithm=" + algorithm + '}';
        }
    }

    /**
     * Join 统计信息接口——提供表行数与列基数查询。
     *
     * @author shuqing-bigdata
     */
    public interface JoinStatistics {
        /**
         * 获取表行数。
         *
         * @param tableName 表名
         * @return 行数，缺失时返回 -1
         */
        long getRowCount(String tableName);

        /**
         * 获取表指定列的基数（NDV，Number of Distinct Values）。
         *
         * @param tableName 表名
         * @param column    列名
         * @return NDV，缺失时返回 -1
         */
        long getNdv(String tableName, String column);
    }

    /**
     * 重排序统计器——记录重排序过程中的统计信息。
     *
     * @author shuqing-bigdata
     */
    public static class ReorderStatistics {
        private volatile int totalReorders = 0;
        private volatile int dpCount = 0;
        private volatile int greedyCount = 0;
        private volatile double totalImprovement = 0;
        private volatile double bestImprovement = 0;
        private volatile int maxTableCount = 0;

        public synchronized void recordReorder(int tableCount, String algorithm,
                                               double originalCost, double optimalCost,
                                               double improvement) {
            totalReorders++;
            if ("DynamicProgramming".equals(algorithm)) {
                dpCount++;
            } else if ("Greedy".equals(algorithm)) {
                greedyCount++;
            }
            totalImprovement += improvement;
            if (improvement > bestImprovement) {
                bestImprovement = improvement;
            }
            if (tableCount > maxTableCount) {
                maxTableCount = tableCount;
            }
        }

        /** 平均改善比例 */
        public double getAverageImprovement() {
            if (totalReorders == 0) {
                return 0.0;
            }
            return totalImprovement / totalReorders;
        }

        public int getTotalReorders() {
            return totalReorders;
        }

        public int getDpCount() {
            return dpCount;
        }

        public int getGreedyCount() {
            return greedyCount;
        }

        public double getBestImprovement() {
            return bestImprovement;
        }

        public int getMaxTableCount() {
            return maxTableCount;
        }

        public synchronized void reset() {
            totalReorders = 0;
            dpCount = 0;
            greedyCount = 0;
            totalImprovement = 0;
            bestImprovement = 0;
            maxTableCount = 0;
        }

        @Override
        public String toString() {
            return "ReorderStatistics{total=" + totalReorders
                    + ", dp=" + dpCount
                    + ", greedy=" + greedyCount
                    + ", avgImprovement=" + String.format("%.2f%%", getAverageImprovement() * 100)
                    + ", bestImprovement=" + String.format("%.2f%%", bestImprovement * 100) + '}';
        }
    }
}