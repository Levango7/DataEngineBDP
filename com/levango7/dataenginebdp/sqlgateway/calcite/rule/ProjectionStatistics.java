package com.shuqing.bigdata.sqlgateway.calcite.rule;

import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 投影下推（列裁剪）统计器——记录投影下推规则的执行统计信息。
 *
 * <p>本类在 {@link ProjectPushDownRule} 执行过程中累积统计，提供以下维度的指标：</p>
 * <ul>
 *   <li><b>总体列裁剪率</b>：已裁剪列数 / 总列数（即 1 - 保留列数/总列数）</li>
 *   <li><b>按数据源分类</b>：Iceberg/Doris/Trino/IoTDB/ES 各数据源的列裁剪率</li>
 *   <li><b>数据传输减少率</b>：列裁剪后传输量减少比例（≈列裁剪率，按列等宽假设）</li>
 *   <li><b>嵌套投影合并次数</b>：Project→Project 自动合并的次数</li>
 *   <li><b>下推次数</b>：成功下投影裁剪的次数</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>
 *   ProjectionStatistics stats = new ProjectionStatistics();
 *   stats.recordProjection(sourceType, 10, 3);  // 10 列裁剪为 3 列
 *   double reductionRate = stats.getColumnReductionRate();  // 0.7
 *   double transferRate = stats.getDataTransferReductionRate();  // 0.7
 * </pre>
 *
 * <p>本类线程安全（使用 {@link AtomicInteger} 计数），可在并发优化场景中使用。</p>
 *
 * @author shuqing-bigdata
 */
public class ProjectionStatistics {

    /** 总列数（所有下推记录的累积） */
    private final AtomicInteger totalColumns = new AtomicInteger(0);
    /** 保留列数（下推后实际读取的列数） */
    private final AtomicInteger retainedColumns = new AtomicInteger(0);
    /** 裁剪列数（被下推裁剪掉的列数） */
    private final AtomicInteger prunedColumns = new AtomicInteger(0);

    /** 下推成功次数（每次 onMatch 触发一次下推） */
    private final AtomicInteger pushDownCount = new AtomicInteger(0);
    /** 嵌套投影合并次数（Project→Project 合并） */
    private final AtomicInteger mergeCount = new AtomicInteger(0);
    /** 跳过次数（无需下推，如 SELECT * 或全列引用） */
    private final AtomicInteger skipCount = new AtomicInteger(0);

    /** 按数据源类型统计：数据源类型 → [总列数, 保留列数] */
    private final Map<DataSourceConfig.Type, int[]> sourceStats = new LinkedHashMap<>();
    /** 已下推投影描述列表（如 "users: [id, name, age] -> [name]"） */
    private final List<String> pushedDescriptions =
            Collections.synchronizedList(new java.util.ArrayList<>());
    /** 跳过原因列表 */
    private final List<String> skipReasons =
            Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * 构造空的统计器，初始化所有数据源类型的计数桶。
     */
    public ProjectionStatistics() {
        for (DataSourceConfig.Type type : DataSourceConfig.Type.values()) {
            sourceStats.put(type, new int[]{0, 0});
        }
    }

    /**
     * 记算裁剪列数（总列数 - 保留列数，非负）。
     *
     * @param totalColumns    总列数
     * @param retainedColumns 保留列数
     * @return 裁剪列数（≥ 0）
     */
    private static int computePruned(int totalColumns, int retainedColumns) {
        return Math.max(0, totalColumns - retainedColumns);
    }

    /**
     * 记算列裁剪率（裁剪列数 / 总列数）。
     *
     * @param totalColumns    总列数
     * @param retainedColumns 保留列数
     * @return 列裁剪率，范围 [0.0, 1.0]，总列数为 0 时返回 0.0
     */
    private static double computeReductionRate(int totalColumns, int retainedColumns) {
        if (totalColumns <= 0) {
            return 0.0;
        }
        return (double) computePruned(totalColumns, retainedColumns) / totalColumns;
    }

