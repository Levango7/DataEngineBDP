package com.levango7.dataenginebdp.ruleengine.agent.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolSandbox 测试。
 */
class ToolSandboxTest {

    private ToolRegistry registry;
    private ToolSandbox sandbox;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        sandbox = new ToolSandbox(2, 5000L);
    }

    @AfterEach
    void tearDown() {
        sandbox.shutdown();
    }

    @Test
    @DisplayName("invoke 成功执行返回成功结果")
    void invoke_success() {
        registry.register(new Tool("echo", "echo", Tool.RiskLevel.SAFE,
                args -> args.get("msg")));
        ToolSandbox.ToolInvocation inv = sandbox.invoke(registry, "echo", Map.of("msg", "hello"));

        assertTrue(inv.success());
        assertEquals("hello", inv.result());
        assertNull(inv.errorCode());
        assertTrue(inv.durationMs() >= 0);
    }

    @Test
    @DisplayName("invoke 未注册工具返回 TOOL_NOT_FOUND")
    void invoke_unknownTool_shouldReturnNotFound() {
        ToolSandbox.ToolInvocation inv = sandbox.invoke(registry, "unknown", Map.of());
        assertFalse(inv.success());
        assertEquals("tool_not_found", inv.errorCode());
        assertTrue(inv.errorMessage().contains("not registered"));
    }

    @Test
    @DisplayName("invoke 工具抛异常返回 TOOL_ERROR")
    void invoke_toolThrows_shouldReturnError() {
        registry.register(new Tool("boom", "boom", Tool.RiskLevel.SAFE,
                args -> { throw new RuntimeException("kaboom"); }));
        ToolSandbox.ToolInvocation inv = sandbox.invoke(registry, "boom", Map.of());

        assertFalse(inv.success());
        assertEquals("TOOL_ERROR", inv.errorCode());
        assertTrue(inv.errorMessage().contains("kaboom"));
    }

    @Test
    @DisplayName("invoke 超时返回 TOOL_TIMEOUT")
    void invoke_timeout_shouldReturnTimeout() throws Exception {
        registry.register(new Tool("slow", "slow", Tool.RiskLevel.SAFE,
                args -> { Thread.sleep(5000); return "done"; }));
        ToolSandbox.ToolInvocation inv = sandbox.invoke(registry, "slow", Map.of(), 100L);

        assertFalse(inv.success());
        assertEquals("TOOL_TIMEOUT", inv.errorCode());
    }

    @Test
    @DisplayName("invoke null args 使用空 map")
    void invoke_nullArgs_shouldUseEmptyMap() {
        registry.register(new Tool("noop", "noop", Tool.RiskLevel.SAFE,
                args -> args.size()));
        ToolSandbox.ToolInvocation inv = sandbox.invoke(registry, "noop", null);

        assertTrue(inv.success());
        assertEquals(0, inv.result());
    }

    @Test
    @DisplayName("invoke 记录工具名和参数")
    void invoke_shouldRecordToolAndArgs() {
        registry.register(new Tool("echo", "echo", Tool.RiskLevel.SAFE,
                args -> "ok"));
        ToolSandbox.ToolInvocation inv = sandbox.invoke(registry, "echo", Map.of("k", "v"));

        assertEquals("echo", inv.toolName());
        assertEquals("v", inv.args().get("k"));
    }

    @Test
    @DisplayName("invoke 并发执行不阻塞")
    void invoke_concurrent_shouldNotBlock() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        registry.register(new Tool("count", "count", Tool.RiskLevel.SAFE,
                args -> { counter.incrementAndGet(); return counter.get(); }));

        ToolSandbox.ToolInvocation inv1 = sandbox.invoke(registry, "count", Map.of());
        ToolSandbox.ToolInvocation inv2 = sandbox.invoke(registry, "count", Map.of());

        assertTrue(inv1.success());
        assertTrue(inv2.success());
        assertEquals(2, counter.get());
    }
}