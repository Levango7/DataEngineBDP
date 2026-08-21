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
 * 质量 Agent：数据质量检查规则生成。
 *
 * <p>根据自然语言需求生成 DQ 规则（完整性、唯一性、范围、格式、一致性等）。
 * 优先调用 {@code generate_dq_rule} 工具，未注册时回退到内置规则模板。</p>
 *
 * <p>输出 payload：
 * <ul>
 *   <li>{@code tableName}：目标表</li>
 *   <li>{@code rules}：DQ 规则列表（每条含 type、column、expression、severity）</li>
 *   <li>{@code summary}：规则汇总</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class QualityAgent extends BaseAgent {

    private static final String TOOL_GENERATE_DQ_RULE = "generate_dq_rule";

    private final ToolSandbox sandbox;
    private final ToolRegistry toolRegistry;

    public QualityAgent(QuotaEnforcer quotaEnforcer, ToolWhitelist toolWhitelist,
                        ToolSandbox sandbox, ToolRegistry toolRegistry) {
        super(quotaEnforcer, toolWhitelist);
        this.sandbox = sandbox;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Agent.Role getRole() {
        return Agent.Role.QUALITY;
    }

    @Override
    public AgentResult doExecute(AgentContext context) {
        String requirement = context.getUserInput();
        if (requirement == null || requirement.isBlank()) {
            Object obj = context.getInput("requirement");
            requirement = obj == null ? null : String.valueOf(obj);
        }
        if (requirement == null || requirement.isBlank()) {
            return AgentResult.failure(getRole(), AgentResult.Status.INVALID_INPUT,
                    "MISSING_REQUIREMENT", "requirement or userInput must not be blank",
                    0L, context.getTenantId(), context.getRequestId());
        }

        String tableName = context.getAttribute("tableName", String.class);
        if (tableName == null) {
            tableName = String.valueOf(context.getInput().getOrDefault("tableName", "target_table"));
        }

        List<Map<String, Object>> toolCalls = new ArrayList<>();

        // 优先调用工具
        if (toolRegistry.contains(TOOL_GENERATE_DQ_RULE)) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("requirement", requirement);
            args.put("tableName", tableName);
            ToolSandbox.ToolInvocation inv = sandbox.invoke(toolRegistry, TOOL_GENERATE_DQ_RULE, args);
            toolCalls.add(toolCallRecord(TOOL_GENERATE_DQ_RULE, args));
            if (inv.success() && inv.result() instanceof Map<?, ?> resultMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = new LinkedHashMap<>((Map<String, Object>) resultMap);
                return AgentResult.success(getRole(), output,
                        List.of("dq-rules-" + System.currentTimeMillis()), toolCalls,
                        null, context.getTenantId(), context.getRequestId());
            }
        }

        // 回退：内置规则模板
        List<Map<String, Object>> rules = generateRules(requirement, tableName);
        Map<String, Object> output = output(
                "tableName", tableName,
                "rules", rules,
                "summary", "生成 " + rules.size() + " 条 DQ 规则",
                "source", "builtin"
        );
        return AgentResult.success(getRole(), output,
                artifacts("dq-rules-" + System.currentTimeMillis()),
                toolCalls, null, context.getTenantId(), context.getRequestId());
    }

    /**
     * 基于关键词生成 DQ 规则。
     */
    private List<Map<String, Object>> generateRules(String requirement, String tableName) {
        List<Map<String, Object>> rules = new ArrayList<>();
        String lower = requirement.toLowerCase();

        if (lower.contains("完整") || lower.contains("非空") || lower.contains("not null")) {
            rules.add(rule("COMPLETENESS", "id", "COUNT(*) = COUNT(id)", "HIGH"));
        }
        if (lower.contains("唯一") || lower.contains("unique") || lower.contains("去重")) {
            rules.add(rule("UNIQUENESS", "id", "COUNT(id) = COUNT(DISTINCT id)", "HIGH"));
        }
        if (lower.contains("范围") || lower.contains("range") || lower.contains("区间")) {
            rules.add(rule("RANGE", "amount", "amount BETWEEN 0 AND 1000000", "MEDIUM"));
        }
        if (lower.contains("格式") || lower.contains("format") || lower.contains("正则")) {
            rules.add(rule("FORMAT", "email", "email ~ '^[^@]+@[^@]+\\\\.[^@]+$'", "MEDIUM"));
        }
        if (lower.contains("一致") || lower.contains("consistent") || lower.contains("参照")) {
            rules.add(rule("REFERENTIAL", "foreign_id",
                    "EXISTS(SELECT 1 FROM ref_table WHERE ref_table.id = " + tableName + ".foreign_id)", "HIGH"));
        }
        if (rules.isEmpty()) {
            // 默认：主键非空 + 唯一
            rules.add(rule("COMPLETENESS", "id", "COUNT(*) = COUNT(id)", "HIGH"));
            rules.add(rule("UNIQUENESS", "id", "COUNT(id) = COUNT(DISTINCT id)", "HIGH"));
        }
        return rules;
    }

    private Map<String, Object> rule(String type, String column, String expression, String severity) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("column", column);
        r.put("expression", expression);
        r.put("severity", severity);
        return r;
    }
}