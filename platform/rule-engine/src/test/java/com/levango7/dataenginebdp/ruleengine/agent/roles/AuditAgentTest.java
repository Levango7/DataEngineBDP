package com.levango7.dataenginebdp.ruleengine.agent.roles;

import com.levango7.dataenginebdp.ruleengine.agent.core.Agent;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentContext;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentResult;
import com.levango7.dataenginebdp.ruleengine.agent.quota.QuotaEnforcer;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolRegistry;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolSandbox;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolWhitelist;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuditAgent 测试。
 */
class AuditAgentTest {

    private AuditAgent agent;
    private ToolSandbox sandbox;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        sandbox = AgentTestSupport.sandbox();
        registry = AgentTestSupport.emptyRegistry();
        agent = new AuditAgent(AgentTestSupport.looseQuotaEnforcer(),
                AgentTestSupport.permissiveWhitelist(), sandbox, registry);
    }

    @AfterEach
    void tearDown() {
        sandbox.shutdown();
    }

    @Test
    @DisplayName("getRole 返回 AUDIT")
    void getRole() {
        assertEquals(Agent.Role.AUDIT, agent.getRole());
    }

    @Test
    @DisplayName("安全 SELECT 审核通过")
    void execute_safeSelect() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u")
                .attributes(java.util.Map.of("sql", "SELECT id, name FROM users WHERE id = 1"))
                .build();
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        assertEquals("SAFE", result.getOutput().get("riskLevel"));
        assertTrue((Boolean) result.getOutput().get("passed"));
    }

    @Test
    @DisplayName("DROP TABLE 审核标记 CRITICAL")
    void execute_dropTable() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u")
                .attributes(java.util.Map.of("sql", "DROP TABLE users"))
                .build();
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        assertEquals("CRITICAL", result.getOutput().get("riskLevel"));
        assertFalse((Boolean) result.getOutput().get("passed"));
        List<?> issues = (List<?>) result.getOutput().get("issues");
        assertFalse(issues.isEmpty());
    }

    @Test
    @DisplayName("DELETE 缺 WHERE 标记 CRITICAL")
    void execute_deleteWithoutWhere() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u")
                .attributes(java.util.Map.of("sql", "DELETE FROM users"))
                .build();
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        assertEquals("CRITICAL", result.getOutput().get("riskLevel"));
    }

    @Test
    @DisplayName("SELECT * 标记 LOW")
    void execute_selectStar() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u")
                .attributes(java.util.Map.of("sql", "SELECT * FROM users WHERE id = 1"))
                .build();
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        assertEquals("LOW", result.getOutput().get("riskLevel"));
    }

    @Test
    @DisplayName("空输入返回 INVALID_INPUT")
    void execute_emptyInput() {
        AgentResult result = agent.execute(AgentTestSupport.context("t1", ""));
        assertEquals(AgentResult.Status.INVALID_INPUT, result.getStatus());
    }
}