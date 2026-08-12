package com.levango7.dataenginebdp.ruleengine.agent.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentContext 测试。
 */
class AgentContextTest {

    @Test
    @DisplayName("builder 构造完整上下文")
    void builder_shouldBuildFullContext() {
        Map<String, Object> input = new HashMap<>();
        input.put("question", "查询用户数");
        AgentContext ctx = AgentContext.builder()
                .userInput("查询用户数")
                .tenantId("tenant-1")
                .userId("user-1")
                .input(input)
                .maxToolCalls(5)
                .maxDurationMs(30000L)
                .maxOutputChars(10000)
                .allowedTools(java.util.Set.of("nl2sql"))
                .traceId("trace-1")
                .requestId("req-1")
                .build();

        assertEquals("查询用户数", ctx.getUserInput());
        assertEquals("tenant-1", ctx.getTenantId());
        assertEquals("user-1", ctx.getUserId());
        assertEquals(5, ctx.getMaxToolCalls());
        assertEquals(30000L, ctx.getMaxDurationMs());
        assertEquals(10000, ctx.getMaxOutputChars());
        assertEquals("trace-1", ctx.getTraceId());
        assertEquals("req-1", ctx.getRequestId());
        assertEquals("查询用户数", ctx.getInput("question"));
    }

    @Test
    @DisplayName("getInput 返回不可变视图")
    void getInput_shouldReturnUnmodifiable() {
        AgentContext ctx = AgentContext.builder()
                .input(Map.of("k", "v"))
                .tenantId("t")
                .build();
        assertThrows(UnsupportedOperationException.class, () -> ctx.getInput().put("x", "y"));
    }

    @Test
    @DisplayName("空 input 时 getInput 返回空 map 而非 null")
    void getInput_nullInput_shouldReturnEmptyMap() {
        AgentContext ctx = AgentContext.builder().tenantId("t").build();
        assertNotNull(ctx.getInput());
        assertTrue(ctx.getInput().isEmpty());
    }

    @Test
    @DisplayName("getAttribute 类型匹配时返回值")
    void getAttribute_typeMatch_shouldReturnValue() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t")
                .attributes(Map.of("dialect", "postgres", "depth", 3))
                .build();
        assertEquals("postgres", ctx.getAttribute("dialect", String.class));
        assertEquals(3, ctx.getAttribute("depth", Integer.class));
    }

    @Test
    @DisplayName("getAttribute 类型不匹配时返回 null")
    void getAttribute_typeMismatch_shouldReturnNull() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t")
                .attributes(Map.of("dialect", "postgres"))
                .build();
        assertNull(ctx.getAttribute("dialect", Integer.class));
    }

    @Test
    @DisplayName("minimal 构造器预填空输入")
    void minimal_shouldPrefillEmptyInput() {
        AgentContext ctx = AgentContext.minimal("t", "u", "hello").build();
        assertEquals("t", ctx.getTenantId());
        assertEquals("u", ctx.getUserId());
        assertEquals("hello", ctx.getUserInput());
        assertNotNull(ctx.getInput());
        assertTrue(ctx.getInput().isEmpty());
    }

    @Test
    @DisplayName("toBuilder 复制并修改")
    void toBuilder_shouldCopyAndModify() {
        AgentContext ctx = AgentContext.builder()
                .tenantId("t")
                .userInput("hello")
                .build();
        AgentContext modified = ctx.toBuilder().userInput("world").build();
        assertEquals("t", modified.getTenantId());
        assertEquals("world", modified.getUserInput());
    }
}