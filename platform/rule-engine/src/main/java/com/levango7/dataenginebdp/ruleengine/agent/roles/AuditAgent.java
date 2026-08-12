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
 * 审核 Agent：SQL/配置安全审核。
 *
 * <p>对输入的 SQL 或配置进行安全审核，识别危险操作、注入风险、权限问题等。
 * 优先调用 {@code audit_sql} 工具，未注册时回退到内置规则审核。</p>
 *
 * <p>输出 payload：
 * <ul>
 *   <li>{@code target}：审核目标</li>
 *   <li>{@code riskLevel}：风险等级（SAFE/LOW/MEDIUM/HIGH/CRITICAL）</li>
 *   <li>{@code issues}：发现的问题列表（每条含 type、severity、message、suggestion）</li>
 *   <li>{@code passed}：是否通过审核</li>
 *   <li>{@code recommendations}：改进建议</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class AuditAgent extends BaseAgent {

    private static final String TOOL_AUDIT_SQL = "audit_sql";

    /** 危险 SQL 关键词 */
    private static final List<String> DANGEROUS_KEYWORDS = List.of(
            "DROP", "TRUNCATE", "DELETE", "GRANT", "REVOKE", "ALTER", "CREATE USER"
    );

    /** 中等风险关键词 */
    private static final List<String> MEDIUM_KEYWORDS = List.of(
            "UPDATE", "INSERT", "MERGE", "EXEC", "EXECUTE"
    );

    private final ToolSandbox sandbox;
    private final ToolRegistry toolRegistry;

    public AuditAgent(QuotaEnforcer quotaEnforcer, ToolWhitelist toolWhitelist,
                      ToolSandbox sandbox, ToolRegistry toolRegistry) {
        super(quotaEnforcer, toolWhitelist);
        this.sandbox = sandbox;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Agent.Role getRole() {
        return Agent.Role.AUDIT;
    }

    @Override
    public AgentResult doExecute(AgentContext context) {
        String target = context.getAttribute("sql", String.class);
        if (target == null) {
            target = context.getAttribute("config", String.class);
        }
        if (target == null) {
            target = context.getUserInput();
        }
        if (target == null || target.isBlank()) {
            return AgentResult.failure(getRole(), AgentResult.Status.INVALID_INPUT,
                    "MISSING_TARGET", "sql/config or userInput must not be blank",
                    0L, context.getTenantId(), context.getRequestId());
        }

        List<Map<String, Object>> toolCalls = new ArrayList<>();

        // 优先调用工具
        if (toolRegistry.contains(TOOL_AUDIT_SQL)) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("target", target);
            ToolSandbox.ToolInvocation inv = sandbox.invoke(toolRegistry, TOOL_AUDIT_SQL, args);
            toolCalls.add(toolCallRecord(TOOL_AUDIT_SQL, args));
            if (inv.success() && inv.result() instanceof Map<?, ?> resultMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = new LinkedHashMap<>((Map<String, Object>) resultMap);
                return AgentResult.success(getRole(), output,
                        List.of("audit-report-" + System.currentTimeMillis()), toolCalls,
                        null, context.getTenantId(), context.getRequestId());
            }
        }

        // 回退：内置规则审核
        List<Map<String, Object>> issues = auditByRules(target);
        String riskLevel = determineRiskLevel(issues);
        boolean passed = "SAFE".equals(riskLevel) || "LOW".equals(riskLevel);
        List<String> recommendations = buildRecommendations(issues);

        Map<String, Object> output = output(
                "target", target,
                "riskLevel", riskLevel,
                "issues", issues,
                "passed", passed,
                "recommendations", recommendations,
                "source", "builtin"
        );
        return AgentResult.success(getRole(), output,
                artifacts("audit-report-" + System.currentTimeMillis()),
                toolCalls, null, context.getTenantId(), context.getRequestId());
    }

    /**
     * 基于规则审核 SQL。
     */
    private List<Map<String, Object>> auditByRules(String target) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String upper = target.toUpperCase();

        for (String kw : DANGEROUS_KEYWORDS) {
            if (upper.contains(kw)) {
                issues.add(issue("DANGEROUS_OPERATION", "CRITICAL",
                        "检测到危险操作: " + kw,
                        "请确认是否真的需要执行 " + kw + "，建议在非生产环境验证"));
            }
        }
        for (String kw : MEDIUM_KEYWORDS) {
            if (upper.contains(kw)) {
                issues.add(issue("WRITE_OPERATION", "MEDIUM",
                        "检测到写操作: " + kw,
                        "确保有 WHERE 条件，避免全表更新"));
            }
        }
        if (upper.contains("SELECT *")) {
            issues.add(issue("SELECT_STAR", "LOW",
                    "使用 SELECT *",
                    "建议明确列出所需字段，减少数据传输与 schema 耦合"));
        }
        if (!upper.contains("WHERE") && (upper.contains("UPDATE") || upper.contains("DELETE"))) {
            issues.add(issue("MISSING_WHERE", "CRITICAL",
                    "UPDATE/DELETE 缺少 WHERE 条件",
                    "必须添加 WHERE 条件，避免全表操作"));
        }
        if (target.contains("'") && target.contains("--")) {
            issues.add(issue("POSSIBLE_INJECTION", "HIGH",
                    "疑似 SQL 注入模式",
                    "检查输入参数是否经过预编译/参数化处理"));
        }
        return issues;
    }

    private String determineRiskLevel(List<Map<String, Object>> issues) {
        if (issues.isEmpty()) {
            return "SAFE";
        }
        boolean hasCritical = false;
        boolean hasHigh = false;
        boolean hasMedium = false;
        for (Map<String, Object> issue : issues) {
            String severity = String.valueOf(issue.get("severity"));
            if ("CRITICAL".equals(severity)) hasCritical = true;
            else if ("HIGH".equals(severity)) hasHigh = true;
            else if ("MEDIUM".equals(severity)) hasMedium = true;
        }
        if (hasCritical) return "CRITICAL";
        if (hasHigh) return "HIGH";
        if (hasMedium) return "MEDIUM";
        return "LOW";
    }

    private List<String> buildRecommendations(List<Map<String, Object>> issues) {
        List<String> recs = new ArrayList<>();
        for (Map<String, Object> issue : issues) {
            Object suggestion = issue.get("suggestion");
            if (suggestion != null) {
                recs.add(String.valueOf(suggestion));
            }
        }
        if (recs.isEmpty()) {
            recs.add("未发现安全问题，代码可放行");
        }
        return recs;
    }

    private Map<String, Object> issue(String type, String severity, String message, String suggestion) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("severity", severity);
        r.put("message", message);
        r.put("suggestion", suggestion);
        return r;
    }
}