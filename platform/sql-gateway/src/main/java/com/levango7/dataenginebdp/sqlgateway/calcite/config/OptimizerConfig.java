package com.levango7.dataenginebdp.sqlgateway.calcite.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Calcite 优化器配置 Schema——集中管理数据源注册、下推规则开关与 Cost 权重。
 *
 * <p>本类是 {@code CalciteOptimizer} 的运行时配置载体，对应 Calcite
 * {@code FrameworkConfig} 中可调参数的可读封装。配置项分三组：</p>
 * <ul>
 *   <li><b>数据源注册</b>：{@link #dataSources}，联邦查询可访问的物理数据源列表</li>
 *   <li><b>下推规则开关</b>：{@link #pushDownRules}，按规则短名启用/禁用下推</li>
 *   <li><b>Cost 权重</b>：{@link #costWeights}，影响 {@code VolcanoPlanner} 代价模型</li>
 * </ul>
 *
 * <p>典型 YAML 配置：</p>
 * <pre>
 * sql-gateway:
 *   optimizer:
 *     enabled: true
 *     data-sources:
 *       - name: doris_olap
 *         type: DORIS
 *         jdbc-url: "jdbc:mysql://doris-fe:9030"
 *     push-down-rules:
 *       FilterPushDown: true
 *       ProjectPushDown: true
 *       AggregatePushDown: false
 *     cost-weights:
 *       cpu: 1.0
 *       io: 10.0
 *       network: 100.0
 * </pre>
 *
 * @author shuqing-bigdata
 */
public class OptimizerConfig {

    /** 默认 CPU Cost 权重 */
    public static final double DEFAULT_CPU_WEIGHT = 1.0;
    /** 默认 IO Cost 权重 */
    public static final double DEFAULT_IO_WEIGHT = 10.0;
    /** 默认网络 Cost 权重 */
    public static final double DEFAULT_NETWORK_WEIGHT = 100.0;

    /** 是否启用 Calcite 优化器（关闭时走原始 SQL 透传） */
    private boolean enabled = true;

    /** 联邦数据源配置列表 */
    private List<DataSourceConfig> dataSources = Collections.emptyList();

    /** 下推规则开关：规则短名 → 是否启用 */
    private Map<String, Boolean> pushDownRules = new LinkedHashMap<>();

    /** Cost 权重：cpu/io/network → 权重值 */
    private Map<String, Double> costWeights = new LinkedHashMap<>();

    /** 是否启用 VolcanoPlanner（基于 Cost 的穷举优化），false 则使用 HepPlanner 启发式 */
    private boolean volcanoPlannerEnabled = false;

    /** 优化器最大迭代次数（HepPlanner 用） */
    private int maxIterations = 100;

    public OptimizerConfig() {
        // 默认下推规则开关
        pushDownRules.put("FilterPushDown", true);
        pushDownRules.put("ProjectPushDown", true);
        pushDownRules.put("AggregatePushDown", false);
        pushDownRules.put("LimitPushDown", true);
        pushDownRules.put("JoinPushDown", false);
        // 默认 Cost 权重
        costWeights.put("cpu", DEFAULT_CPU_WEIGHT);
        costWeights.put("io", DEFAULT_IO_WEIGHT);
        costWeights.put("network", DEFAULT_NETWORK_WEIGHT);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public OptimizerConfig setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public List<DataSourceConfig> getDataSources() {
        return dataSources == null ? Collections.emptyList() : dataSources;
    }

    public OptimizerConfig setDataSources(List<DataSourceConfig> dataSources) {
        this.dataSources = dataSources == null ? Collections.emptyList() : dataSources;
        return this;
    }

    public Map<String, Boolean> getPushDownRules() {
        return pushDownRules == null ? Collections.emptyMap() : pushDownRules;
    }

    public OptimizerConfig setPushDownRules(Map<String, Boolean> pushDownRules) {
        this.pushDownRules = pushDownRules == null ? new LinkedHashMap<>() : pushDownRules;
        return this;
    }

    public Map<String, Double> getCostWeights() {
        return costWeights == null ? Collections.emptyMap() : costWeights;
    }

    public OptimizerConfig setCostWeights(Map<String, Double> costWeights) {
        this.costWeights = costWeights == null ? new LinkedHashMap<>() : costWeights;
        return this;
    }

    public boolean isVolcanoPlannerEnabled() {
        return volcanoPlannerEnabled;
    }

    public OptimizerConfig setVolcanoPlannerEnabled(boolean volcanoPlannerEnabled) {
        this.volcanoPlannerEnabled = volcanoPlannerEnabled;
        return this;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public OptimizerConfig setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
        return this;
    }

    /**
     * 查询指定下推规则是否启用。
     *
     * @param ruleName 规则短名
     * @return {@code true} 表示启用
     */
    public boolean isPushDownRuleEnabled(String ruleName) {
        return pushDownRules.getOrDefault(Objects.requireNonNull(ruleName), false);
    }

    /**
     * 启用/禁用指定下推规则（链式）。
     *
     * @param ruleName 规则短名
     * @param enabled  是否启用
     * @return 当前配置
     */
    public OptimizerConfig setPushDownRule(String ruleName, boolean enabled) {
        this.pushDownRules.put(ruleName, enabled);
        return this;
    }

    /**
     * 获取指定维度的 Cost 权重，未配置时返回默认值。
     *
     * @param dimension 维度名（cpu/io/network）
     * @return 权重值
     */
    public double getCostWeight(String dimension) {
        return costWeights.getOrDefault(dimension, DEFAULT_CPU_WEIGHT);
    }

    /**
     * 设置 Cost 权重（链式）。
     *
     * @param dimension 维度名
     * @param weight    权重值
     * @return 当前配置
     */
    public OptimizerConfig setCostWeight(String dimension, double weight) {
        this.costWeights.put(dimension, weight);
        return this;
    }

    /**
     * 按 name 查找数据源配置。
     *
     * @param name 数据源名称
     * @return 数据源配置，未找到返回 {@code null}
     */
    public DataSourceConfig findDataSource(String name) {
        if (name == null || dataSources == null) {
            return null;
        }
        for (DataSourceConfig ds : dataSources) {
            if (ds != null && name.equals(ds.getName())) {
                return ds;
            }
        }
        return null;
    }

    /**
     * 获取所有合法（{@link DataSourceConfig#isValid()} 为 true）的数据源。
     *
     * @return 合法数据源集合
     */
    public Set<DataSourceConfig> getValidDataSources() {
        Set<DataSourceConfig> valid = new LinkedHashSet<>();
        if (dataSources != null) {
            for (DataSourceConfig ds : dataSources) {
                if (ds != null && ds.isValid()) {
                    valid.add(ds);
                }
            }
        }
        return valid;
    }

    @Override
    public String toString() {
        return "OptimizerConfig{enabled=" + enabled
                + ", dataSources=" + (dataSources == null ? 0 : dataSources.size())
                + ", pushDownRules=" + pushDownRules
                + ", costWeights=" + costWeights
                + ", volcano=" + volcanoPlannerEnabled
                + ", maxIterations=" + maxIterations + '}';
    }
}