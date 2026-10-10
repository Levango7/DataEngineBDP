package com.levango7.dataenginebdp.governance.realtime.quality;

import java.util.List;

/**
 * 质量规则的**来源端口**（台账 #6 阶段 3，Q1 裁决：rule-engine 为权威）。
 *
 * <p>背景（草案断点 C）：pipeline 的质量规则此前只经 {@code GovernanceController} 的
 * 进程内注册表写入（重启即空、多实例各一套），与 rule-engine 里持久化的规则是两份数据，
 * 导致"在规则引擎里配了质量规则"不作用于任何自动评估。
 *
 * <p>本端口把"规则从哪来"从实现中抽离，使引擎可以：
 * <ol>
 *   <li>从 rule-engine 拉取（{@link RuleEngineRuleSource}，生产路径）；</li>
 *   <li>在测试中注入假实现，验证刷新与降级语义。</li>
 * </ol>
 */
public interface QualityRuleSource {

    /** 该来源是否启用；未启用时引擎保持现有注册表（默认关，保持 additivity）。 */
    boolean isEnabled();

    /**
     * 拉取全部可用规则。
     *
     * <p>失败应抛出 {@link RuntimeException}（而非返回空），以便调用方区分
     * "拉取失败"与"确实没有规则"，并对前者执行降级（沿用上次缓存）。
     *
     * @return 规则列表（可能为空）
     */
    List<QualityRule> fetchRules();
}
