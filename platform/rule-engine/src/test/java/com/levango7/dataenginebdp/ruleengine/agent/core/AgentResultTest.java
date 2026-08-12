package com.levango7.dataenginebdp.ruleengine.agent.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentResult 测试。
 */
class AgentResultTest {

    @Test
    @DisplayName("success 构造成功结果")
    void success_shouldBuildSuccessResult() {
        Map<String, Object> output = Map.of("sql", "SELECT 1");
        AgentResult result = AgentResult.success(Agent.Role.SQL, output,
                List.of("sql-1"), List.of(Map.of("tool", "nl2sql")),
                100L, "tenant-1", "req-1");

        assertEquals(Agent.Role.SQL, result.getRole());
        assertEquals(AgentResult.Status.SUCCESS, result.getStatus());
        assertEquals("SELECT 1", result.getOutput().get("sql"));
        assertEquals(1, result.getArtifacts().size());
        assertEquals(100L, result.getDurationMs());
        assertTrue(result.isSuccess());
        assertNull(result.getErrorCode());
    }

    @Test
    @DisplayName("failure 构造失败结果")
    void failure_shouldBuildFailureResult() {
        AgentResult result = AgentResult.failure(Agent.Role.SQL,
                AgentResult.Status.QUOTA_EXCEEDED, "CONCURRENT_LIMIT_EXCEEDED",
                "too many", 50L, "tenant-1", "req-1");

        assertEquals(AgentResult.Status.QUOTA_EXCEEDED, result.getStatus());
        assertEquals("CONCURRENT_LIMIT_EXCEEDED", result.getErrorCode());
        assertEquals("too many", result.getErrorMessage());
        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().isEmpty());
        assertTrue(result.getArtifacts().isEmpty());
    }

    @Test
    @DisplayName("getOutput 返回不可变视图")
    void getOutput_shouldReturnUnmodifiable() {
        AgentResult result = AgentResult.success(Agent.Role.SQL,
                Map.of("k", "v"), null, null, null, null, null);
        assertThrows(UnsupportedOperationException.class, () -> result.getOutput().put("x", "y"));
    }

    @Test
    @DisplayName("success null output 转为空 map")
    void success_nullOutput_shouldBeEmptyMap() {
        AgentResult result = AgentResult.success(Agent.Role.SQL, null, null, null, null, null, null);
        assertNotNull(result.getOutput());
        assertTrue(result.getOutput().isEmpty());
    }

    @Test
    @DisplayName("toBuilder 保留并修改字段")
    void toBuilder_shouldPreserveAndModify() {
        AgentResult result = AgentResult.success(Agent.Role.SQL,
                Map.of("sql", "SELECT 1"), null, null, null, "t", "r");
        AgentResult modified = result.toBuilder().durationMs(200L).build();
        assertEquals(200L, modified.getDurationMs());
        assertEquals("SELECT 1", modified.getOutput().get("sql"));
    }

    @Test
    @DisplayName("Role 枚举包含 8 种角色")
    void role_shouldContain8Roles() {
        assertEquals(8, Agent.Role.values().length);
        assertNotNull(Agent.Role.valueOf("PLANNING"));
        assertNotNull(Agent.Role.valueOf("SQL"));
        assertNotNull(Agent.Role.valueOf("VISUALIZATION"));
        assertNotNull(Agent.Role.valueOf("QUALITY"));
        assertNotNull(Agent.Role.valueOf("LINEAGE"));
        assertNotNull(Agent.Role.valueOf("DOCUMENTATION"));
        assertNotNull(Agent.Role.valueOf("CODE"));
        assertNotNull(Agent.Role.valueOf("AUDIT"));
    }
}