package com.levango7.dataenginebdp.sqlgateway.calcite.rule;

import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 下推率统计器——记录谓词下推规则的执行统计信息。
 *
 * <p>本类在 {@link PredicatePushDownRule} 执行过程中累积统计，提供以下维度的指标：</p>
 * <ul>
 *   <li><b>总体下推率</b>：已下推谓词数 / 总谓词数</li>
 *   <li><b>按谓词类型分类</b>：等值/范围/IN/LIKE/IS_NULL/UNSUPPORTED 各类的下推与保留数</li>
 *   <li><b>按数据源分类</b>：Iceberg/Doris/Trino/IoTDB/ES 各数据源的下推率</li>
 *   <li><b>保留原因</b>：未下推谓词的原因列表（如"UDF 不支持"、"跨源谓词"）</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>
 *   PushDownStatistics stats = new PushDownStatistics();
 *   stats.recordPredicate(sourceType, PredicateType.EQUALITY, true);
 *   stats.recordPredicate(sourceType, PredicateType.UNSUPPORTED, false, "UDF 不支持");
 *   double rate = stats.getPushDownRate();  // 0.5
 * </pre>
 *
 * <p>本类线程安全（使用 {@link AtomicInteger} 计数），可在并发优化场景中使用。</p>
 *
 * @author shuqing-bigdata
 */
public class PushDownStatistics {

    /** 总谓词数 */
    private final AtomicInteger totalPredicates = new AtomicInteger(0);
    /** 已下推谓词数 */
    private final AtomicInteger pushedPredicates = new AtomicInteger(0);
    /** 保留谓词数 */
    private final AtomicInteger remainingPredicates = new AtomicInteger(0);

    /** 按谓词类型统计：类型 → [总数, 下推数] */
    private final Map<PredicateType, int[]> typeStats = new LinkedHashMap<>();
    /** 按数据源类型统计：数据源类型 → [总数, 下推数] */
    private final Map<DataSourceConfig.Type, int[]> sourceStats = new LinkedHashMap<>();
    /** 保留原因列表 */
    private final List<String> remainingReasons = Collections.synchronizedList(new java.util.ArrayList<>());
    /** 已下推谓词描述列表 */
    private final List<String> pushedDescriptions = Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * 构造空的统计器，初始化所有谓词类型与数据源类型的计数桶。
     */
    public PushDownStatistics() {
        for (PredicateType type : PredicateType.values()) {
            typeStats.put(type, new int[]{0, 0});
        }
        for (DataSourceConfig.Type type : DataSourceConfig.Type.values()) {
            sourceStats.put(type, new int[]{0, 0});
        }
    }

    /**
     * 记录一个谓词的下推结果。
     *
     * @param sourceType 数据源类型（null 表示未知数据源）
     * @param predicateType 谓词类型
     * @param pushed 是否成功下推
     */
    public void recordPredicate(DataSourceConfig.Type sourceType,
                                PredicateType predicateType, boolean pushed) {
        recordPredicate(sourceType, predicateType, pushed, null, null);
    }

    /**
     * 记录一个谓词的下推结果（含原因与描述）。
     *
     * @param sourceType    数据源类型（null 表示未知数据源）
     * @param predicateType 谓词类型
     * @param pushed        是否成功下推
     * @param reason        保留原因（pushed=false 时记录）
     * @param description   谓词描述（如 "id = 100"）
     */
    public void recordPredicate(DataSourceConfig.Type sourceType,
                                PredicateType predicateType, boolean pushed,
                                String reason, String description) {
        totalPredicates.incrementAndGet();
        if (pushed) {
            pushedPredicates.incrementAndGet();
            if (description != null) {
                pushedDescriptions.add(description);
            }
        } else {
            remainingPredicates.incrementAndGet();
            if (reason != null) {
                remainingReasons.add(reason);
            }
        }

        // 按谓词类型统计
        int[] typeCount = typeStats.get(predicateType);
        if (typeCount != null) {
            typeCount[0]++;
            if (pushed) {
                typeCount[1]++;
            }
        }

        // 按数据源类型统计
        if (sourceType != null) {
            int[] sourceCount = sourceStats.get(sourceType);
            if (sourceCount != null) {
                sourceCount[0]++;
                if (pushed) {
                    sourceCount[1]++;
                }
            }
        }
    }