    /**
     * 记录一次投影下推。
     *
     * @param sourceType     数据源类型（null 表示未知数据源）
     * @param totalColumns   本次下推涉及的总列数
     * @param retainedColumns 本次下推后保留的列数
     */
    public void recordProjection(DataSourceConfig.Type sourceType,
                                 int totalColumns, int retainedColumns) {
        recordProjection(sourceType, totalColumns, retainedColumns, null);
    }

    /**
     * 记录一次投影下推（含描述）。
     *
     * @param sourceType     数据源类型（null 表示未知数据源）
     * @param totalColumns   本次下推涉及的总列数
     * @param retainedColumns 本次下推后保留的列数
     * @param description    下推描述（如 "users: [id,name,age] -> [name]"）
     */
    public void recordProjection(DataSourceConfig.Type sourceType,
                                 int totalColumns, int retainedColumns,
                                 String description) {
        if (totalColumns < 0 || retainedColumns < 0) {
            return;
        }
        int pruned = computePruned(totalColumns, retainedColumns);
        this.totalColumns.addAndGet(totalColumns);
        this.retainedColumns.addAndGet(retainedColumns);
        this.prunedColumns.addAndGet(pruned);
        this.pushDownCount.incrementAndGet();

        if (description != null) {
            pushedDescriptions.add(description);
        }

        // 按数据源类型统计
        if (sourceType != null) {
            int[] count = sourceStats.get(sourceType);
            if (count != null) {
                count[0] += totalColumns;
                count[1] += retainedColumns;
            }
        }
    }

    /**
     * 记录一次嵌套投影合并（Project→Project → 合并为单个 Project）。
     */
    public void recordMerge() {
        mergeCount.incrementAndGet();
    }

    /**
     * 记录一次跳过（无需下推，如 SELECT * 或全列引用）。
     *
     * @param reason 跳过原因
     */
    public void recordSkip(String reason) {
        skipCount.incrementAndGet();
        if (reason != null) {
            skipReasons.add(reason);
        }
    }

    // ===================== 总体统计指标 =====================

