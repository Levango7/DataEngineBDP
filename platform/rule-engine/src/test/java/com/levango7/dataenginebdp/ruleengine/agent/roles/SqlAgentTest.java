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

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlAgent 测试。
 */
class SqlAgentTest {

    private QuotaEnforcer quotaEnforcer;
    private ToolWhitelist whitelist;
    private ToolRegistry registry;
    private ToolSandbox sandbox;
    private SqlAgent agent;

    @BeforeEach
    void setUp() {
        quotaEnforcer = AgentTestSupport.looseQuotaEnforcer();
        whitelist = AgentTestSupport.permissiveWhitelist();
        registry = AgentTestSupport.emptyRegistry();
        sandbox = AgentTestSupport.sandbox();
        agent = new SqlAgent(quotaEnforcer, whitelist, sandbox, registry);
    }

    @AfterEach
    void tearDown() {
        sandbox.shutdown();
    }

    @Test
    @DisplayName("getRole 返回 SQL")
    void getRole() {
        assertEquals(Agent.Role.SQL, agent.getRole());
    }

    @Test
    @DisplayName("查询用户数生成 COUNT SQL")
    void execute_countQuery() {
        AgentContext ctx = AgentTestSupport.context("t1", "统计用户数量");
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        String sql = (String) result.getOutput().get("sql");
        assertNotNull(sql);
        assertTrue(sql.toUpperCase().contains("COUNT"));
    }

    @Test
    @DisplayName("空输入返回 INVALID_INPUT")
    void execute_emptyInput() {
        AgentContext ctx = AgentTestSupport.context("t1", "");
        AgentResult result = agent.execute(ctx);
        assertEquals(AgentResult.Status.INVALID_INPUT, result.getStatus());
    }

    @Test
    @DisplayName("nl2sql 工具调用成功")
    void execute_withNl2SqlTool() {
        registry.register(new com.levango7.dataenginebdp.ruleengine.agent.tool.Tool(
                "nl2sql", "NL2SQL", com.levango7.dataenginebdp.ruleengine.agent.tool.Tool.RiskLevel.SAFE,
                args -> java.util.Map.of("sql", "SELECT COUNT(*) FROM users", "confidence", 0.95)
        ));
        AgentContext ctx = AgentTestSupport.context("t1", "用户数");
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        assertEquals("SELECT COUNT(*) FROM users", result.getOutput().get("sql"));
        assertFalse(result.getToolCalls().isEmpty());
    }

    @Test
    @DisplayName("dialect 从 attributes 读取")
    void execute_dialectFromAttributes() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u").userInput("查询订单")
                .attributes(java.util.Map.of("dialect", "mysql"))
                .build();
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        assertEquals("mysql", result.getOutput().get("dialect"));
    }
}