package com.levango7.dataenginebdp.sqlgateway.calcite.rule;

import com.levango7.dataenginebdp.sqlgateway.calcite.adapter.BaseAdapter;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 下推规则基类——对应 Calcite {@code org.apache.calcite.plan.RelOptRule}。
 *
 * <p>每条下推规则负责将特定模式的 RelNode 子树下推到数据源执行。规则由
 * {@code CalciteOptimizer} 注册到 HepPlanner（启发式）或 VolcanoPlanner（基于 Cost），
 * 在优化遍历中按 {@link #matches} 判定是否触发，触发后调用 {@link #onMatch} 执行改写。</p>
 *
 * <p>本类是抽象基类，子类需实现 {@link #matches} 与 {@link #onMatch}。
 * 典型子类：</p>
 * <ul>
 *   <li>{@code FilterPushDownRule}：将 Filter 下推到 TableScan 之上</li>
 *   <li>{@code ProjectPushDownRule}：将 Project 列裁剪下推到 TableScan</li>
 *   <li>{@code AggregatePushDownRule}：将 Aggregate 下推到支持聚合的数据源</li>
 *   <li>{@code LimitPushDownRule}：将 Limit 下推到数据源</li>
 * </ul>
 *
 * <p>对应 Calcite 下推规则通过 {@code RelOptRuleOperand} 声明匹配模式，
 * 本简化版以 {@link #matches} 方法手动判定。</p>
 *
 * @author shuqing-bigdata
 */
public abstract class PushDownRule {

    /** 规则短名（如 "FilterPushDown"） */
    private final String ruleName;
    /** 规则描述 */
    private final String description;
    /** 该规则关联的数据源适配器（决定下推目标） */
    private final BaseAdapter adapter;
    /** 该规则匹配的 RelNode 操作类型 */
    private final CustomRelNode.Op matchOp;
    /** 是否启用 */
    private boolean enabled = true;

    /**
     * 构造下推规则。
     *
     * @param ruleName    规则短名
     * @param description 规则描述
     * @param adapter     关联的数据源适配器
     * @param matchOp     匹配的 RelNode 操作类型
     */
    protected PushDownRule(String ruleName, String description,
                           BaseAdapter adapter, CustomRelNode.Op matchOp) {
        this.ruleName = Objects.requireNonNull(ruleName, "ruleName");
        this.description = description;
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.matchOp = Objects.requireNonNull(matchOp, "matchOp");
    }

    /**
     * 判断指定 RelNode 是否匹配本规则。
     *
     * <p>对应 Calcite {@code RelOptRule.matches(RelOptRuleCall)}。
     * 默认实现检查：节点操作类型一致 + 适配器可下推 + 规则已启用。
     * 子类可覆写以增加更精细的匹配条件（如检查谓词是否引用分区列）。</p>
     *
     * @param relNode 待检查的 RelNode
     * @return {@code true} 表示匹配
     */
    public boolean matches(CustomRelNode relNode) {
        if (!enabled || relNode == null) {
            return false;
        }
        if (relNode.getOp() != matchOp) {
            return false;
        }
        return adapter.canPushDown(relNode);
    }

    /**
     * 当规则匹配成功时执行下推改写。
     *
     * <p>对应 Calcite {@code RelOptRule.onMatch(RelOptRuleCall)}。
     * 子类应在此方法中调用 {@link BaseAdapter#pushDown} 生成下推结果，
     * 并通过 {@link RuleCall#transformTo} 将原 RelNode 替换为改写后的 RelNode。</p>
     *
     * @param call 规则调用上下文
     */
    public abstract void onMatch(RuleCall call);

    /**
     * 对指定 RelNode 应用本规则（便捷方法）。
     *
     * <p>若 {@link #matches} 返回 true，则构造 {@link RuleCall} 并调用
     * {@link #onMatch}；否则返回原 RelNode。</p>
     *
     * @param relNode 待优化的 RelNode
     * @return 改写后的 RelNode（未匹配时为原 RelNode）
     */
    public CustomRelNode apply(CustomRelNode relNode) {
        if (!matches(relNode)) {
            return relNode;
        }
        RuleCall call = new RuleCall(this, relNode);
        onMatch(call);
        return call.getResult();
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getDescription() {
        return description;
    }

    public BaseAdapter getAdapter() {
        return adapter;
    }

    public CustomRelNode.Op getMatchOp() {
        return matchOp;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public PushDownRule setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    @Override
    public String toString() {
        return "PushDownRule{" + ruleName
                + ", adapter=" + (adapter.getDataSourceConfig() == null
                        ? "?" : adapter.getDataSourceConfig().getName())
                + ", matchOp=" + matchOp
                + ", enabled=" + enabled + '}';
    }

    /**
     * 规则调用上下文——对应 Calcite {@code RelOptRuleCall}。
     *
     * <p>携带触发规则的根 RelNode（{@code root}），并提供 {@link #transformTo}
     * 方法用于声明改写结果。{@link PushDownRule#apply} 最终通过 {@link #getResult}
     * 获取改写后的 RelNode。</p>
     *
     * @author shuqing-bigdata
     */
    public static class RuleCall {
        private final PushDownRule rule;
        private final CustomRelNode root;
        private CustomRelNode result;
        private final List<String> appliedRules = new ArrayList<>();

        public RuleCall(PushDownRule rule, CustomRelNode root) {
            this.rule = rule;
            this.root = root;
            this.result = root;
        }

        public PushDownRule getRule() {
            return rule;
        }

        public CustomRelNode getRoot() {
            return root;
        }

        public CustomRelNode getResult() {
            return result;
        }

        public List<String> getAppliedRules() {
            return appliedRules;
        }

        /**
         * 声明改写结果——将原 RelNode 替换为新的 RelNode。
         *
         * @param newRel 改写后的 RelNode
         */
        public void transformTo(CustomRelNode newRel) {
            this.result = newRel;
            this.appliedRules.add(rule.getRuleName());
        }
    }
}