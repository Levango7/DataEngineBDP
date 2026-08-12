package com.levango7.dataenginebdp.flinkcdc.materializedview.refresh;

import com.levango7.dataenginebdp.flinkcdc.materializedview.model.AggregationType;
import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 聚合预计算器：对 CDC 变更记录流执行增量聚合计算，维护各聚合维度的中间状态。
 *
 * <p>支持五种聚合类型（COUNT/SUM/AVG/MIN/MAX），对每个 (维度组合, 指标列) 维护
 * 增量可合并的状态量。INSERT 时累加，DELETE 时撤回，UPDATE 拆分为 DELETE+INSERT。</p>
 *
 * <p>状态量说明：</p>
 * <ul>
 *   <li>COUNT — long 计数值</li>
 *   <li>SUM — double 累加和</li>
 *   <li>AVG — (sum, count) 二元组</li>
 *   <li>MIN — double 当前最小值</li>
 *   <li>MAX — double 当前最大值</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * AggregateCalculator calc = new AggregateCalculator(
 *     List.of("region"),                       // 维度列
 *     Map.of("total", AggregationType.SUM,     // 指标列
 *            "cnt", AggregationType.COUNT));
 * calc.apply(record1);  // 累积变更
 * calc.apply(record2);
 * Map<String, Map<String, Object>> result = calc.snapshot();  // 获取当前聚合结果
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public class AggregateCalculator {

    private static final Logger log = LoggerFactory.getLogger(AggregateCalculator.class);

    /** 维度列名列表。 */
    private final List<String> dimensions;

    /** 指标列：列名 → 聚合类型。 */
    private final Map<String, AggregationType> metrics;

    /**
     * 聚合状态：维度组合 key → 指标列 → 状态值。
     * <p>状态值含义因聚合类型而异（见类注释）。</p>
     */
    private final Map<String, Map<String, double[]>> state = new HashMap<>();

    /**
     * 构造器。
     *
     * @param dimensions 维度列名列表（GROUP BY 列）
     * @param metrics    指标列映射（列名 → 聚合类型）
     */
    public AggregateCalculator(List<String> dimensions, Map<String, AggregationType> metrics) {
        this.dimensions = new ArrayList<>(Objects.requireNonNull(dimensions, "维度列列表不能为 null"));
        this.metrics = new LinkedHashMap<>(Objects.requireNonNull(metrics, "指标列映射不能为 null"));
    }

    /**
     * 应用一条 CDC 变更记录，更新聚合状态。
     *
     * <p>根据操作类型分别处理：</p>
     * <ul>
     *   <li>INSERT (c) — 累加新行</li>
     *   <li>UPDATE (u) — 先撤回 before 再累加 after</li>
     *   <li>DELETE (d) — 撤回 before</li>
     *   <li>SNAPSHOT (r) — 视为 INSERT 累加</li>
     * </ul>
     *
     * @param record CDC 变更记录
     */
    public void apply(ChangeRecord record) {
        Objects.requireNonNull(record, "ChangeRecord 不能为 null");
        String op = record.getOp();
        if (op == null) {
            return;
        }
        switch (op) {
            case "c", "r" -> addToState(record.getAfter());
            case "u" -> {
                removeFromState(record.getBefore());
                addToState(record.getAfter());
            }
            case "d" -> removeFromState(record.getBefore());
            default -> log.warn("未知操作类型: {}，跳过", op);
        }
    }

    /**
     * 将一行数据累加到聚合状态。
     *
     * @param row 行数据（列名 → 列值）
     */
    private void addToState(Map<String, Object> row) {
        if (row == null) {
            return;
        }
        String dimKey = dimensionKey(row);
        Map<String, double[]> metricState = state.computeIfAbsent(dimKey, k -> new HashMap<>());
        for (Map.Entry<String, AggregationType> entry : metrics.entrySet()) {
            String col = entry.getKey();
            AggregationType type = entry.getValue();
            double value = numericValue(row, col);
            double[] st = metricState.computeIfAbsent(col, c -> initialState(type));
            addToAggregate(st, type, value);
        }
    }

    /**
     * 从聚合状态撤回一行数据。
     *
     * @param row 行数据（列名 → 列值）
     */
    private void removeFromState(Map<String, Object> row) {
        if (row == null) {
            return;
        }
        String dimKey = dimensionKey(row);
        Map<String, double[]> metricState = state.get(dimKey);
        if (metricState == null) {
            return;
        }
        for (Map.Entry<String, AggregationType> entry : metrics.entrySet()) {
            String col = entry.getKey();
            AggregationType type = entry.getValue();
            double value = numericValue(row, col);
            double[] st = metricState.get(col);
            if (st == null) {
                continue;
            }
            removeFromAggregate(st, type, value);
        }
    }

    /**
     * 初始化聚合状态数组。
     *
     * <p>状态数组约定：</p>
     * <ul>
     *   <li>COUNT/SUM/MIN/MAX — 长度 1，存当前值</li>
     *   <li>AVG — 长度 2，[sum, count]</li>
     * </ul>
     *
     * @param type 聚合类型
     * @return 初始状态数组
     */
    static double[] initialState(AggregationType type) {
        return switch (type) {
            case COUNT -> new double[]{0};
            case SUM -> new double[]{0};
            case AVG -> new double[]{0, 0};
            case MIN -> new double[]{Double.POSITIVE_INFINITY};
            case MAX -> new double[]{Double.NEGATIVE_INFINITY};
        };
    }

    /**
     * 累加一个值到聚合状态。
     *
     * @param st    状态数组
     * @param type  聚合类型
     * @param value 新值
     */
    static void addToAggregate(double[] st, AggregationType type, double value) {
        switch (type) {
            case COUNT -> st[0] += 1;
            case SUM -> st[0] += value;
            case AVG -> {
                st[0] += value;
                st[1] += 1;
            }
            case MIN -> st[0] = Math.min(st[0], value);
            case MAX -> st[0] = Math.max(st[0], value);
        }
    }

    /**
     * 从聚合状态撤回一个值。
     *
     * <p>COUNT/SUM/AVG 支持精确撤回；MIN/MAX 不支持撤回（标记为 NaN 表示需全量重算）。</p>
     *
     * @param st    状态数组
     * @param type  聚合类型
     * @param value 被撤回的值
     */
    static void removeFromAggregate(double[] st, AggregationType type, double value) {
        switch (type) {
            case COUNT -> st[0] -= 1;
            case SUM -> st[0] -= value;
            case AVG -> {
                st[0] -= value;
                st[1] -= 1;
            }
            case MIN, MAX -> st[0] = Double.NaN;  // 标记需全量重算
        }
    }

    /**
     * 计算最终聚合结果值。
     *
     * @param st    状态数组
     * @param type  聚合类型
     * @return 最终值；MIN/MAX 若被标记为 NaN 返回 null（需全量重算）
     */
    static Number finalValue(double[] st, AggregationType type) {
        return switch (type) {
            case COUNT -> (long) st[0];
            case SUM -> st[0];
            case AVG -> st[1] == 0 ? null : st[0] / st[1];
            case MIN, MAX -> Double.isNaN(st[0]) ? null : st[0];
        };
    }

    /**
     * 构造维度组合 key（将各维度列值拼接为字符串）。
     *
     * @param row 行数据
     * @return 维度 key，如 "east|2024-01-01"
     */
    String dimensionKey(Map<String, Object> row) {
        if (dimensions.isEmpty()) {
            return "__ALL__";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dimensions.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            Object val = row.get(dimensions.get(i));
            sb.append(val == null ? "" : val);
        }
        return sb.toString();
    }

    /**
     * 从行数据中提取数值列值。
     *
     * @param row   行数据
     * @param column 列名
     * @return 数值；若为 null 或非数值返回 0
     */
    static double numericValue(Map<String, Object> row, String column) {
        Object val = row.get(column);
        if (val == null) {
            return 0;
        }
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 获取当前聚合状态快照（维度 key → 指标列 → 最终值）。
     *
     * @return 不可修改的快照
     */
    public Map<String, Map<String, Number>> snapshot() {
        Map<String, Map<String, Number>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, double[]>> dimEntry : state.entrySet()) {
            Map<String, Number> metricsResult = new LinkedHashMap<>();
            for (Map.Entry<String, double[]> metricEntry : dimEntry.getValue().entrySet()) {
                AggregationType type = metrics.get(metricEntry.getKey());
                Number value = finalValue(metricEntry.getValue(), type);
                metricsResult.put(metricEntry.getKey(), value);
            }
            result.put(dimEntry.getKey(), metricsResult);
        }
        return result;
    }

    /**
     * 获取当前聚合状态快照（带维度列名解析）。
     *
     * <p>返回结果中每个维度组合的 key 被解析为维度列 → 值的映射，
     * 便于序列化为 JSON。</p>
     *
     * @return 维度值映射 → 指标列 → 最终值
     */
    public List<Map<String, Object>> snapshotWithDimensions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, double[]>> dimEntry : state.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            // 解析维度 key
            String[] dimValues = dimEntry.getKey().split("\\|", -1);
            for (int i = 0; i < dimensions.size(); i++) {
                row.put(dimensions.get(i), i < dimValues.length ? dimValues[i] : null);
            }
            // 添加指标值
            for (Map.Entry<String, double[]> metricEntry : dimEntry.getValue().entrySet()) {
                AggregationType type = metrics.get(metricEntry.getKey());
                row.put(metricEntry.getKey(), finalValue(metricEntry.getValue(), type));
            }
            result.add(row);
        }
        return result;
    }

    /**
     * 重置所有聚合状态。
     */
    public void reset() {
        state.clear();
    }

    /**
     * 获取当前状态中的维度组合数量。
     *
     * @return 维度组合数量
     */
    public int dimensionCount() {
        return state.size();
    }
}