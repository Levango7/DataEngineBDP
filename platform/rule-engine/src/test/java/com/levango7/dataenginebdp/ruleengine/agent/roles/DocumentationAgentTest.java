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
 * DocumentationAgent 测试。
 */
class DocumentationAgentTest {

    private DocumentationAgent agent;
    private ToolSandbox sandbox;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        sandbox = AgentTestSupport.sandbox();
        registry = AgentTestSupport.emptyRegistry();
        agent = new DocumentationAgent(AgentTestSupport.looseQuotaEnforcer(),
                AgentTestSupport.permissiveWhitelist(), sandbox, registry);
    }

    @AfterEach
    void tearDown() {
        sandbox.shutdown();
    }

    @Test
    @DisplayName("getRole 返回 DOCUMENTATION")
    void getRole() {
        assertEquals(Agent.Role.DOCUMENTATION, agent.getRole());
    }

    @Test
    @DisplayName("正常输入生成 Markdown 文档")
    void execute_normalInput() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u")
                .attributes(java.util.Map.of("tableName", "orders"))
                .build();
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        String doc = (String) result.getOutput().get("document");
        assertNotNull(doc);
        assertTrue(doc.contains("# 数据资产文档：orders"));
        assertEquals("markdown", result.getOutput().get("format"));
    }

    @Test
    @DisplayName("空输入返回 INVALID_INPUT")
    void execute_emptyInput() {
        AgentResult result = agent.execute(AgentTestSupport.context("t1", ""));
        assertEquals(AgentResult.Status.INVALID_INPUT, result.getStatus());
    }

    @Test
    @DisplayName("自定义 columns 出现在文档中")
    void execute_customColumns() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u")
                .attributes(java.util.Map.of("tableName", "t"))
                .input(java.util.Map.of("columns", java.util.List.of("id", "name", "amount")))
                .build();
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        String doc = (String) result.getOutput().get("document");
        assertTrue(doc.contains("id"));
        assertTrue(doc.contains("amount"));
    }
}