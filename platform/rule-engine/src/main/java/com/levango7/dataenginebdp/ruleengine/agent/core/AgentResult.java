package com.levango7.dataenginebdp.ruleengine.agent.core;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行结果。
 *
 * <p>统一承载 8 种 Agent 角色的输出，包含：
 * <ul>
 *   <li>{@code status}：执行状态（{@link Status#SUCCESS}/{@link Status#FAILURE}/{@link Status#QUOTA_EXCEEDED}/{@link Status#TOOL_DENIED}）</li>
 *   <li>{@code output}：结构化输出 payload（如生成的 SQL、计划 DAG、图表配置）</li>
 *   <li>{@code artifacts}：生成的产物列表（如代码文件、文档片段）</li>
 *   <li>{@code toolCalls}：实际发生的工具调用记录，用于审计与配额对账</li>
 *   <li>{@code durationMs}：执行耗时（毫秒）</li>
 *   <li>{@code errorMessage}/{@code errorCode}：失败原因</li>
 * </ul>
 *
 * <p>不可变值对象，由 {@link BaseAgent} 在执行完成后构造。</p>
 *
 * @author shuqing-bigdata
 */
@Getter
@ToString
@Builder(toBuilder = true)
public class AgentResult {

    /** 角色 */
    private final Agent.Role role;

    /** 执行状态 */
    private final Status status;

    /** 结构化输出 payload */
    private final Map<String, Object> output;

    /** 生成的产物列表（如代码、文档、SQL） */
    private final List<String> artifacts;

    /** 实际发生的工具调用记录（工具名 + 调用参数摘要） */
    private final List<Map<String, Object>> toolCalls;

    /** 执行耗时（毫秒） */
    private final Long durationMs;

    /** 错误码（机器可读，如 QUOTA_EXCEEDED、TOOL_NOT_ALLOWED） */
    private final String errorCode;

    /** 错误消息（人类可读） */
    private final String errorMessage;

    /** 执行完成时间 */
    @Builder.Default
    private final LocalDateTime executedAt = LocalDateTime.now();

    /** 租户 ID（冗余记录，便于审计检索） */
    private final String tenantId;

    /** 请求 ID（冗余记录，便于日志关联） */
    private final String requestId;

    /**
     * 执行状态枚举。
     */
    public enum Status {
        /** 执行成功 */
        SUCCESS,
        /** 执行失败（业务异常） */
        FAILURE,
        /** 配额超限 */
        QUOTA_EXCEEDED,
        /** 工具调用被白名单拒绝 */
        TOOL_DENIED,
        /** 输入校验失败 */
        INVALID_INPUT
    }

    /**
     * 获取只读输出视图。
     *
     * @return 不可变输出 map；若未设置返回空 map
     */
    public Map<String, Object> getOutput() {
        return output == null ? Collections.emptyMap() : Collections.unmodifiableMap(output);
    }

    /**
     * 判断是否成功。
     *
     * @return {@code true} 当 status == SUCCESS
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    /**
     * 构造成功结果。
     *
     * @param role      角色
     * @param output    输出 payload
     * @param artifacts 产物列表
     * @param toolCalls 工具调用记录
     * @param durationMs 耗时
     * @return 成功结果
     */
    public static AgentResult success(Agent.Role role, Map<String, Object> output,
                                      List<String> artifacts, List<Map<String, Object>> toolCalls,
                                      Long durationMs, String tenantId, String requestId) {
        return AgentResult.builder()
                .role(role)
                .status(Status.SUCCESS)
                .output(output == null ? new LinkedHashMap<>() : new LinkedHashMap<>(output))
                .artifacts(artifacts == null ? List.of() : artifacts)
                .toolCalls(toolCalls == null ? List.of() : toolCalls)
                .durationMs(durationMs)
                .tenantId(tenantId)
                .requestId(requestId)
                .build();
    }

    /**
     * 构造失败结果。
     *
     * @param role         角色
     * @param status       失败状态（非 SUCCESS）
     * @param errorCode    错误码
     * @param errorMessage 错误消息
     * @param durationMs   耗时
     * @return 失败结果
     */
    public static AgentResult failure(Agent.Role role, Status status, String errorCode,
                                      String errorMessage, Long durationMs,
                                      String tenantId, String requestId) {
        return AgentResult.builder()
                .role(role)
                .status(status)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .durationMs(durationMs)
                .output(new LinkedHashMap<>())
                .artifacts(List.of())
                .toolCalls(List.of())
                .tenantId(tenantId)
                .requestId(requestId)
                .build();
    }
}