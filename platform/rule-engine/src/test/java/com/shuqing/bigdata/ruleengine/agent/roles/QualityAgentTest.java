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
 * QualityAgent 测试。
 */
class QualityAgentTest {

    private QualityAgent agent;
    private ToolSandbox sandbox;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        sandbox = AgentTestSupport.sandbox();
        registry = AgentTestSupport.emptyRegistry();
        agent = new QualityAgent(AgentTestSupport.looseQuotaEnforcer(),
                AgentTestSupport.permissiveWhitelist(), sandbox, registry);
    }

    @AfterEach
    void tearDown() {
        sandbox.shutdown();
    }

    @Test
    @DisplayName("getRole 返回 QUALITY")
    void getRole() {
        assertEquals(Agent.Role.QUALITY, agent.getRole());
    }

    @Test
    @DisplayName("完整性需求生成 COMPLETENESS 规则")
    void execute_completeness() {
        AgentContext ctx = AgentTestSupport.context("t1", "检查字段完整性非空");
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        Object rules = result.getOutput().get("rules");
        assertInstanceOf(List.class, rules);
        assertFalse(((List<?>) rules).isEmpty());
    }

    @Test
    @DisplayName("唯一性需求生成 UNIQUENESS 规则")
    void execute_uniqueness() {
        AgentContext ctx = AgentTestSupport.context("t1", "检查唯一性");
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        List<?> rules = (List<?>) result.getOutput().get("rules");
        assertTrue(rules.stream().anyMatch(r ->
                ((java.util.Map<?, ?>) r).get("type").equals("UNIQUENESS")));
    }

    @Test
    @DisplayName("空输入返回 INVALID_INPUT")
    void execute_emptyInput() {
        AgentResult result = agent.execute(AgentTestSupport.context("t1", ""));
        assertEquals(AgentResult.Status.INVALID_INPUT, result.getStatus());
    }

    @Test
    @DisplayName("默认需求生成主键非空+唯一")
    void execute_defaultRequirement() {
        AgentContext ctx = AgentTestSupport.context("t1", "检查数据质量");
        AgentResult result = agent.execute(ctx);

        assertTrue(result.isSuccess());
        List<?> rules = (List<?>) result.getOutput().get("rules");
        assertEquals(2, rules.size());
    }
}