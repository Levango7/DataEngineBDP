package com.levango7.dataenginebdp.ruleengine.agent.controller;

import jakarta.validation.constraints.Max;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;
import java.util.Set;

/**
 * Agent 执行请求体。
 *
 * <p>对应 {@code POST /api/v1/agents/{role}/execute} 请求体。
 * 租户与用户 ID 不在请求体中传递，由 JWT（{@link com.levango7.dataenginebdp.common.security.TenantContext}）解析。</p>
 *
 * @author shuqing-bigdata
 */
@Getter
@Setter
@ToString
public class AgentExecutionRequest {

    /** 原始用户输入文本（自然语言提问/指令） */
    private String userInput;

    /** 业务输入参数键值对 */
    private Map<String, Object> input;

    /** 角色特定扩展属性 */
    private Map<String, Object> attributes;

    /** 本次执行允许的最大工具调用次数 */
    private Integer maxToolCalls;

    /** 本次执行最大耗时（毫秒） */
    private Long maxDurationMs;

    /** 本次执行最大输出字符数 */
    @Max(value = 1_000_000, message = "maxOutputChars must be <= 1000000")
    private Integer maxOutputChars;

    /** 本次执行允许调用的工具白名单 */
    private Set<String> allowedTools;

    /** 链路追踪 ID */
    private String traceId;
}