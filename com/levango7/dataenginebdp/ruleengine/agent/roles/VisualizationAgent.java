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
 * 可视化 Agent：图表推荐 + 仪表盘生成。
 *
 * <p>根据数据特征推荐合适图表类型，并生成仪表盘配置。
 * 优先调用 {@code recommend_chart} 工具，未注册时回退到内置规则推荐。</p>
 *
 * <p>输出 payload：
 * <ul>
 *   <li>{@code chartType}：推荐图表类型（line/bar/pie/scatter/...）</li>
 *   <li>{@code chartConfig}：ECharts 配置 JSON</li>
 *   <li>{@code dashboard}：仪表盘布局</li>
 *   <li>{@code reason}：推荐理由</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class VisualizationAgent extends BaseAgent {

    private static final String TOOL_RECOMMEND_CHART = "recommend_chart";

    private final ToolSandbox sandbox;
    private final ToolRegistry toolRegistry;

    public VisualizationAgent(QuotaEnforcer quotaEnforcer, ToolWhitelist toolWhitelist,
                               ToolSandbox sandbox, ToolRegistry toolRegistry) {
        super(quotaEnforcer, toolWhitelist);
        this.sandbox = sandbox;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Agent.Role getRole() {
        return Agent.Role.VISUALIZATION;
    }

    @Override
    public AgentResult doExecute(AgentContext context) {
        String description = context.getUserInput();
        if (description == null || description.isBlank()) {
            Object obj = context.getInput("description");
            description = obj == null ? null : String.valueOf(obj);
        }
        if (description == null || description.isBlank()) {
            return AgentResult.failure(getRole(), AgentResult.Status.INVALID_INPUT,
                    "MISSING_DESCRIPTION", "description or userInput must not be blank",
                    0L, context.getTenantId(), context.getRequestId());
        }

        Object dataColumnsObj = context.getInput("columns");
        @SuppressWarnings("unchecked")
        List<String> columns = dataColumnsObj instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();

        List<Map<String, Object>> toolCalls = new ArrayList<>();

        // 优先调用工具
        if (toolRegistry.contains(TOOL_RECOMMEND_CHART)) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("description", description);
            args.put("columns", columns);
            ToolSandbox.ToolInvocation inv = sandbox.invoke(toolRegistry, TOOL_RECOMMEND_CHART, args);
            toolCalls.add(toolCallRecord(TOOL_RECOMMEND_CHART, args));
            if (inv.success() && inv.result() instanceof Map<?, ?> resultMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = new LinkedHashMap<>((Map<String, Object>) resultMap);
                return AgentResult.success(getRole(), output,
                        List.of("dashboard-" + System.currentTimeMillis()), toolCalls,
                        null, context.getTenantId(), context.getRequestId());
            }
        }

        // 回退：内置规则推荐
        String chartType = recommendChartType(description, columns);
        Map<String, Object> chartConfig = buildChartConfig(chartType, columns);
        Map<String, Object> dashboard = buildDashboard(chartType, chartConfig);

        Map<String, Object> output = output(
                "chartType", chartType,
                "chartConfig", chartConfig,
                "dashboard", dashboard,
                "reason", recommendReason(chartType),
                "source", "builtin"
        );
        return AgentResult.success(getRole(), output,
                artifacts("dashboard-" + System.currentTimeMillis()),
                toolCalls, null, context.getTenantId(), context.getRequestId());
    }

    /**
     * 基于关键词推荐图表类型。
     */
    private String recommendChartType(String description, List<String> columns) {
        String lower = description.toLowerCase();
        if (lower.contains("趋势") || lower.contains("trend") || lower.contains("时间")) {
            return "line";
        }
        if (lower.contains("占比") || lower.contains("比例") || lower.contains("pie") || lower.contains("分布")) {
            return "pie";
        }
        if (lower.contains("关系") || lower.contains("correlation") || lower.contains("scatter")) {
            return "scatter";
        }
        if (lower.contains("对比") || lower.contains("比较") || lower.contains("bar")) {
            return "bar";
        }
        if (columns.size() > 3) {
            return "table";
        }
        return "bar";
    }

    private Map<String, Object> buildChartConfig(String chartType, List<String> columns) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", chartType);
        config.put("title", "自动生成图表");
        if (!columns.isEmpty()) {
            config.put("xAxis", columns.get(0));
            config.put("yAxis", columns.size() > 1 ? columns.subList(1, columns.size()) : List.of(columns.get(0)));
        }
        return config;
    }

    private Map<String, Object> buildDashboard(String chartType, Map<String, Object> chartConfig) {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("layout", "grid");
        dashboard.put("widgets", List.of(Map.of(
                "id", "widget-1",
                "type", chartType,
                "config", chartConfig,
                "position", Map.of("row", 1, "col", 1, "width", 6, "height", 4)
        )));
        return dashboard;
    }

    private String recommendReason(String chartType) {
        return switch (chartType) {
            case "line" -> "数据含时间维度，适合折线图展示趋势";
            case "pie" -> "数据呈分布特征，适合饼图展示占比";
            case "scatter" -> "数据含两个连续变量，适合散点图展示相关性";
            case "bar" -> "数据呈分类对比特征，适合柱状图";
            case "table" -> "字段较多，适合表格展示";
            default -> "默认推荐柱状图";
        };
    }
}