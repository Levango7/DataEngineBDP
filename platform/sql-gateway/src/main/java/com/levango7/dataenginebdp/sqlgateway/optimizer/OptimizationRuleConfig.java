package com.levango7.dataenginebdp.sqlgateway.optimizer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 优化规则配置——对应 Calcite {@code org.apache.calcite.rel.rules} 包中的内置规则。
 *
 * <p>本类以可配置方式注册/启停优化规则，每条规则同时记录对应的 Calcite 规则类名，
 * 便于在执行计划中展示与 Calcite 的对应关系。{@link SqlOptimizerService} 根据本配置
 * 决定应用哪些启发式优化规则。</p>
 *
 * <p>默认启用的规则：
 * <ul>
 *   <li>{@link Rule#FILTER_MERGE}        → Calcite {@code FilterMergeRule}（谓词合并）</li>
 *   <li>{@link Rule#FILTER_PUSH_DOWN}    → Calcite {@code FilterPushDownPastProjectRule}（谓词下推）</li>
 *   <li>{@link Rule#PROJECT_MERGE}       → Calcite {@code ProjectMergeRule}（投影合并/列裁剪）</li>
 *   <li>{@link Rule#JOIN_EXPR_PUSH_DOWN} → Calcite {@code JoinPushExpressionsRule}（Join 表达式下推）</li>
 *   <li>{@link Rule#AGG_CONSTANT_PULL_UP}→ Calcite {@code AggregatePullUpConstantsRule}（常量上拉）</li>
 * </ul>
 * </p>
 *
 * @author shuqing-bigdata
 */
public class OptimizationRuleConfig {

    /**
     * 内置优化规则枚举。
     */
    public enum Rule {
        /** 谓词合并：合并相邻 Filter → Calcite FilterMergeRule */
        FILTER_MERGE("FilterMergeRule",
                "org.apache.calcite.rel.rules.FilterMergeRule",
                "合并相邻 Filter 谓词"),
        /** 谓词下推：将 Filter 推过 Project/Join → Calcite FilterPushDownPastProjectRule */
        FILTER_PUSH_DOWN("FilterPushDownPastProjectRule",
                "org.apache.calcite.rel.rules.FilterPushDownPastProjectRule",
                "谓词下推至数据源"),
        /** 投影合并/列裁剪：合并相邻 Project → Calcite ProjectMergeRule */
        PROJECT_MERGE("ProjectMergeRule",
                "org.apache.calcite.rel.rules.ProjectMergeRule",
                "投影合并与列裁剪"),
        /** Join 表达式下推 → Calcite JoinPushExpressionsRule */
        JOIN_EXPR_PUSH_DOWN("JoinPushExpressionsRule",
                "org.apache.calcite.rel.rules.JoinPushExpressionsRule",
                "Join 表达式下推"),
        /** 聚合常量上拉 → Calcite AggregatePullUpConstantsRule */
        AGG_CONSTANT_PULL_UP("AggregatePullUpConstantsRule",
                "org.apache.calcite.rel.rules.AggregatePullUpConstantsRule",
                "聚合常量上拉"),
        /** Join 重排序（启发式：小表驱动） */
        JOIN_REORDER("JoinReorderRule",
                "com.levango7.dataenginebdp.sqlgateway.optimizer.JoinReorderRule",
                "Join 表重排序（小表驱动）"),
        /** 谓词下推至 Join 的两侧输入 */
        FILTER_INTO_JOIN("FilterIntoJoinRule",
                "org.apache.calcite.rel.rules.FilterJoinRule",
                "谓词下推至 Join 输入");

        private final String shortName;
        private final String calciteClassName;
        private final String description;

        Rule(String shortName, String calciteClassName, String description) {
            this.shortName = shortName;
            this.calciteClassName = calciteClassName;
            this.description = description;
        }

        public String getShortName() {
            return shortName;
        }

        public String getCalciteClassName() {
            return calciteClassName;
        }

        public String getDescription() {
            return description;
        }
    }

    private final Map<Rule, Boolean> enabledRules;

    public OptimizationRuleConfig() {
        this.enabledRules = new LinkedHashMap<>();
        // 默认启用前 5 项核心规则
        enabledRules.put(Rule.FILTER_MERGE, true);
        enabledRules.put(Rule.FILTER_PUSH_DOWN, true);
        enabledRules.put(Rule.PROJECT_MERGE, true);
        enabledRules.put(Rule.JOIN_EXPR_PUSH_DOWN, true);
        enabledRules.put(Rule.AGG_CONSTANT_PULL_UP, true);
        enabledRules.put(Rule.JOIN_REORDER, false);
        enabledRules.put(Rule.FILTER_INTO_JOIN, true);
    }

    /**
     * 启用指定规则。
     *
     * @param rule 规则
     * @return 当前配置（链式）
     */
    public OptimizationRuleConfig enable(Rule rule) {
        enabledRules.put(Objects.requireNonNull(rule), true);
        return this;
    }

    /**
     * 禁用指定规则。
     *
     * @param rule 规则
     * @return 当前配置（链式）
     */
    public OptimizationRuleConfig disable(Rule rule) {
        enabledRules.put(Objects.requireNonNull(rule), false);
        return this;
    }

    /**
     * 启用所有内置规则。
     *
     * @return 当前配置（链式）
     */
    public OptimizationRuleConfig enableAll() {
        for (Rule r : Rule.values()) {
            enabledRules.put(r, true);
        }
        return this;
    }

    /**
     * 禁用所有规则（关闭优化）。
     *
     * @return 当前配置（链式）
     */
    public OptimizationRuleConfig disableAll() {
        for (Rule r : Rule.values()) {
            enabledRules.put(r, false);
        }
        return this;
    }

    /**
     * 查询规则是否启用。
     *
     * @param rule 规则
     * @return {@code true} 表示启用
     */
    public boolean isEnabled(Rule rule) {
        return enabledRules.getOrDefault(rule, false);
    }

    /**
     * 获取所有已启用的规则集合。
     *
     * @return 已启用规则集合
     */
    public Set<Rule> getEnabledRules() {
        java.util.Set<Rule> result = new java.util.LinkedHashSet<>();
        for (Map.Entry<Rule, Boolean> e : enabledRules.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    /**
     * 获取所有规则及其启用状态（不可变视图）。
     *
     * @return 规则→启用状态映射
     */
    public Map<Rule, Boolean> getAllRules() {
        return Collections.unmodifiableMap(enabledRules);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("OptimizationRuleConfig{");
        boolean first = true;
        for (Map.Entry<Rule, Boolean> e : enabledRules.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(e.getKey().shortName).append('=').append(e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }
}