package com.levango7.dataenginebdp.ruleengine.agent.roles;

import com.levango7.dataenginebdp.ruleengine.agent.core.Agent;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentContext;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentResult;
import com.levango7.dataenginebdp.ruleengine.agent.core.BaseAgent;
import com.levango7.dataenginebdp.ruleengine.agent.quota.QuotaEnforcer;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolRegistry;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolSandbox;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolWhitelist;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL Agent：自然语言转 SQL（对接 T010 NL2SQL）。
 *
 * <p>优先调用 {@code nl2sql} 工具（对接 T010 NL2SQL 服务），
 * 工具未注册时回退到内置模板生成（仅用于演示与测试）。</p>
 *
 * <p>输出 payload：
 * <ul>
 *   <li>{@code sql}：生成的 SQL</li>
 *   <li>{@code dialect}：SQL 方言（默认 postgres）</li>
 *   <li>{@code explanation}：SQL 解释</li>
 *   <li>{@code tables}：涉及的表</li>
 *   <li>{@code confidence}：置信度（0-1）</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class SqlAgent extends BaseAgent {

    private static final String TOOL_NL2SQL = "nl2sql";

    private final ToolSandbox sandbox;
    private final ToolRegistry toolRegistry;

    public SqlAgent(QuotaEnforcer quotaEnforcer, ToolWhitelist toolWhitelist,
                    ToolSandbox sandbox, ToolRegistry toolRegistry) {
        super(quotaEnforcer, toolWhitelist);
        this.sandbox = sandbox;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Agent.Role getRole() {
        return Agent.Role.SQL;
    }

    @Override
    public AgentResult doExecute(AgentContext context) {
        String question = context.getUserInput();
        if (question == null || question.isBlank()) {
            Object obj = context.getInput("question");
            question = obj == null ? null : String.valueOf(obj);
        }
        if (question == null || question.isBlank()) {
            return AgentResult.failure(getRole(), AgentResult.Status.INVALID_INPUT,
                    "MISSING_QUESTION", "question or userInput must not be blank",
                    0L, context.getTenantId(), context.getRequestId());
        }

        String dialect = context.getAttribute("dialect", String.class);
        if (dialect == null) {
            dialect = String.valueOf(context.getInput().getOrDefault("dialect", "postgres"));
        }
        String schema = context.getAttribute("schema", String.class);
        if (schema == null) {
            schema = String.valueOf(context.getInput().getOrDefault("schema", "public"));
        }

        List<Map<String, Object>> toolCalls = new ArrayList<>();

        // 优先调用 nl2sql 工具
        if (toolRegistry.contains(TOOL_NL2SQL)) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("question", question);
            args.put("dialect", dialect);
            args.put("schema", schema);
            ToolSandbox.ToolInvocation inv = sandbox.invoke(toolRegistry, TOOL_NL2SQL, args);
            toolCalls.add(toolCallRecord(TOOL_NL2SQL, args));
            if (inv.success() && inv.result() instanceof Map<?, ?> resultMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = new LinkedHashMap<>((Map<String, Object>) resultMap);
                output.putIfAbsent("dialect", dialect);
                return AgentResult.success(getRole(), output,
                        List.of("sql-" + System.currentTimeMillis()), toolCalls,
                        null, context.getTenantId(), context.getRequestId());
            }
        }

        // 回退：内置模板生成
        String table = extractTable(question);
        String sql = buildFallbackSql(question, table, schema);
        Map<String, Object> output = output(
                "sql", sql,
                "dialect", dialect,
                "explanation", "基于关键词识别生成的模板 SQL，建议接入 T010 NL2SQL 服务获取更精准结果",
                "tables", List.of(schema + "." + table),
                "confidence", 0.5,
                "source", "builtin"
        );
        return AgentResult.success(getRole(), output,
                artifacts(sql), toolCalls,
                null, context.getTenantId(), context.getRequestId());
    }

    /**
     * 从问题中抽取表名（简单关键词匹配）。
     */
    private String extractTable(String question) {
        String lower = question.toLowerCase();
        if (lower.contains("user") || lower.contains("用户")) {
            return "users";
        }
        if (lower.contains("order") || lower.contains("订单")) {
            return "orders";
        }
        if (lower.contains("product") || lower.contains("商品")) {
            return "products";
        }
        return "data_table";
    }

    /**
     * 构造回退 SQL。
     */
    private String buildFallbackSql(String question, String table, String schema) {
        String lower = question.toLowerCase();
        if (lower.contains("count") || lower.contains("多少") || lower.contains("数量")) {
            return "SELECT COUNT(*) AS cnt FROM " + schema + "." + table + ";";
        }
        if (lower.contains("sum") || lower.contains("总和") || lower.contains("总计")) {
            return "SELECT SUM(amount) AS total FROM " + schema + "." + table + ";";
        }
        return "SELECT * FROM " + schema + "." + table + " LIMIT 100;";
    }

}
