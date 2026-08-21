package com.shuqing.bigdata.ruleengine.agent.core;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 执行上下文。
 *
 * <p>承载一次 Agent 调用所需的全部输入与运行时元数据：
 * <ul>
 *   <li>业务输入：{@code input}（自然语言/参数键值对）+ {@code userInput}（原始文本）</li>
 *   <li>身份上下文：{@code tenantId}、{@code userId}（多租户隔离与审计）</li>
 *   <li>资源约束：{@code maxToolCalls}、{@code maxDurationMs}、{@code maxOutputChars}（运行期配额）</li>
 *   <li>工具白名单：{@code allowedTools}（限制本次可调用的工具集合）</li>
 *   <li>追踪元数据：{@code traceId}、{@code requestId}（关联 OpenTelemetry span）</li>
 *   <li>扩展属性：{@code attributes}（角色特定参数，如 schema、表名、SQL 方言）</li>
 * </ul>
 *
 * <p>不可变值对象，由 {@link com.shuqing.bigdata.ruleengine.agent.service.AgentService}
 * 在编排阶段构造后传入 Agent，避免 Agent 内部修改上下文造成副作用。</p>
 *
 * @author shuqing-bigdata
 */
@Getter
@ToString
@Builder(toBuilder = true)
public class AgentContext {

    /** 业务输入参数键值对（如 {"question": "...", "schema": "..."}） */
    private final Map<String, Object> input;

    /** 原始用户输入文本（自然语言提问/指令） */
    private final String userInput;

    /** 租户 ID，用于多租户隔离与配额归属 */
    private final String tenantId;

    /** 用户 ID，用于审计与权限校验 */
    private final String userId;

    /** 本次执行允许的最大工具调用次数，{@code null} 表示使用角色默认配额 */
    private final Integer maxToolCalls;

    /** 本次执行最大耗时（毫秒），{@code null} 表示使用角色默认配额 */
    private final Long maxDurationMs;

    /** 本次执行最大输出字符数，超出将被截断并标记 */
    private final Integer maxOutputChars;

    /** 本次执行允许调用的工具白名单，{@code null} 表示使用角色默认白名单 */
    private final java.util.Set<String> allowedTools;

    /** 链路追踪 ID，关联 OpenTelemetry span */
    private final String traceId;

    /** 请求 ID，用于日志关联 */
    private final String requestId;

    /** 角色特定扩展属性 */
    private final Map<String, Object> attributes;

    /** 创建时间，用于耗时统计 */
    @Builder.Default
    private final LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 获取只读输入视图。
     *
     * @return 不可变输入 map；若未设置返回空 map
     */
    public Map<String, Object> getInput() {
        return input == null ? Collections.emptyMap() : Collections.unmodifiableMap(input);
    }

    /**
     * 获取只读扩展属性视图。
     *
     * @return 不可变属性 map；若未设置返回空 map
     */
    public Map<String, Object> getAttributes() {
        return attributes == null ? Collections.emptyMap() : Collections.unmodifiableMap(attributes);
    }

    /**
     * 从输入 map 中按 key 取值。
     *
     * @param key 输入键
     * @return 值；不存在返回 {@code null}
     */
    public Object getInput(String key) {
        return getInput().get(Objects.requireNonNull(key, "key"));
    }

    /**
     * 从扩展属性中按 key 取值并转换为指定类型。
     *
     * @param key        属性键
     * @param targetType 目标类型
     * @param <T>        目标类型泛型
     * @return 转换后的值；不存在或类型不匹配返回 {@code null}
     */
    public <T> T getAttribute(String key, Class<T> targetType) {
        Object value = getAttributes().get(Objects.requireNonNull(key, "key"));
        if (value == null) {
            return null;
        }
        if (targetType.isInstance(value)) {
            return targetType.cast(value);
        }
        return null;
    }

    /**
     * 创建一个空输入的上下文构造器。
     *
     * @return 预填空输入与空属性的 builder
     */
    public static AgentContextBuilder minimal(String tenantId, String userId, String userInput) {
        return AgentContext.builder()
                .tenantId(tenantId)
                .userId(userId)
                .userInput(userInput)
                .input(new HashMap<>())
                .attributes(new HashMap<>());
    }
}