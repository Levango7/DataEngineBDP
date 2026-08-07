package com.shuqing.bigdata.ruleengine.agent.core;

import com.shuqing.bigdata.ruleengine.agent.quota.AgentQuota;
import com.shuqing.bigdata.ruleengine.agent.quota.QuotaEnforcer;
import com.shuqing.bigdata.ruleengine.agent.tool.ToolWhitelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 抽象基类，实现模板方法编排公共执行流程。
 *
 * <p>统一处理：
 * <ol>
 *   <li>输入校验：{@link #validateContext(AgentContext)}</li>
 *   <li>配额检查：通过 {@link QuotaEnforcer} 校验租户/角色配额是否超限</li>
 *   <li>白名单校验：通过 {@link ToolWhitelist} 校验 context.allowedTools 是否被角色允许</li>
 *   <li>计时与 tracing：记录执行耗时与 span</li>
 *   <li>业务执行：委托子类 {@link #doExecute(AgentContext)}</li>
 *   <li>异常兜底：捕获业务异常转为 {@link AgentResult#failure}</li>
 *   <li>输出截断：按 {@code maxOutputChars} 截断超长输出</li>
 * </ol>
 *
 * <p>子类只需实现 {@link #doExecute(AgentContext)} 与 {@link #getRole()}，
 * 公共流程由本类保证，避免每个角色重复实现安全与可观测性逻辑。</p>
 *
 * @author shuqing-bigdata
 */
public abstract class BaseAgent implements Agent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** 配额强制执行器，用于运行前配额校验 */
    protected final QuotaEnforcer quotaEnforcer;

    /** 工具白名单，用于校验 context.allowedTools 是否在角色允许范围内 */
    protected final ToolWhitelist toolWhitelist;

    /**
     * 构造基类依赖。
     *
     * @param quotaEnforcer 配额执行器，不应为 {@code null}
     * @param toolWhitelist 工具白名单，不应为 {@code null}
     */
    protected BaseAgent(QuotaEnforcer quotaEnforcer, ToolWhitelist toolWhitelist) {
        this.quotaEnforcer = quotaEnforcer;
        this.toolWhitelist = toolWhitelist;
    }

    /**
     * 模板方法：执行完整 Agent 流程。
     *
     * <p>本方法由 {@link com.shuqing.bigdata.ruleengine.agent.service.AgentService}
     * 调用，子类不应直接重写。</p>
     *
     * @param context 执行上下文
     * @return 执行结果，永不返回 {@code null}
     */
    public final AgentResult execute(AgentContext context) {
        long startNanos = System.nanoTime();
        Agent.Role role = getRole();

        // 1. 输入校验（先校验 null，避免后续解引用 NPE）
        String validationError = validateContext(context);
        if (validationError != null) {
            return finish(role, AgentResult.Status.INVALID_INPUT, "INVALID_INPUT",
                    validationError, startNanos, null, null);
        }

        String tenantId = context.getTenantId();
        String requestId = context.getRequestId();

        // 2. 配额检查
        AgentQuota quota = quotaEnforcer.resolveQuota(role, context);
        AgentResult quotaResult = quotaEnforcer.checkAndAcquire(role, quota, tenantId);
        if (quotaResult != null) {
            return finish(role, quotaResult.getStatus(), quotaResult.getErrorCode(),
                    quotaResult.getErrorMessage(), startNanos, tenantId, requestId);
        }

        try {
            // 3. 白名单校验
            String deniedTool = toolWhitelist.checkAllowed(role, context.getAllowedTools());
            if (deniedTool != null) {
                return finish(role, AgentResult.Status.TOOL_DENIED, "TOOL_NOT_ALLOWED",
                        "Tool '" + deniedTool + "' is not in the whitelist for role " + role,
                        startNanos, tenantId, requestId);
            }

            // 4. 业务执行
            log.debug("Agent {} start, tenant={}, request={}", role, tenantId, requestId);
            AgentResult result = doExecute(context);

            // 5. 输出截断
            result = truncateOutput(result, context.getMaxOutputChars());

            // 6. 补充耗时
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            return result.toBuilder().durationMs(durationMs).build();
        } catch (Exception e) {
            log.error("Agent {} failed, tenant={}, request={}", role, tenantId, requestId, e);
            return finish(role, AgentResult.Status.FAILURE, "AGENT_EXCEPTION",
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    startNanos, tenantId, requestId);
        } finally {
            quotaEnforcer.release(role, quota, tenantId);
        }
    }

    /**
     * 输入校验钩子，子类可重写以增加角色特定校验。
     *
     * @param context 上下文
     * @return 错误消息；返回 {@code null} 表示校验通过
     */
    protected String validateContext(AgentContext context) {
        if (context == null) {
            return "context must not be null";
        }
        if (context.getTenantId() == null || context.getTenantId().isBlank()) {
            return "tenantId must not be blank";
        }
        return null;
    }

    /**
     * 截断超长输出，避免单次调用产生过大 payload 拖垮下游。
     *
     * @param result        原始结果
     * @param maxOutputChars 最大字符数；{@code null} 或非正表示不截断
     * @return 处理后的结果
     */
    protected AgentResult truncateOutput(AgentResult result, Integer maxOutputChars) {
        if (maxOutputChars == null || maxOutputChars <= 0) {
            return result;
        }
        Map<String, Object> output = new LinkedHashMap<>(result.getOutput());
        boolean truncated = false;
        for (Map.Entry<String, Object> entry : output.entrySet()) {
            if (entry.getValue() instanceof String s && s.length() > maxOutputChars) {
                entry.setValue(s.substring(0, maxOutputChars) + "...[truncated]");
                truncated = true;
            }
        }
        if (truncated) {
            output.put("_truncated", true);
            return result.toBuilder().output(output).build();
        }
        return result;
    }

    /**
     * 构造失败结果并补充耗时。
     */
    private AgentResult finish(Agent.Role role, AgentResult.Status status, String errorCode,
                               String errorMessage, long startNanos,
                               String tenantId, String requestId) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        return AgentResult.failure(role, status, errorCode, errorMessage, durationMs, tenantId, requestId);
    }

    /**
     * 工具方法：构造单条工具调用记录。
     *
     * @param toolName 工具名
     * @param args     调用参数
     * @return 工具调用记录 map
     */
    protected Map<String, Object> toolCallRecord(String toolName, Map<String, Object> args) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("tool", toolName);
        record.put("args", args == null ? Map.of() : args);
        record.put("timestamp", System.currentTimeMillis());
        return record;
    }

    /**
     * 工具方法：构造成功输出 map。
     *
     * @param kv 键值对，长度必须为偶数
     * @return LinkedHashMap
     */
    protected Map<String, Object> output(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("kv pairs must be even, got " + kv.length);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    /**
     * 工具方法：构造单产物列表。
     *
     * @param artifact 产物
     * @return 单元素列表
     */
    protected List<String> artifacts(String artifact) {
        return artifact == null ? List.of() : List.of(artifact);
    }
}