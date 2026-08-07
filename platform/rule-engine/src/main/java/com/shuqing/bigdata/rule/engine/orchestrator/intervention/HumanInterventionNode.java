package com.shuqing.bigdata.rule.engine.orchestrator.intervention;

import com.shuqing.bigdata.rule.engine.orchestrator.dag.DagNode;
import com.shuqing.bigdata.rule.engine.orchestrator.scheduler.TaskExecutor;
import com.shuqing.bigdata.rule.engine.orchestrator.scheduler.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人工介入节点执行器。
 *
 * <p>当 DAG 节点的 taskType 为 {@code HUMAN_INTERVENTION} 时，由调度器分派到本执行器。
 * 执行流程：
 * <ol>
 *   <li>从节点 params 提取 reason / context / timeoutSeconds；</li>
 *   <li>调用 {@link InterventionService#createRequest} 创建介入请求；</li>
 *   <li>调用 {@link InterventionService#awaitResolution} 阻塞等待审批；</li>
 *   <li>审批通过返回 SUCCESS（携带 overrideParams 作为输出）；</li>
 *   <li>审批驳回或超时返回 FAILED。</li>
 * </ol>
 * </p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>本执行器为阻塞式，调度线程在 await 期间不占用 CPU；</li>
 *   <li>审批通过后，overrideParams 作为节点输出传递给下游节点；</li>
 *   <li>超时时间优先取节点 params.timeoutSeconds，缺省取配置项
 *       {@code orchestrator.intervention.default-timeout-ms}（默认 30 分钟）。</li>
 * </ul>
 * </p>
 */
@Component
public class HumanInterventionNode implements TaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(HumanInterventionNode.class);

    /** taskType 常量 */
    public static final String TASK_TYPE = "HUMAN_INTERVENTION";

    /** params 字段名常量 */
    private static final String PARAM_REASON = "reason";
    private static final String PARAM_CONTEXT = "context";
    private static final String PARAM_TIMEOUT_MS = "timeoutMs";
    private static final String PARAM_EXEC_ID = "execId";

    private final InterventionService interventionService;

    /** 默认超时时间（毫秒），默认 30 分钟 */
    @Value("${orchestrator.intervention.default-timeout-ms:1800000}")
    private long defaultTimeoutMs;

    @Autowired
    public HumanInterventionNode(InterventionService interventionService) {
        this.interventionService = interventionService;
    }

    @Override
    public TaskResult execute(DagNode node, Map<String, TaskResult> context) {
        long start = System.currentTimeMillis();
        Map<String, Object> params = node.getParams() != null ? node.getParams() : Map.of();

        // 提取介入参数
        String reason = (String) params.getOrDefault(PARAM_REASON, "人工审批节点：" + node.getName());
        @SuppressWarnings("unchecked")
        Map<String, Object> interventionContext = (Map<String, Object>) params.get(PARAM_CONTEXT);
        if (interventionContext == null) {
            interventionContext = buildDefaultContext(node, context);
        }
        long timeoutMs = extractTimeoutMs(params);
        String execId = (String) params.get(PARAM_EXEC_ID);

        // 创建介入请求
        InterventionRequest request = interventionService.createRequest(
                extractDagId(node, params), execId, node.getId(), node.getName(),
                reason, interventionContext);

        log.info("[HUMAN-INTERVENTION] node={} requestId={} waiting for approval (timeout={}ms)",
                node.getId(), request.getId(), timeoutMs);

        // 阻塞等待审批
        InterventionRequest resolved = interventionService.awaitResolution(request.getId(), timeoutMs);
        long duration = System.currentTimeMillis() - start;

        if (resolved == null) {
            return TaskResult.failure(node.getId(), "介入请求丢失", duration);
        }

        if (resolved.isApproved()) {
            // 批准：overrideParams 作为输出传递给下游
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("approved", true);
            output.put("approver", resolved.getApprover());
            output.put("comment", resolved.getComment());
            if (resolved.getOverrideParams() != null) {
                output.put("overrideParams", resolved.getOverrideParams());
            }
            return TaskResult.success(node.getId(), output, duration);
        }

        // 驳回或超时
        String errorMsg = "介入" + resolved.getStatus() + ": " + resolved.getComment();
        return TaskResult.failure(node.getId(), errorMsg, duration);
    }

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    /* ------------------------------ 内部方法 ------------------------------ */

    /**
     * 从节点 params 提取超时时间，缺省使用全局配置。
     */
    private long extractTimeoutMs(Map<String, Object> params) {
        Object v = params.get(PARAM_TIMEOUT_MS);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (NumberFormatException ignored) {
                // fallthrough
            }
        }
        return defaultTimeoutMs;
    }

    /**
     * 从节点 params 提取 dagId（params.dagId 优先，否则从 node.command 解析）。
     */
    private String extractDagId(DagNode node, Map<String, Object> params) {
        Object v = params.get("dagId");
        if (v instanceof String) {
            return (String) v;
        }
        // 兜底：使用节点 ID 前缀作为 dagId 占位
        return "dag-for-" + node.getId();
    }

    /**
     * 构建默认上下文：包含节点基本信息与上游输出摘要。
     */
    private Map<String, Object> buildDefaultContext(DagNode node, Map<String, TaskResult> context) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("nodeId", node.getId());
        ctx.put("nodeName", node.getName());
        ctx.put("taskType", node.getTaskType());
        ctx.put("command", node.getCommand());
        // 上游输出摘要
        Map<String, Object> upstream = new LinkedHashMap<>();
        for (Map.Entry<String, TaskResult> entry : context.entrySet()) {
            TaskResult r = entry.getValue();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", r.getStatus());
            summary.put("durationMs", r.getDurationMs());
            if (r.getOutput() != null) {
                summary.put("output", r.getOutput());
            }
            upstream.put(entry.getKey(), summary);
        }
        ctx.put("upstream", upstream);
        return ctx;
    }
}