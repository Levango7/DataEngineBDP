package com.levango7.dataenginebdp.ruleengine.agent.service;

import com.levango7.dataenginebdp.ruleengine.agent.core.Agent;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentContext;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentResult;
import com.levango7.dataenginebdp.ruleengine.agent.quota.QuotaEnforcer;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolWhitelist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentService 测试。
 */
class AgentServiceTest {

    private QuotaEnforcer quotaEnforcer;
    private ToolWhitelist toolWhitelist;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        quotaEnforcer = new QuotaEnforcer();
        toolWhitelist = new ToolWhitelist();
        // 构造 mock agents：每个角色一个简单实现
        List<Agent> agents = java.util.Arrays.stream(Agent.Role.values())
                .map(this::mockAgent)
                .toList();
        agentService = new AgentService(agents, quotaEnforcer, toolWhitelist);
    }

    @Test
    @DisplayName("listRoles 返回全部 8 种角色")
    void listRoles_shouldReturnAll8Roles() {
        assertEquals(8, agentService.listRoles().size());
    }

    @Test
    @DisplayName("hasRole 已注册角色返回 true")
    void hasRole_registered_shouldReturnTrue() {
        for (Agent.Role role : Agent.Role.values()) {
            assertTrue(agentService.hasRole(role));
        }
    }

    @Test
    @DisplayName("getAgent 已注册角色返回 Optional 含值")
    void getAgent_registered_shouldReturnPresent() {
        Optional<Agent> agent = agentService.getAgent(Agent.Role.SQL);
        assertTrue(agent.isPresent());
        assertEquals(Agent.Role.SQL, agent.get().getRole());
    }

    @Test
    @DisplayName("describe 返回所有角色元数据")
    void describe_shouldReturnAllRoles() {
        Map<Agent.Role, Map<String, Object>> desc = agentService.describe();
        assertEquals(8, desc.size());
        Map<String, Object> sqlMeta = desc.get(Agent.Role.SQL);
        assertNotNull(sqlMeta.get("implementation"));
        assertNotNull(sqlMeta.get("defaultQuota"));
    }

    @Test
    @DisplayName("register 动态注册覆盖旧 Agent")
    void register_shouldOverride() {
        Agent original = agentService.getAgent(Agent.Role.SQL).orElseThrow();
        Agent newAgent = mockAgent(Agent.Role.SQL);
        Agent previous = agentService.register(newAgent);
        assertEquals(original, previous);
        assertEquals(newAgent, agentService.getAgent(Agent.Role.SQL).orElseThrow());
    }

    @Test
    @DisplayName("execute 路由到正确 Agent")
    void execute_shouldRouteToCorrectAgent() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u").userInput("test").build();
        AgentResult result = agentService.execute(Agent.Role.SQL, ctx);

        assertNotNull(result);
        assertEquals(Agent.Role.SQL, result.getRole());
    }

    @Test
    @DisplayName("execute 未注册角色返回 FAILURE")
    void execute_unregisteredRole_shouldReturnFailure() {
        AgentService emptyService = new AgentService(List.of(), quotaEnforcer, toolWhitelist);
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1").userId("u").userInput("test").build();
        AgentResult result = emptyService.execute(Agent.Role.SQL, ctx);

        assertEquals(AgentResult.Status.FAILURE, result.getStatus());
        assertEquals("agent_not_found", result.getErrorCode());
    }

    /**
     * 构造一个简单的 mock Agent（直接返回成功结果，不走 BaseAgent 模板）。
     */
    private Agent mockAgent(Agent.Role role) {
        return new Agent() {
            @Override
            public Agent.Role getRole() {
                return role;
            }

            @Override
            public AgentResult doExecute(AgentContext context) {
                return AgentResult.success(role, Map.of("mock", true),
                        List.of(), List.of(), 0L,
                        context.getTenantId(), context.getRequestId());
            }
        };
    }
}