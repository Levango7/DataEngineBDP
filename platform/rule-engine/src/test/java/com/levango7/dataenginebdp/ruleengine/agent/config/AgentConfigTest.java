package com.levango7.dataenginebdp.ruleengine.agent.config;

import com.levango7.dataenginebdp.ruleengine.agent.core.Agent;
import com.levango7.dataenginebdp.ruleengine.agent.quota.AgentQuota;
import com.levango7.dataenginebdp.ruleengine.agent.quota.QuotaEnforcer;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolRegistry;
import com.levango7.dataenginebdp.ruleengine.agent.tool.ToolWhitelist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentConfig 测试。
 */
class AgentConfigTest {

    private ToolWhitelist whitelist;
    private QuotaEnforcer quotaEnforcer;
    private ToolRegistry registry;
    private AgentConfig config;

    @BeforeEach
    void setUp() {
        whitelist = new ToolWhitelist();
        quotaEnforcer = new QuotaEnforcer();
        registry = new ToolRegistry();
        config = new AgentConfig(whitelist, quotaEnforcer, registry);
    }

    @Test
    @DisplayName("initialize 填充 8 种角色白名单")
    void initialize_shouldFillWhitelists() {
        config.initialize();

        for (Agent.Role role : Agent.Role.values()) {
            assertFalse(whitelist.allowlistOf(role).isEmpty(),
                    "Role " + role + " should have non-empty whitelist");
        }
    }

    @Test
    @DisplayName("initialize 注册内置 query_metadata 工具")
    void initialize_shouldRegisterBuiltinTool() {
        config.initialize();

        assertTrue(registry.contains("query_metadata"));
    }

    @Test
    @DisplayName("enabled=false 时跳过初始化")
    void initialize_disabled_shouldSkip() {
        config.setEnabled(false);
        config.initialize();

        assertTrue(registry.names().isEmpty());
    }

    @Test
    @DisplayName("配置覆盖白名单")
    void initialize_configOverrideWhitelist() {
        Map<String, List<String>> whitelistConfig = new LinkedHashMap<>();
        whitelistConfig.put("SQL", List.of("custom_tool"));
        config.setWhitelist(whitelistConfig);

        config.initialize();

        assertTrue(whitelist.isAllowed(Agent.Role.SQL, "custom_tool"));
        assertFalse(whitelist.isAllowed(Agent.Role.SQL, "nl2sql"));
    }

    @Test
    @DisplayName("配置覆盖配额")
    void initialize_configOverrideQuota() {
        Map<String, AgentConfig.QuotaOverride> quotaConfig = new LinkedHashMap<>();
        AgentConfig.QuotaOverride override = new AgentConfig.QuotaOverride();
        override.setMaxToolCalls(99);
        quotaConfig.put("SQL", override);
        config.setQuota(quotaConfig);

        config.initialize();

        AgentQuota quota = quotaEnforcer.defaultQuotaOf(Agent.Role.SQL);
        assertEquals(99, quota.getMaxToolCalls());
    }

    @Test
    @DisplayName("Sandbox 默认配置")
    void sandbox_defaultConfig() {
        AgentConfig.Sandbox sandbox = new AgentConfig.Sandbox();
        assertEquals(4, sandbox.getPoolSize());
        assertEquals(30_000L, sandbox.getDefaultTimeoutMs());
    }
}