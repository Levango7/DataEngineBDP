package com.shuqing.bigdata.ruleengine.agent.roles;

import com.shuqing.bigdata.ruleengine.agent.core.Agent;
import com.shuqing.bigdata.ruleengine.agent.core.AgentContext;
import com.shuqing.bigdata.ruleengine.agent.core.AgentResult;
import com.shuqing.bigdata.ruleengine.agent.core.BaseAgent;
import com.shuqing.bigdata.ruleengine.agent.quota.QuotaEnforcer;
import com.shuqing.bigdata.ruleengine.agent.tool.ToolRegistry;
import com.shuqing.bigdata.ruleengine.agent.tool.ToolSandbox;
import com.shuqing.bigdata.ruleengine.agent.tool.ToolWhitelist;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划 Agent：任务分解 + 执行计划生成。
 *
 * <p>将复杂任务分解为有序子步骤，生成可执行的 DAG 计划。
 * 优先调用 {@code task_decompose} 工具（若已注册），否则回退到内置启发式分解。</p>
 *
 * <p>输出 payload：
 * <ul>
 *   <li>{@code task}：原始任务描述</li>
 *   <li>{@code steps}：子步骤列表（每步含 id、name、description、dependsOn）</li>
 *   <li>{@code dag}：依赖邻接表</li>
 *   <li>{@code estimatedSteps}：估计步骤数</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class PlanningAgent extends BaseAgent {

    private static final String TOOL_DECOMPOSE = "task_decompose";

    private final ToolSandbox sandbox;
    private final ToolRegistry toolRegistry;

    public PlanningAgent(QuotaEnforcer quotaEnforcer, ToolWhitelist toolWhitelist,
                         ToolSandbox sandbox, ToolRegistry toolRegistry) {
        super(quotaEnforcer, toolWhitelist);
        this.sandbox = sandbox;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Agent.Role getRole() {
        return Agent.Role.PLANNING;
    }

    @Override
    public AgentResult doExecute(AgentContext context) {
        String task = context.getUserInput();
        if (task == null || task.isBlank()) {
            Object obj = context.getInput("task");
            task = obj == null ? null : String.valueOf(obj);
        }
        if (task == null || task.isBlank()) {
            return AgentResult.failure(getRole(), AgentResult.Status.INVALID_INPUT,
                    "MISSING_TASK", "task or userInput must not be blank",
                    0L, context.getTenantId(), context.getRequestId());
        }

        List<Map<String, Object>> toolCalls = new ArrayList<>();

        // 优先调用工具
        if (toolRegistry.contains(TOOL_DECOMPOSE)) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("task", task);
            ToolSandbox.ToolInvocation inv = sandbox.invoke(toolRegistry, TOOL_DECOMPOSE, args);
            toolCalls.add(toolCallRecord(TOOL_DECOMPOSE, args));
            if (inv.success() && inv.result() instanceof Map<?, ?> resultMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = new LinkedHashMap<>((Map<String, Object>) resultMap);
                output.put("task", task);
                return AgentResult.success(getRole(), output,
                        List.of("plan-" + System.currentTimeMillis()), toolCalls,
                        null, context.getTenantId(), context.getRequestId());
            }
        }

        // 回退：内置启发式分解
        List<String> segments = splitTask(task);
        List<Map<String, Object>> steps = buildSteps(segments);
        Map<String, List<String>> dag = buildDag(steps);

        Map<String, Object> output = output(
                "task", task,
                "steps", steps,
                "dag", dag,
                "estimatedSteps", steps.size(),
                "source", "builtin"
        );
        return AgentResult.success(getRole(), output,
                artifacts("plan-" + System.currentTimeMillis()),
                toolCalls, null, context.getTenantId(), context.getRequestId());
    }

    /**
     * 内置任务分解：按标点/连词切分。
     */
    private List<String> splitTask(String task) {
        String[] parts = task.split("[,;，；。]|\\s+然后\\s+|\\s+接着\\s+|\\s+and\\s+");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        if (result.isEmpty()) {
            result.add(task);
        }
        return result;
    }

    /**
     * 构造步骤列表，每步依赖前一步（线性 DAG）。
     */
    private List<Map<String, Object>> buildSteps(List<String> segments) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", "step-" + (i + 1));
            step.put("name", "步骤" + (i + 1));
            step.put("description", segments.get(i));
            step.put("dependsOn", i == 0 ? List.of() : List.of("step-" + i));
            steps.add(step);
        }
        return steps;
    }

    /**
     * 构造 DAG 邻接表。
     */
    private Map<String, List<String>> buildDag(List<Map<String, Object>> steps) {
        Map<String, List<String>> dag = new LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            String id = (String) steps.get(i).get("id");
            List<String> next = i < steps.size() - 1
                    ? List.of((String) steps.get(i + 1).get("id"))
                    : List.of();
            dag.put(id, next);
        }
        return dag;
    }
}