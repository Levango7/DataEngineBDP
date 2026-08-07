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
 * 血缘 Agent：数据血缘分析。
 *
 * <p>分析指定表/字段的上下游依赖关系，构建血缘图。
 * 优先调用 {@code analyze_lineage} 工具，未注册时回退到内置模拟血缘。</p>
 *
 * <p>输出 payload：
 * <ul>
 *   <li>{@code target}：分析目标（表名）</li>
 *   <li>{@code upstream}：上游依赖列表</li>
 *   <li>{@code downstream}：下游依赖列表</li>
 *   <li>{@code lineageGraph}：血缘图（节点 + 边）</li>
 *   <li>{@code depth}：分析深度</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class LineageAgent extends BaseAgent {

    private static final String TOOL_ANALYZE_LINEAGE = "analyze_lineage";

    private final ToolSandbox sandbox;
    private final ToolRegistry toolRegistry;

    public LineageAgent(QuotaEnforcer quotaEnforcer, ToolWhitelist toolWhitelist,
                        ToolSandbox sandbox, ToolRegistry toolRegistry) {
        super(quotaEnforcer, toolWhitelist);
        this.sandbox = sandbox;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Agent.Role getRole() {
        return Agent.Role.LINEAGE;
    }

    @Override
    public AgentResult doExecute(AgentContext context) {
        String tableName = context.getAttribute("tableName", String.class);
        if (tableName == null) {
            Object obj = context.getInput().getOrDefault("tableName", context.getUserInput());
            tableName = obj == null ? null : String.valueOf(obj);
        }
        if (tableName == null || tableName.isBlank()) {
            return AgentResult.failure(getRole(), AgentResult.Status.INVALID_INPUT,
                    "MISSING_TABLE", "tableName or userInput must not be blank",
                    0L, context.getTenantId(), context.getRequestId());
        }

        Integer depth = context.getAttribute("depth", Integer.class);
        if (depth == null) {
            Object d = context.getInput().get("depth");
            depth = d instanceof Number n ? n.intValue() : 3;
        }

        List<Map<String, Object>> toolCalls = new ArrayList<>();

        // 优先调用工具
        if (toolRegistry.contains(TOOL_ANALYZE_LINEAGE)) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("tableName", tableName);
            args.put("depth", depth);
            ToolSandbox.ToolInvocation inv = sandbox.invoke(toolRegistry, TOOL_ANALYZE_LINEAGE, args);
            toolCalls.add(toolCallRecord(TOOL_ANALYZE_LINEAGE, args));
            if (inv.success() && inv.result() instanceof Map<?, ?> resultMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = new LinkedHashMap<>((Map<String, Object>) resultMap);
                return AgentResult.success(getRole(), output,
                        List.of("lineage-" + System.currentTimeMillis()), toolCalls,
                        null, context.getTenantId(), context.getRequestId());
            }
        }

        // 回退：内置模拟血缘
        List<String> upstream = List.of(tableName + "_raw", tableName + "_staging");
        List<String> downstream = List.of(tableName + "_mart", tableName + "_report");
        Map<String, Object> lineageGraph = buildLineageGraph(tableName, upstream, downstream);

        Map<String, Object> output = output(
                "target", tableName,
                "upstream", upstream,
                "downstream", downstream,
                "lineageGraph", lineageGraph,
                "depth", depth,
                "source", "builtin"
        );
        return AgentResult.success(getRole(), output,
                artifacts("lineage-" + System.currentTimeMillis()),
                toolCalls, null, context.getTenantId(), context.getRequestId());
    }

    private Map<String, Object> buildLineageGraph(String target,
                                                   List<String> upstream,
                                                   List<String> downstream) {
        List<Map<String, String>> nodes = new ArrayList<>();
        List<Map<String, String>> edges = new ArrayList<>();

        for (String up : upstream) {
            nodes.add(Map.of("id", up, "type", "source"));
            edges.add(Map.of("from", up, "to", target));
        }
        nodes.add(Map.of("id", target, "type", "target"));
        for (String down : downstream) {
            nodes.add(Map.of("id", down, "type", "sink"));
            edges.add(Map.of("from", target, "to", down));
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }
}