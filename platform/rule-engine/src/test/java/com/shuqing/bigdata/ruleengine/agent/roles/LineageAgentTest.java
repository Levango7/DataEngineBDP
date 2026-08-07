package com.shuqing.bigdata.ruleengine.agent.roles;

import com.shuqing.bigdata.ruleengine.agent.core.Agent;
import com.shuqing.bigdata.ruleengine.agent.core.AgentContext;
import com.shuqing.bigdata.ruleengine.agent.core.AgentResult;
import com.shuqing.bigdata.ruleengine.agent.quota.QuotaEnforcer;
import com.shuqing.bigdata.ruleengine.agent.tool.ToolRegistry;
import com.shuqing.bigdata.ruleengine.agent.tool.ToolSandbox;
import com.shuqing.bigdata.ruleengine.agent.tool.ToolWhitelist;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LineageAgent 测试。
 */
class LineageAgentTest {

    private LineageAgent agent;
    private ToolSandbox sandbox;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        sandbox = AgentTestSupport.sandbox();
        registry = AgentTestSupport.emptyRegistry();
        agent = new LineageAgent(AgentTestSupport.looseQuotaEnforcer(),
                AgentTestSupport.permissiveWhitelist(), sandbox, registry);
    }

    @AfterEach
    void tearDown() {
        sandbox.shutdown();
    }

    @Test
    @DisplayName("getRole 返回 LINEAGE")
    void getRole() {
        assertEquals(Agent.Role.LINEAGE, agent.getRole());
    }

    @Test
    @DisplayName("正常输入返回上下游与血缘图")
    void execute_normalInput() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u")
                .attributes(java.util.Map.of("tableName", "orders"))
                .build();
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        assertNotNull(result.getOutput().get("upstream"));
        assertNotNull(result.getOutput().get("downstream"));
        assertNotNull(result.getOutput().get("lineageGraph"));
        assertEquals("orders", result.getOutput().get("target"));
    }

    @Test
    @DisplayName("userInput 作为 tableName 兜底")
    void execute_userInputAsTable() {
        AgentContext ctx = AgentTestSupport.context("t1", "users");
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        assertEquals("users", result.getOutput().get("target"));
    }

    @Test
    @DisplayName("空输入返回 INVALID_INPUT")
    void execute_emptyInput() {
        AgentResult result = agent.execute(AgentTestSupport.context("t1", ""));
        assertEquals(AgentResult.Status.INVALID_INPUT, result.getStatus());
    }

    @Test
    @DisplayName("血缘图包含节点与边")
    void execute_lineageGraphHasNodesAndEdges() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u")
                .attributes(java.util.Map.of("tableName", "t"))
                .build();
        AgentResult result = agent.execute(ctx);

        java.util.Map<?, ?> graph = (java.util.Map<?, ?>) result.getOutput().get("lineageGraph");
        assertNotNull(graph.get("nodes"));
        assertNotNull(graph.get("edges"));
        assertInstanceOf(List.class, graph.get("nodes"));
        assertTrue(((List<?>) graph.get("nodes")).size() > 1);
    }
}