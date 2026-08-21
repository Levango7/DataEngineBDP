package com.shuqing.bigdata.ruleengine.agent.tool;

import com.shuqing.bigdata.ruleengine.agent.core.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolWhitelist 测试。
 */
class ToolWhitelistTest {

    private ToolWhitelist whitelist;

    @BeforeEach
    void setUp() {
        Map<Agent.Role, Set<String>> defaults = new EnumMap<>(Agent.Role.class);
        defaults.put(Agent.Role.SQL, Set.of("nl2sql", "query_metadata"));
        defaults.put(Agent.Role.AUDIT, Set.of("audit_sql"));
        whitelist = new ToolWhitelist(defaults);
    }

    @Test
    @DisplayName("checkAllowed 全部在白名单内返回 null")
    void checkAllowed_allInWhitelist_shouldReturnNull() {
        assertNull(whitelist.checkAllowed(Agent.Role.SQL, Set.of("nl2sql")));
        assertNull(whitelist.checkAllowed(Agent.Role.SQL, Set.of("nl2sql", "query_metadata")));
    }

    @Test
    @DisplayName("checkAllowed 存在不在白名单的工具返回该工具名")
    void checkAllowed_notInWhitelist_shouldReturnToolName() {
        String denied = whitelist.checkAllowed(Agent.Role.SQL, Set.of("nl2sql", "dangerous_tool"));
        assertEquals("dangerous_tool", denied);
    }

    @Test
    @DisplayName("checkAllowed null 或空 requestedTools 直接放行")
    void checkAllowed_nullOrEmpty_shouldPass() {
        assertNull(whitelist.checkAllowed(Agent.Role.SQL, null));
        assertNull(whitelist.checkAllowed(Agent.Role.SQL, Set.of()));
    }

    @Test
    @DisplayName("checkAllowed 未配置白名单的角色拒绝所有")
    void checkAllowed_unconfiguredRole_shouldDeny() {
        String denied = whitelist.checkAllowed(Agent.Role.PLANNING, Set.of("task_decompose"));
        assertEquals("task_decompose", denied);
    }

    @Test
    @DisplayName("isAllowed 判断工具是否允许")
    void isAllowed() {
        assertTrue(whitelist.isAllowed(Agent.Role.SQL, "nl2sql"));
        assertFalse(whitelist.isAllowed(Agent.Role.SQL, "dangerous"));
        assertFalse(whitelist.isAllowed(Agent.Role.PLANNING, "task_decompose"));
    }

    @Test
    @DisplayName("grant 动态授权后 isAllowed 返回 true")
    void grant_thenIsAllowed() {
        assertFalse(whitelist.isAllowed(Agent.Role.PLANNING, "task_decompose"));
        whitelist.grant(Agent.Role.PLANNING, "task_decompose");
        assertTrue(whitelist.isAllowed(Agent.Role.PLANNING, "task_decompose"));
    }

    @Test
    @DisplayName("revoke 动态回收后 isAllowed 返回 false")
    void revoke_thenIsNotAllowed() {
        assertTrue(whitelist.isAllowed(Agent.Role.SQL, "nl2sql"));
        assertTrue(whitelist.revoke(Agent.Role.SQL, "nl2sql"));
        assertFalse(whitelist.isAllowed(Agent.Role.SQL, "nl2sql"));
    }

    @Test
    @DisplayName("revoke 未授权工具返回 false")
    void revoke_notGranted_shouldReturnFalse() {
        assertFalse(whitelist.revoke(Agent.Role.SQL, "nonexistent"));
    }

    @Test
    @DisplayName("reset 重置白名单")
    void reset_shouldReplaceWhitelist() {
        whitelist.reset(Agent.Role.SQL, Set.of("new_tool"));
        assertTrue(whitelist.isAllowed(Agent.Role.SQL, "new_tool"));
        assertFalse(whitelist.isAllowed(Agent.Role.SQL, "nl2sql"));
    }

    @Test
    @DisplayName("allowlistOf 返回不可变集合")
    void allowlistOf_shouldReturnUnmodifiable() {
        Set<String> allowed = whitelist.allowlistOf(Agent.Role.SQL);
        assertThrows(UnsupportedOperationException.class, () -> allowed.add("x"));
    }

    @Test
    @DisplayName("allowlistOf 未配置角色返回空集合")
    void allowlistOf_unconfigured_shouldReturnEmpty() {
        assertTrue(whitelist.allowlistOf(Agent.Role.PLANNING).isEmpty());
    }

    @Test
    @DisplayName("snapshot 返回所有角色白名单")
    void snapshot_shouldReturnAllRoles() {
        Map<Agent.Role, Set<String>> snap = whitelist.snapshot();
        assertEquals(2, snap.size());
        assertTrue(snap.get(Agent.Role.SQL).contains("nl2sql"));
    }

    @Test
    @DisplayName("默认构造器创建空白名单")
    void defaultConstructor_shouldCreateEmpty() {
        ToolWhitelist empty = new ToolWhitelist();
        assertTrue(empty.allowlistOf(Agent.Role.SQL).isEmpty());
    }
}