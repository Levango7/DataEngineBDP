package com.levango7.dataenginebdp.ruleengine.agent.quota;

import com.levango7.dataenginebdp.ruleengine.agent.core.Agent;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentContext;
import com.levango7.dataenginebdp.ruleengine.agent.core.AgentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QuotaEnforcer 测试。
 */
class QuotaEnforcerTest {

    private QuotaEnforcer enforcer;

    @BeforeEach
    void setUp() {
        enforcer = new QuotaEnforcer();
    }

    @Test
    @DisplayName("resolveQuota context 覆盖角色默认")
    void resolveQuota_contextOverridesDefault() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t1")
                .maxToolCalls(3)
                .build();
        AgentQuota quota = enforcer.resolveQuota(Agent.Role.SQL, ctx);
        assertEquals(3, quota.getMaxToolCalls());
    }

    @Test
    @DisplayName("resolveQuota 无 context 返回角色默认")
    void resolveQuota_noContext_shouldReturnDefault() {
        AgentQuota quota = enforcer.resolveQuota(Agent.Role.SQL, null);
        AgentQuota defaultQuota = enforcer.defaultQuotaOf(Agent.Role.SQL);
        assertEquals(defaultQuota.getMaxToolCalls(), quota.getMaxToolCalls());
    }

    @Test
    @DisplayName("checkAndAcquire 首次通过返回 null")
    void checkAndAcquire_firstCall_shouldPass() {
        AgentQuota quota = enforcer.defaultQuotaOf(Agent.Role.SQL);
        AgentResult result = enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t1");
        assertNull(result);
        assertEquals(1, enforcer.currentConcurrent(Agent.Role.SQL, "t1"));
    }

    @Test
    @DisplayName("checkAndAcquire 并发超限返回 QUOTA_EXCEEDED")
    void checkAndAcquire_concurrentExceeded() {
        AgentQuota quota = AgentQuota.builder()
                .maxConcurrentExecutions(2).dailyCallLimit(0).build();
        enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t1");
        enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t1");
        AgentResult result = enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t1");

        assertNotNull(result);
        assertEquals(AgentResult.Status.QUOTA_EXCEEDED, result.getStatus());
        assertEquals("CONCURRENT_LIMIT_EXCEEDED", result.getErrorCode());
    }

    @Test
    @DisplayName("checkAndAcquire 日调用超限返回 QUOTA_EXCEEDED")
    void checkAndAcquire_dailyExceeded() {
        AgentQuota quota = AgentQuota.builder()
                .maxConcurrentExecutions(100).dailyCallLimit(2).build();
        enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t1");
        enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t1");
        enforcer.release(Agent.Role.SQL, quota, "t1");
        enforcer.release(Agent.Role.SQL, quota, "t1");
        AgentResult result = enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t1");

        assertNotNull(result);
        assertEquals(AgentResult.Status.QUOTA_EXCEEDED, result.getStatus());
        assertEquals("DAILY_LIMIT_EXCEEDED", result.getErrorCode());
    }

    @Test
    @DisplayName("release 并发计数减一")
    void release_shouldDecrementConcurrent() {
        AgentQuota quota = enforcer.defaultQuotaOf(Agent.Role.SQL);
        enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t1");
        assertEquals(1, enforcer.currentConcurrent(Agent.Role.SQL, "t1"));

        enforcer.release(Agent.Role.SQL, quota, "t1");
        assertEquals(0, enforcer.currentConcurrent(Agent.Role.SQL, "t1"));
    }

    @Test
    @DisplayName("release 多于 acquire 不会变负")
    void release_moreThanAcquire_shouldNotGoNegative() {
        AgentQuota quota = enforcer.defaultQuotaOf(Agent.Role.SQL);
        enforcer.release(Agent.Role.SQL, quota, "t1");
        assertEquals(0, enforcer.currentConcurrent(Agent.Role.SQL, "t1"));
    }

    @Test
    @DisplayName("release null tenantId 不操作")
    void release_nullTenant_shouldNoop() {
        AgentQuota quota = enforcer.defaultQuotaOf(Agent.Role.SQL);
        enforcer.release(Agent.Role.SQL, quota, null);
        enforcer.release(Agent.Role.SQL, quota, "");
    }

    @Test
    @DisplayName("不同租户配额独立")
    void differentTenants_shouldBeIndependent() {
        AgentQuota quota = AgentQuota.builder()
                .maxConcurrentExecutions(1).dailyCallLimit(0).build();
        assertNull(enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t1"));
        assertNull(enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t2"));
        assertNotNull(enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t1"));
    }

    @Test
    @DisplayName("updateDefaultQuota 更新后 defaultQuotaOf 返回新值")
    void updateDefaultQuota_shouldReflect() {
        AgentQuota newQuota = AgentQuota.builder()
                .maxToolCalls(99).maxDurationMs(999L).maxOutputChars(999)
                .maxConcurrentExecutions(99).dailyCallLimit(99).build();
        enforcer.updateDefaultQuota(Agent.Role.SQL, newQuota);
        assertEquals(99, enforcer.defaultQuotaOf(Agent.Role.SQL).getMaxToolCalls());
    }

    @Test
    @DisplayName("resetConcurrent 清零并发计数")
    void resetConcurrent_shouldZeroCounter() {
        AgentQuota quota = enforcer.defaultQuotaOf(Agent.Role.SQL);
        enforcer.checkAndAcquire(Agent.Role.SQL, quota, "t1");
        assertEquals(1, enforcer.currentConcurrent(Agent.Role.SQL, "t1"));

        enforcer.resetConcurrent(Agent.Role.SQL, "t1");
        assertEquals(0, enforcer.currentConcurrent(Agent.Role.SQL, "t1"));
    }
}