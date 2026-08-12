package com.levango7.dataenginebdp.ruleengine.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 测试。
 */
class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    @Test
    @DisplayName("register 后 find 能找到")
    void register_thenFind() {
        Tool tool = new Tool("nl2sql", "NL2SQL", Tool.RiskLevel.SAFE, args -> "ok");
        registry.register(tool);

        assertTrue(registry.contains("nl2sql"));
        assertEquals(tool, registry.find("nl2sql").orElseThrow());
    }

    @Test
    @DisplayName("find 未注册工具返回 empty")
    void find_unregistered_shouldReturnEmpty() {
        assertTrue(registry.find("unknown").isEmpty());
    }

    @Test
    @DisplayName("register 同名工具覆盖")
    void register_duplicate_shouldOverride() {
        Tool t1 = new Tool("t", "v1", Tool.RiskLevel.SAFE, args -> "1");
        Tool t2 = new Tool("t", "v2", Tool.RiskLevel.MUTATING, args -> "2");
        registry.register(t1);
        registry.register(t2);

        Tool found = registry.find("t").orElseThrow();
        assertEquals("v2", found.description());
        assertEquals(Tool.RiskLevel.MUTATING, found.riskLevel());
    }

    @Test
    @DisplayName("registerAll 批量注册")
    void registerAll_shouldRegisterAll() {
        Tool t1 = new Tool("t1", "d1", Tool.RiskLevel.SAFE, args -> "1");
        Tool t2 = new Tool("t2", "d2", Tool.RiskLevel.SAFE, args -> "2");
        registry.registerAll(java.util.List.of(t1, t2));

        assertEquals(2, registry.names().size());
        assertTrue(registry.contains("t1"));
        assertTrue(registry.contains("t2"));
    }

    @Test
    @DisplayName("names 返回不可变集合")
    void names_shouldReturnUnmodifiable() {
        registry.register(new Tool("t", "d", Tool.RiskLevel.SAFE, args -> "x"));
        assertThrows(UnsupportedOperationException.class, () -> registry.names().add("y"));
    }

    @Test
    @DisplayName("unregister 移除并返回工具")
    void unregister_shouldRemoveAndReturn() {
        Tool tool = new Tool("t", "d", Tool.RiskLevel.SAFE, args -> "x");
        registry.register(tool);

        Tool removed = registry.unregister("t");
        assertEquals(tool, removed);
        assertFalse(registry.contains("t"));
    }

    @Test
    @DisplayName("unregister 未注册工具返回 null")
    void unregister_unknown_shouldReturnNull() {
        assertNull(registry.unregister("unknown"));
    }

    @Test
    @DisplayName("clear 清空所有")
    void clear_shouldRemoveAll() {
        registry.register(new Tool("t1", "d", Tool.RiskLevel.SAFE, args -> "x"));
        registry.register(new Tool("t2", "d", Tool.RiskLevel.SAFE, args -> "x"));
        registry.clear();

        assertTrue(registry.names().isEmpty());
    }

    @Test
    @DisplayName("all 返回不可变 map")
    void all_shouldReturnUnmodifiable() {
        registry.register(new Tool("t", "d", Tool.RiskLevel.SAFE, args -> "x"));
        assertThrows(UnsupportedOperationException.class, () -> registry.all().put("y", null));
    }

    @Test
    @DisplayName("Tool record 字段校验")
    void tool_validation() {
        assertThrows(NullPointerException.class, () ->
                new Tool(null, "d", Tool.RiskLevel.SAFE, args -> "x"));
        assertThrows(IllegalArgumentException.class, () ->
                new Tool("  ", "d", Tool.RiskLevel.SAFE, args -> "x"));
        assertThrows(NullPointerException.class, () ->
                new Tool("t", "d", null, args -> "x"));
    }
}