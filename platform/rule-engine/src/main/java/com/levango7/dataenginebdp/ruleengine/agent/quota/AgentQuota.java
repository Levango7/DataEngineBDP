package com.levango7.dataenginebdp.ruleengine.agent.quota;

import com.levango7.dataenginebdp.ruleengine.agent.core.Agent;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.EnumMap;
import java.util.Map;

/**
 * Agent 资源配额。
 *
 * <p>定义单个 Agent 角色在单次执行或租户维度上的资源上限：
 * <ul>
 *   <li>{@code maxToolCalls}：单次执行最大工具调用次数</li>
 *   <li>{@code maxDurationMs}：单次执行最大耗时（毫秒）</li>
 *   <li>{@code maxOutputChars}：单次执行最大输出字符数</li>
 *   <li>{@code maxConcurrentExecutions}：租户维度最大并发执行数</li>
 *   <li>{@code dailyCallLimit}：租户维度每日调用上限（0 表示不限制）</li>
 * </ul>
 *
 * <p>配额来源优先级：{@link com.levango7.dataenginebdp.ruleengine.agent.core.AgentContext}
 * 显式指定 &gt; 角色默认配额（{@link #defaults()}）&gt; 全局兜底。</p>
 *
 * @author shuqing-bigdata
 */
@Getter
@ToString
@Builder(toBuilder = true)
public class AgentQuota {

    /** 单次执行最大工具调用次数 */
    private final Integer maxToolCalls;

    /** 单次执行最大耗时（毫秒） */
    private final Long maxDurationMs;

    /** 单次执行最大输出字符数 */
    private final Integer maxOutputChars;

    /** 租户维度最大并发执行数 */
    private final Integer maxConcurrentExecutions;

    /** 租户维度每日调用上限（0 表示不限制） */
    private final Integer dailyCallLimit;

    /**
     * 合并另一个配额：非空字段以 {@code override} 为准，否则保留本配额值。
     *
     * @param override 覆盖配额；字段为 {@code null} 表示不覆盖
     * @return 合并后的新配额
     */
    public AgentQuota merge(AgentQuota override) {
        if (override == null) {
            return this;
        }
        return AgentQuota.builder()
                .maxToolCalls(override.maxToolCalls != null ? override.maxToolCalls : this.maxToolCalls)
                .maxDurationMs(override.maxDurationMs != null ? override.maxDurationMs : this.maxDurationMs)
                .maxOutputChars(override.maxOutputChars != null ? override.maxOutputChars : this.maxOutputChars)
                .maxConcurrentExecutions(override.maxConcurrentExecutions != null
                        ? override.maxConcurrentExecutions : this.maxConcurrentExecutions)
                .dailyCallLimit(override.dailyCallLimit != null ? override.dailyCallLimit : this.dailyCallLimit)
                .build();
    }

    /**
     * 构造 8 种角色的默认配额。
     *
     * <p>配额设计原则：
     * <ul>
     *   <li>只读/分析类角色（血缘、文档、可视化）配额宽松</li>
     *   <li>生成/写操作类角色（SQL、代码、规划）配额适中</li>
     *   <li>高风险角色（审核）配额严格，限制并发与输出</li>
     * </ul>
     *
     * @return 角色 → 默认配额
     */
    public static Map<Agent.Role, AgentQuota> defaults() {
        Map<Agent.Role, AgentQuota> map = new EnumMap<>(Agent.Role.class);
        map.put(Agent.Role.PLANNING, AgentQuota.builder()
                .maxToolCalls(10).maxDurationMs(60_000L).maxOutputChars(20_000)
                .maxConcurrentExecutions(4).dailyCallLimit(200).build());
        map.put(Agent.Role.SQL, AgentQuota.builder()
                .maxToolCalls(5).maxDurationMs(30_000L).maxOutputChars(10_000)
                .maxConcurrentExecutions(8).dailyCallLimit(500).build());
        map.put(Agent.Role.VISUALIZATION, AgentQuota.builder()
                .maxToolCalls(8).maxDurationMs(45_000L).maxOutputChars(30_000)
                .maxConcurrentExecutions(6).dailyCallLimit(300).build());
        map.put(Agent.Role.QUALITY, AgentQuota.builder()
                .maxToolCalls(6).maxDurationMs(30_000L).maxOutputChars(15_000)
                .maxConcurrentExecutions(6).dailyCallLimit(300).build());
        map.put(Agent.Role.LINEAGE, AgentQuota.builder()
                .maxToolCalls(4).maxDurationMs(60_000L).maxOutputChars(50_000)
                .maxConcurrentExecutions(4).dailyCallLimit(100).build());
        map.put(Agent.Role.DOCUMENTATION, AgentQuota.builder()
                .maxToolCalls(6).maxDurationMs(60_000L).maxOutputChars(50_000)
                .maxConcurrentExecutions(4).dailyCallLimit(100).build());
        map.put(Agent.Role.CODE, AgentQuota.builder()
                .maxToolCalls(8).maxDurationMs(60_000L).maxOutputChars(40_000)
                .maxConcurrentExecutions(4).dailyCallLimit(100).build());
        map.put(Agent.Role.AUDIT, AgentQuota.builder()
                .maxToolCalls(4).maxDurationMs(15_000L).maxOutputChars(10_000)
                .maxConcurrentExecutions(2).dailyCallLimit(1000).build());
        return map;
    }

    /**
     * 全局兜底配额（最宽松）。
     *
     * @return 兜底配额
     */
    public static AgentQuota fallback() {
        return AgentQuota.builder()
                .maxToolCalls(20).maxDurationMs(120_000L).maxOutputChars(100_000)
                .maxConcurrentExecutions(8).dailyCallLimit(0).build();
    }
}