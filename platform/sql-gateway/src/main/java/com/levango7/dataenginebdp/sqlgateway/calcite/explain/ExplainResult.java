package com.levango7.dataenginebdp.sqlgateway.calcite.explain;

import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EXPLAIN 执行计划结果数据模型——聚合优化后的 RelNode 树、下推统计、Cost 估算与调优建议。
 *
 * <p>本类是 {@link ExplainVisualizer} 的统一输出载体，封装一次 EXPLAIN 调用产生的
 * 全部信息，供 {@link ExplainFormatter} 按指定格式渲染输出。</p>
 *
 * <p>结构：</p>
 * <ul>
 *   <li>{@link #sql}：原始 SQL 文本</li>
 *   <li>{@link #relNode}：优化后的 CustomRelNode 树（含下推标注与跨源标记）</li>
 *   <li>{@link #rowCount}：估算结果行数</li>
 *   <li>{@link #depth}：RelNode 树深度</li>
 *   <li>{@link #rulesApplied}：已应用的优化规则名列表</li>
 *   <li>{@link #pushDownStats}：下推率统计指标（总下推率、分类下推率）</li>
 *   <li>{@link #costStats}：Cost 估算指标（CPU/IO/Network/总 Cost）</li>
 *   <li>{@link #tuningSuggestions}：性能调优建议列表</li>
 *   <li>{@link #success}：EXPLAIN 是否成功</li>
 *   <li>{@link #error}：失败原因（success=false 时）</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public class ExplainResult {

    /** 原始 SQL 文本 */
    private final String sql;
    /** 优化后的 CustomRelNode 树（可能为 null，表示解析失败） */
    private final CustomRelNode relNode;
    /** 估算结果行数 */
    private final double rowCount;
    /** RelNode 树深度 */
    private final int depth;
    /** 已应用的优化规则名列表 */
    private final List<String> rulesApplied;
    /** 下推率统计指标（键 → 值） */
    private final Map<String, Object> pushDownStats;
    /** Cost 估算指标（键 → 值） */
    private final Map<String, Object> costStats;
    /** 性能调优建议列表 */
    private final List<String> tuningSuggestions;
    /** EXPLAIN 是否成功 */
    private final boolean success;
    /** 失败原因 */
    private final String error;

    /**
     * 构造完整的 EXPLAIN 结果。
     *
     * @param sql              原始 SQL
     * @param relNode          优化后的 RelNode 树
     * @param rowCount         估算行数
     * @param depth            树深度
     * @param rulesApplied     已应用规则列表
     * @param pushDownStats    下推统计指标
     * @param costStats        Cost 估算指标
     * @param tuningSuggestions 调优建议
     * @param success          是否成功
     * @param error            失败原因
     */
    public ExplainResult(String sql, CustomRelNode relNode, double rowCount, int depth,
                         List<String> rulesApplied, Map<String, Object> pushDownStats,
                         Map<String, Object> costStats, List<String> tuningSuggestions,
                         boolean success, String error) {
        this.sql = sql;
        this.relNode = relNode;
        this.rowCount = rowCount;
        this.depth = depth;
        this.rulesApplied = rulesApplied == null
                ? Collections.emptyList() : Collections.unmodifiableList(rulesApplied);
        this.pushDownStats = pushDownStats == null
                ? Collections.emptyMap() : Collections.unmodifiableMap(pushDownStats);
        this.costStats = costStats == null
                ? Collections.emptyMap() : Collections.unmodifiableMap(costStats);
        this.tuningSuggestions = tuningSuggestions == null
                ? Collections.emptyList() : Collections.unmodifiableList(tuningSuggestions);
        this.success = success;
        this.error = error;
    }

    /**
     * 创建成功的结果。
     *
     * @param sql               SQL
     * @param relNode           RelNode 树
     * @param rowCount          行数
     * @param depth             深度
     * @param rulesApplied      已应用规则
     * @param pushDownStats     下推统计
     * @param costStats         Cost 统计
     * @param tuningSuggestions 调优建议
     * @return 成功结果
     */
    public static ExplainResult success(String sql, CustomRelNode relNode, double rowCount,
                                        int depth, List<String> rulesApplied,
                                        Map<String, Object> pushDownStats,
                                        Map<String, Object> costStats,
                                        List<String> tuningSuggestions) {
        return new ExplainResult(sql, relNode, rowCount, depth, rulesApplied,
                pushDownStats, costStats, tuningSuggestions, true, null);
    }

    /**
     * 创建失败的结果。
     *
     * @param sql   SQL
     * @param error 失败原因
     * @return 失败结果
     */
    public static ExplainResult failure(String sql, String error) {
        return new ExplainResult(sql, null, 0, 0, Collections.emptyList(),
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyList(), false, Objects.requireNonNull(error));
    }

    public String getSql() {
        return sql;
    }

    public CustomRelNode getRelNode() {
        return relNode;
    }

    public double getRowCount() {
        return rowCount;
    }

    public int getDepth() {
        return depth;
    }

    public List<String> getRulesApplied() {
        return rulesApplied;
    }

    public Map<String, Object> getPushDownStats() {
        return pushDownStats;
    }

    public Map<String, Object> getCostStats() {
        return costStats;
    }

    public List<String> getTuningSuggestions() {
        return tuningSuggestions;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getError() {
        return error;
    }

    /**
     * 生成简要摘要（用于日志）。
     *
     * @return 摘要字符串
     */
    public String summary() {
        StringBuilder sb = new StringBuilder("ExplainResult{");
        sb.append("success=").append(success);
        sb.append(", depth=").append(depth);
        sb.append(", rowCount=").append(rowCount);
        sb.append(", rules=").append(rulesApplied);
        sb.append(", pushDownKeys=").append(pushDownStats.keySet());
        sb.append(", costKeys=").append(costStats.keySet());
        sb.append(", suggestions=").append(tuningSuggestions.size());
        if (error != null) {
            sb.append(", error=").append(error);
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public String toString() {
        return summary();
    }
}