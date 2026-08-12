package com.levango7.dataenginebdp.ruleengine.agent.quota;

import com.levango7.dataenginebdp.ruleengine.agent.core.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentQuota 测试。
 */
class AgentQuotaTest {

    @Test
    @DisplayName("merge 覆盖非空字段")
    void merge_shouldOverrideNonNullFields() {
        AgentQuota base = AgentQuota.builder()
                .maxToolCalls(10).maxDurationMs(60000L).maxOutputChars(20000)
                .maxConcurrentExecutions(4).dailyCallLimit(200).build();
        AgentQuota override = AgentQuota.builder()
                .maxToolCalls(5).build();

        AgentQuota merged = base.merge(override);
        assertEquals(5, merged.getMaxToolCalls());
        assertEquals(60000L, merged.getMaxDurationMs());
        assertEquals(20000, merged.getMaxOutputChars());
    }

    @Test
    @DisplayName("merge null override 返回原配额")
    void merge_nullOverride_shouldReturnOriginal() {
        AgentQuota base = AgentQuota.builder().maxToolCalls(10).build();
        AgentQuota merged = base.merge(null);
        assertEquals(10, merged.getMaxToolCalls());
    }

    @Test
    @DisplayName("defaults 包含 8 种角色")
    void defaults_shouldContain8Roles() {
        Map<Agent.Role, AgentQuota> defaults = AgentQuota.defaults();
        assertEquals(8, defaults.size());
        for (Agent.Role role : Agent.Role.values()) {
            assertNotNull(defaults.get(role), "Missing default quota for " + role);
            assertNotNull(defaults.get(role).getMaxToolCalls());
            assertNotNull(defaults.get(role).getMaxDurationMs());
        }
    }

    @Test
    @DisplayName("fallback 提供宽松配额")
    void fallback_shouldProvideLooseQuota() {
        AgentQuota fallback = AgentQuota.fallback();
        assertTrue(fallback.getMaxToolCalls() >= 20);
        assertTrue(fallback.getMaxDurationMs() >= 120_000L);
        assertEquals(0, fallback.getDailyCallLimit());
    }

    @Test
    @DisplayName("AUDIT 角色配额最严格")
    void defaults_auditShouldBeStrictest() {
        Map<Agent.Role, AgentQuota> defaults = AgentQuota.defaults();
        AgentQuota audit = defaults.get(Agent.Role.AUDIT);
        AgentQuota planning = defaults.get(Agent.Role.PLANNING);
        assertTrue(audit.getMaxConcurrentExecutions() <= planning.getMaxConcurrentExecutions());
        assertTrue(audit.getMaxDurationMs() <= planning.getMaxDurationMs());
    }

    @Test
    @DisplayName("toBuilder 复制并修改")
    void toBuilder_shouldCopyAndModify() {
        AgentQuota base = AgentQuota.builder().maxToolCalls(10).maxDurationMs(60000L).build();
        AgentQuota modified = base.toBuilder().maxToolCalls(5).build();
        assertEquals(5, modified.getMaxToolCalls());
        assertEquals(60000L, modified.getMaxDurationMs());
    }
}