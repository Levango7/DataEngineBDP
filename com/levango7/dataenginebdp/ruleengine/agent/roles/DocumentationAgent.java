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
 * 文档 Agent：数据资产文档生成。
 *
 * <p>为指定表/数据集生成 Markdown 文档，包含字段说明、业务含义、示例等。
 * 优先调用 {@code generate_doc} 工具，未注册时回退到内置模板。</p>
 *
 * <p>输出 payload：
 * <ul>
 *   <li>{@code tableName}：表名</li>
 *   <li>{@code document}：Markdown 文档内容</li>
 *   <li>{@code sections}：文档章节列表</li>
 *   <li>{@code format}：文档格式（markdown）</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class DocumentationAgent extends BaseAgent {

    private static final String TOOL_GENERATE_DOC = "generate_doc";

    private final ToolSandbox sandbox;
    private final ToolRegistry toolRegistry;

    public DocumentationAgent(QuotaEnforcer quotaEnforcer, ToolWhitelist toolWhitelist,
                              ToolSandbox sandbox, ToolRegistry toolRegistry) {
        super(quotaEnforcer, toolWhitelist);
        this.sandbox = sandbox;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Agent.Role getRole() {
        return Agent.Role.DOCUMENTATION;
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

        Object columnsObj = context.getInput("columns");
        @SuppressWarnings("unchecked")
        List<String> columns = columnsObj instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();

        List<Map<String, Object>> toolCalls = new ArrayList<>();

        // 优先调用工具
        if (toolRegistry.contains(TOOL_GENERATE_DOC)) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("tableName", tableName);
            args.put("columns", columns);
            ToolSandbox.ToolInvocation inv = sandbox.invoke(toolRegistry, TOOL_GENERATE_DOC, args);
            toolCalls.add(toolCallRecord(TOOL_GENERATE_DOC, args));
            if (inv.success() && inv.result() instanceof Map<?, ?> resultMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = new LinkedHashMap<>((Map<String, Object>) resultMap);
                return AgentResult.success(getRole(), output,
                        List.of("doc-" + System.currentTimeMillis()), toolCalls,
                        null, context.getTenantId(), context.getRequestId());
            }
        }

        // 回退：内置模板
        String document = buildDocument(tableName, columns);
        List<String> sections = List.of("概述", "字段说明", "业务含义", "使用示例");

        Map<String, Object> output = output(
                "tableName", tableName,
                "document", document,
                "sections", sections,
                "format", "markdown",
                "source", "builtin"
        );
        return AgentResult.success(getRole(), output,
                artifacts(document), toolCalls,
                null, context.getTenantId(), context.getRequestId());
    }

    private String buildDocument(String tableName, List<String> columns) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 数据资产文档：").append(tableName).append("\n\n");
        sb.append("## 1. 概述\n\n");
        sb.append("本表 `").append(tableName).append("` 存储").append(tableName).append("相关业务数据。\n\n");
        sb.append("## 2. 字段说明\n\n");
        sb.append("| 字段名 | 类型 | 说明 |\n");
        sb.append("|--------|------|------|\n");
        if (columns.isEmpty()) {
            sb.append("| id | BIGINT | 主键 |\n");
            sb.append("| name | VARCHAR | 名称 |\n");
            sb.append("| created_at | TIMESTAMP | 创建时间 |\n");
        } else {
            for (String col : columns) {
                sb.append("| ").append(col).append(" | - | 待补充 |\n");
            }
        }
        sb.append("\n## 3. 业务含义\n\n");
        sb.append("待业务方补充。\n\n");
        sb.append("## 4. 使用示例\n\n");
        sb.append("```sql\nSELECT * FROM ").append(tableName).append(" LIMIT 10;\n```\n");
        return sb.toString();
    }
}