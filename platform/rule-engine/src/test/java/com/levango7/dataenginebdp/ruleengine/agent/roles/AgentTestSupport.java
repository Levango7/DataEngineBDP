package com.levango7.dataenginebdp.ruleengine.agent.roles;

import com.levango7.dataenginebdp.ruleengine.agent.core.AgentContext;
import com.levango7.dataenginebdp.ruleengine.agent.quota.AgentQuota;
import com.levango7.dataenginebdp.ruleengine.agent.quota.QuotaEnforcer;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolRegistry;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolSandbox;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolWhitelist;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Agent 角色测试辅助：构造共享依赖。
 */
final class AgentTestSupport {

    private AgentTestSupport() {
    }

    /** 构造宽松配额执行器（高并发、无日限） */
    static QuotaEnforcer looseQuotaEnforcer() {
        Map<com.levango7.dataenginebdp.ruleengine.agent.core.Agent.Role, AgentQuota> quotas =
                new EnumMap<>(com.levango7.dataenginebdp.ruleengine.agent.core.Agent.Role.class);
        AgentQuota loose = AgentQuota.builder()
                .maxToolCalls(100).maxDurationMs(60000L).maxOutputChars(100000)
                .maxConcurrentExecutions(100).dailyCallLimit(0).build();
        for (com.levango7.dataenginebdp.ruleengine.agent.core.Agent.Role role :
                com.levango7.dataenginebdp.ruleengine.agent.core.Agent.Role.values()) {
            quotas.put(role, loose);
        }
        return new QuotaEnforcer(quotas, AgentQuota.fallback());
    }

    /** 构造全放行白名单 */
    static ToolWhitelist permissiveWhitelist() {
        ToolWhitelist wl = new ToolWhitelist();
        for (com.levango7.dataenginebdp.ruleengine.agent.core.Agent.Role role :
                com.levango7.dataenginebdp.ruleengine.agent.core.Agent.Role.values()) {
            wl.reset(role, Set.of(
                    "task_decompose", "nl2sql", "query_metadata", "recommend_chart",
                    "generate_dq_rule", "analyze_lineage", "generate_doc",
                    "generate_code", "audit_sql"
            ));
        }
        return wl;
    }

    /** 构造空工具注册中心 */
    static ToolRegistry emptyRegistry() {
        return new ToolRegistry();
    }

    /** 构造沙箱 */
    static ToolSandbox sandbox() {
        return new ToolSandbox(2, 5000L);
    }

    /** 构造基本上下文 */
    static AgentContext context(String tenantId, String userInput) {
        return AgentContext.builder()
                .tenantId(tenantId)
                .userId("test-user")
                .userInput(userInput)
                .build();
    }
}