    /**
     * 获取总体列裁剪率（裁剪列数 / 总列数）。
     *
     * <p>列裁剪率 = 1 - 保留列数/总列数 = (总列数 - 保留列数) / 总列数</p>
     *
     * @return 列裁剪率，范围 [0.0, 1.0]，无记录时返回 0.0
     */
    public double getColumnReductionRate() {
        int total = totalColumns.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) prunedColumns.get() / total;
    }

    /**
     * 获取数据传输减少率。
     *
     * <p>按列等宽假设（每列传输量相同），数据传输减少率 ≈ 列裁剪率。
     * 实际场景中不同列类型宽度不同，此处采用等宽近似。</p>
     *
     * @return 数据传输减少率，范围 [0.0, 1.0]
     */
    public double getDataTransferReductionRate() {
        return getColumnReductionRate();
    }

    /**
     * 获取指定数据源的列裁剪率。
     *
     * @param sourceType 数据源类型
     * @return 该数据源的列裁剪率，无记录时返回 0.0
     */
    public double getColumnReductionRate(DataSourceConfig.Type sourceType) {
        int[] count = sourceStats.get(sourceType);
        if (count == null || count[0] == 0) {
            return 0.0;
        }
        return (double) (count[0] - count[1]) / count[0];
    }

    /**
     * 获取指定数据源的数据传输减少率。
     *
     * @param sourceType 数据源类型
     * @return 该数据源的数据传输减少率
     */
    public double getDataTransferReductionRate(DataSourceConfig.Type sourceType) {
        return getColumnReductionRate(sourceType);
    }

    /**
     * 获取下推率（下推次数 / (下推次数 + 跳过次数)）。
     *
     * <p>下推率衡量规则触发后实际执行下推的比例。SELECT * 等场景会跳过下推。</p>
     *
     * @return 下推率，范围 [0.0, 1.0]
     */
    public double getPushDownRate() {
        int total = pushDownCount.get() + skipCount.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) pushDownCount.get() / total;
    }

    /**
     * 获取指定数据源的下推率。
     *
     * @param sourceType 数据源类型
     * @return 该数据源的下推率
     */
    public double getPushDownRate(DataSourceConfig.Type sourceType) {
        int[] count = sourceStats.get(sourceType);
        if (count == null || count[0] == 0) {
            return 0.0;
        }
        // 下推率：有裁剪即视为下推成功（保留列数 < 总列数）
        if (count[1] < count[0]) {
            return 1.0;
        }
        return 0.0;
    }

    /**
     * 获取所有参与统计的数据源类型（即至少有一次投影记录的数据源）。
     *
     * @return 数据源类型集合
     */
    public Set<DataSourceConfig.Type> getActiveSourceTypes() {
        Set<DataSourceConfig.Type> active = new LinkedHashSet<>();
        for (Map.Entry<DataSourceConfig.Type, int[]> entry : sourceStats.entrySet()) {
            if (entry.getValue()[0] > 0) {
                active.add(entry.getKey());
            }
        }
        return active;
    }

    /**
     * 重置所有统计计数。
     */
    public void reset() {
        totalColumns.set(0);
        retainedColumns.set(0);
        prunedColumns.set(0);
        pushDownCount.set(0);
        mergeCount.set(0);
        skipCount.set(0);
        for (int[] count : sourceStats.values()) {
            count[0] = 0;
            count[1] = 0;
        }
        pushedDescriptions.clear();
        skipReasons.clear();
    }

    // ===================== Getter =====================

    public int getTotalColumns() {
        return totalColumns.get();
    }

    public int getRetainedColumns() {
        return retainedColumns.get();
    }

    public int getPrunedColumns() {
        return prunedColumns.get();
    }

    public int getPushDownCount() {
        return pushDownCount.get();
    }

    public int getMergeCount() {
        return mergeCount.get();
    }

    public int getSkipCount() {
        return skipCount.get();
    }

    /**
     * 获取按数据源类型分类的统计快照。
     *
     * @return 不可变映射：数据源类型 → [总列数, 保留列数]
     */
    public Map<DataSourceConfig.Type, int[]> getSourceStats() {
        Map<DataSourceConfig.Type, int[]> snapshot = new LinkedHashMap<>();
        for (Map.Entry<DataSourceConfig.Type, int[]> entry : sourceStats.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public List<String> getPushedDescriptions() {
        return Collections.unmodifiableList(pushedDescriptions);
    }

    public List<String> getSkipReasons() {
        return Collections.unmodifiableList(skipReasons);
    }

    /**
     * 生成统计摘要字符串（用于日志与调试）。
     *
     * @return 统计摘要
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("ProjectionStatistics{");
        sb.append("totalCols=").append(totalColumns.get());
        sb.append(", retainedCols=").append(retainedColumns.get());
        sb.append(", prunedCols=").append(prunedColumns.get());
        sb.append(", reductionRate=").append(String.format("%.2f%%", getColumnReductionRate() * 100));
        sb.append(", transferReductionRate=").append(
                String.format("%.2f%%", getDataTransferReductionRate() * 100));
        sb.append(", pushDownCount=").append(pushDownCount.get());
        sb.append(", mergeCount=").append(mergeCount.get());
        sb.append(", skipCount=").append(skipCount.get());
        sb.append(", pushDownRate=").append(String.format("%.2f%%", getPushDownRate() * 100));
        sb.append("\n  bySource:");
        for (Map.Entry<DataSourceConfig.Type, int[]> entry : sourceStats.entrySet()) {
            if (entry.getValue()[0] > 0) {
                int total = entry.getValue()[0];
                int retained = entry.getValue()[1];
                double rate = (double) (total - retained) / total;
                sb.append("\n    ").append(entry.getKey())
                        .append(": totalCols=").append(total)
                        .append(", retainedCols=").append(retained)
                        .append(", reductionRate=").append(String.format("%.2f%%", rate * 100));
            }
        }
        sb.append("\n}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ProjectionStatistics{totalCols=" + totalColumns.get()
                + ", retainedCols=" + retainedColumns.get()
                + ", prunedCols=" + prunedColumns.get()
                + ", reductionRate=" + String.format("%.4f", getColumnReductionRate())
                + ", pushDownCount=" + pushDownCount.get()
                + ", mergeCount=" + mergeCount.get()
                + ", skipCount=" + skipCount.get() + '}';
    }
}