    /**
     * 获取总体下推率（已下推谓词数 / 总谓词数）。
     *
     * @return 下推率，范围 [0.0, 1.0]，无谓词时返回 0.0
     */
    public double getPushDownRate() {
        int total = totalPredicates.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) pushedPredicates.get() / total;
    }

    /**
     * 获取指定数据源的下推率。
     *
     * @param sourceType 数据源类型
     * @return 该数据源的下推率，无谓词时返回 0.0
     */
    public double getPushDownRate(DataSourceConfig.Type sourceType) {
        int[] count = sourceStats.get(sourceType);
        if (count == null || count[0] == 0) {
            return 0.0;
        }
        return (double) count[1] / count[0];
    }

    /**
     * 获取指定谓词类型的下推率。
     *
     * @param predicateType 谓词类型
     * @return 该类型的下推率，无谓词时返回 0.0
     */
    public double getPushDownRate(PredicateType predicateType) {
        int[] count = typeStats.get(predicateType);
        if (count == null || count[0] == 0) {
            return 0.0;
        }
        return (double) count[1] / count[0];
    }

    /**
     * 获取所有参与统计的数据源类型（即至少有一个谓词的数据源）。
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
        totalPredicates.set(0);
        pushedPredicates.set(0);
        remainingPredicates.set(0);
        for (int[] count : typeStats.values()) {
            count[0] = 0;
            count[1] = 0;
        }
        for (int[] count : sourceStats.values()) {
            count[0] = 0;
            count[1] = 0;
        }
        remainingReasons.clear();
        pushedDescriptions.clear();
    }

    // ===================== Getter =====================

    public int getTotalPredicates() {
        return totalPredicates.get();
    }

    public int getPushedPredicates() {
        return pushedPredicates.get();
    }

    public int getRemainingPredicates() {
        return remainingPredicates.get();
    }

    /**
     * 获取按谓词类型分类的统计快照。
     *
     * @return 不可变映射：谓词类型 → [总数, 下推数]
     */
    public Map<PredicateType, int[]> getTypeStats() {
        Map<PredicateType, int[]> snapshot = new LinkedHashMap<>();
        for (Map.Entry<PredicateType, int[]> entry : typeStats.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * 获取按数据源类型分类的统计快照。
     *
     * @return 不可变映射：数据源类型 → [总数, 下推数]
     */
    public Map<DataSourceConfig.Type, int[]> getSourceStats() {
        Map<DataSourceConfig.Type, int[]> snapshot = new LinkedHashMap<>();
        for (Map.Entry<DataSourceConfig.Type, int[]> entry : sourceStats.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public List<String> getRemainingReasons() {
        return Collections.unmodifiableList(remainingReasons);
    }

    public List<String> getPushedDescriptions() {
        return Collections.unmodifiableList(pushedDescriptions);
    }

    /**
     * 生成统计摘要字符串（用于日志与调试）。
     *
     * @return 统计摘要
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("PushDownStatistics{");
        sb.append("total=").append(totalPredicates.get());
        sb.append(", pushed=").append(pushedPredicates.get());
        sb.append(", remaining=").append(remainingPredicates.get());
        sb.append(", rate=").append(String.format("%.2f%%", getPushDownRate() * 100));
        sb.append("\n  byType:");
        for (Map.Entry<PredicateType, int[]> entry : typeStats.entrySet()) {
            if (entry.getValue()[0] > 0) {
                double rate = entry.getValue()[0] == 0 ? 0
                        : (double) entry.getValue()[1] / entry.getValue()[0];
                sb.append("\n    ").append(entry.getKey().description())
                        .append(": total=").append(entry.getValue()[0])
                        .append(", pushed=").append(entry.getValue()[1])
                        .append(", rate=").append(String.format("%.2f%%", rate * 100));
            }
        }
        sb.append("\n  bySource:");
        for (Map.Entry<DataSourceConfig.Type, int[]> entry : sourceStats.entrySet()) {
            if (entry.getValue()[0] > 0) {
                double rate = entry.getValue()[0] == 0 ? 0
                        : (double) entry.getValue()[1] / entry.getValue()[0];
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
        return "PushDownStatistics{total=" + totalPredicates.get()
                + ", pushed=" + pushedPredicates.get()
                + ", remaining=" + remainingPredicates.get()
                + ", rate=" + String.format("%.4f", getPushDownRate()) + '}';
    }
}