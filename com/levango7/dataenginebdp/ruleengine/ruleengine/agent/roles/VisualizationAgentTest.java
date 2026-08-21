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

import static org.junit.jupiter.api.Assertions.*;

/**
 * VisualizationAgent 测试。
 */
class VisualizationAgentTest {

    private VisualizationAgent agent;
    private ToolSandbox sandbox;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        sandbox = AgentTestSupport.sandbox();
        registry = AgentTestSupport.emptyRegistry();
        agent = new VisualizationAgent(AgentTestSupport.looseQuotaEnforcer(),
                AgentTestSupport.permissiveWhitelist(), sandbox, registry);
    }

    @AfterEach
    void tearDown() {
        sandbox.shutdown();
    }

    @Test
    @DisplayName("getRole 返回 VISUALIZATION")
    void getRole() {
        assertEquals(Agent.Role.VISUALIZATION, agent.getRole());
    }

    @Test
    @DisplayName("趋势数据推荐 line 图")
    void execute_trend_shouldRecommendLine() {
        AgentContext ctx = AgentTestSupport.context("t1", "展示销售趋势");
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        assertEquals("line", result.getOutput().get("chartType"));
        assertNotNull(result.getOutput().get("dashboard"));
    }

    @Test
    @DisplayName("占比数据推荐 pie 图")
    void execute_proportion_shouldRecommendPie() {
        AgentContext ctx = AgentTestSupport.context("t1", "展示各品类占比");
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        assertEquals("pie", result.getOutput().get("chartType"));
    }

    @Test
    @DisplayName("空输入返回 INVALID_INPUT")
    void execute_emptyInput() {
        AgentResult result = agent.execute(AgentTestSupport.context("t1", ""));
        assertEquals(AgentResult.Status.INVALID_INPUT, result.getStatus());
    }

    @Test
    @DisplayName("recommend_chart 工具调用")
    void execute_withTool() {
        registry.register(new com.shuqing.bigdata.ruleengine.agent.tool.Tool(
                "recommend_chart", "chart", com.shuqing.bigdata.ruleengine.agent.tool.Tool.RiskLevel.SAFE,
                args -> java.util.Map.of("chartType", "bar", "chartConfig", java.util.Map.of())
        ));
        AgentResult result = agent.execute(AgentTestSupport.context("t1", "对比数据"));

        assertTrue(result.isSuccess());
        assertEquals("bar", result.getOutput().get("chartType"));
        assertFalse(result.getToolCalls().isEmpty());
    }
}