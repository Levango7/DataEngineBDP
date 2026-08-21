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
 * PlanningAgent 测试。
 */
class PlanningAgentTest {

    private QuotaEnforcer quotaEnforcer;
    private ToolWhitelist whitelist;
    private ToolRegistry registry;
    private ToolSandbox sandbox;
    private PlanningAgent agent;

    @BeforeEach
    void setUp() {
        quotaEnforcer = AgentTestSupport.looseQuotaEnforcer();
        whitelist = AgentTestSupport.permissiveWhitelist();
        registry = AgentTestSupport.emptyRegistry();
        sandbox = AgentTestSupport.sandbox();
        agent = new PlanningAgent(quotaEnforcer, whitelist, sandbox, registry);
    }

    @AfterEach
    void tearDown() {
        sandbox.shutdown();
    }

    @Test
    @DisplayName("getRole 返回 PLANNING")
    void getRole() {
        assertEquals(Agent.Role.PLANNING, agent.getRole());
    }

    @Test
    @DisplayName("正常输入返回 SUCCESS 并含 steps 与 dag")
    void execute_normalInput() {
        AgentContext ctx = AgentTestSupport.context("t1", "清洗数据, 转换格式, 加载到数仓");
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        assertEquals(Agent.Role.PLANNING, result.getRole());
        assertNotNull(result.getOutput().get("steps"));
        assertNotNull(result.getOutput().get("dag"));
        Object steps = result.getOutput().get("steps");
        assertInstanceOf(List.class, steps);
        assertTrue(((List<?>) steps).size() >= 1);
    }

    @Test
    @DisplayName("空输入返回 INVALID_INPUT")
    void execute_emptyInput() {
        AgentContext ctx = AgentTestSupport.context("t1", "");
        AgentResult result = agent.execute(ctx);

        assertEquals(AgentResult.Status.INVALID_INPUT, result.getStatus());
        assertEquals("MISSING_TASK", result.getErrorCode());
    }

    @Test
    @DisplayName("null context 返回 INVALID_INPUT")
    void execute_nullContext() {
        AgentResult result = agent.execute(null);
        assertEquals(AgentResult.Status.INVALID_INPUT, result.getStatus());
    }

    @Test
    @DisplayName("blank tenantId 返回 INVALID_INPUT")
    void execute_blankTenant() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("").userInput("task").build();
        AgentResult result = agent.execute(ctx);
        assertEquals(AgentResult.Status.INVALID_INPUT, result.getStatus());
    }

    @Test
    @DisplayName("注册 task_decompose 工具后调用并记录 toolCalls")
    void execute_withTool() {
        registry.register(new com.shuqing.bigdata.ruleengine.agent.tool.Tool(
                "task_decompose", "decompose", com.shuqing.bigdata.ruleengine.agent.tool.Tool.RiskLevel.SAFE,
                args -> java.util.Map.of("steps", List.of("a", "b"), "dag", java.util.Map.of())
        ));
        AgentContext ctx = AgentTestSupport.context("t1", "做某事");
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        assertFalse(result.getToolCalls().isEmpty());
        assertEquals("task_decompose", result.getToolCalls().get(0).get("tool"));
    }

    @Test
    @DisplayName("输出截断按 maxOutputChars 生效")
    void execute_outputTruncation() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u").userInput("task")
                .maxOutputChars(10).build();
        AgentResult result = agent.execute(ctx);
        assertTrue(result.isSuccess());
    }
}