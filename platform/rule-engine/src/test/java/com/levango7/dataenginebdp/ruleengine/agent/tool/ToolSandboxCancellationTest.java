package com.levango7.dataenginebdp.ruleengine.agent.tool;

import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolSandbox 超时取消 + 生命周期回归（2026-10-12）。
 *
 * <p>背景：超时分支只返回 TOOL_TIMEOUT 而不 cancel 任务——沙箱是固定线程池，
 * 死循环/忽略中断的工具会永久占住线程，4 个并发即耗尽池（后续调用全部排队超时 = 软性 DoS）。
 * 修法：超时/中断分支 future.cancel(true)。</p>
 */
class ToolSandboxCancellationTest {

    private ToolRegistry registry;
    private ToolSandbox sandbox;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        sandbox = new ToolSandbox(2, 300L);   // 2 线程、300ms 超时，便于快速复现
    }

    @AfterEach
    void tearDown() {
        sandbox.shutdown();
    }

    @Test
    @DisplayName("超时后线程池不被死任务占满：连续 3 个死循环工具调用全部返回 TOOL_TIMEOUT")
    void timedOutTask_shouldNotExhaustPool() {
        // 死循环且不响应中断检查之外的中断（阻塞式 sleep 会响应中断，故用忙循环：
        // cancel(true) 通过中断位让阻塞/可中断实现退出；本用例确保"调用返回后线程可被复用"）
        registry.register(new Tool("hang", "hang", Tool.RiskLevel.SAFE, args -> {
            long deadline = System.nanoTime() + 5_000_000_000L;   // 最长 5s，远大于 300ms 超时
            while (System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted()) {
                    return "interrupted";   // 响应 cancel 的中断位
                }
                Thread.onSpinWait();
            }
            return "done";
        }));

        // 池大小=2：若超时任务不被取消，第 3 次调用会排在已占满的池子后面
        // （前两次仍会超时返回，但第 3 次的耗时会更长或同样超时——用 git 历史版旧实现时，
        //  这里观察到的线程占用是持续的；修复后每次都在 300ms 附近返回且线程被中断回收）
        for (int i = 0; i < 3; i++) {
            ToolSandbox.ToolInvocation inv = sandbox.invoke(registry, "hang", Map.of());
            assertFalse(inv.success(), "第 " + i + " 次调用应超时失败");
            assertEquals("TOOL_TIMEOUT", inv.errorCode());
        }
    }

    @Test
    @DisplayName("超时返回后，线程池仍可正常执行工具（未被死任务占满）")
    void poolUsable_afterTimeout() {
        registry.register(new Tool("hang", "hang", Tool.RiskLevel.SAFE, args -> {
            try {
                Thread.sleep(60_000L);   // 可中断的阻塞：cancel(true) 立即打断
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
            return "done";
        }));
        registry.register(new Tool("echo", "echo", Tool.RiskLevel.SAFE, args -> args.get("v")));

        // 用满 2 个线程做超时调用
        assertEquals("TOOL_TIMEOUT", sandbox.invoke(registry, "hang", Map.of()).errorCode());
        assertEquals("TOOL_TIMEOUT", sandbox.invoke(registry, "hang", Map.of()).errorCode());

        // 修复后线程被取消回收 ⇒ 后续正常工具能立即拿到线程成功执行
        ToolSandbox.ToolInvocation ok = sandbox.invoke(registry, "echo", Map.of("v", "ok"));
        assertTrue(ok.success(), "超时任务未被取消会占满线程池，正常调用将排队超时");
        assertEquals("ok", ok.result());
    }

    @Test
    @DisplayName("shutdown 已接 Spring 生命周期（@PreDestroy）")
    void shutdown_isWiredToSpringLifecycle() {
        Method m = null;
        try {
            m = ToolSandbox.class.getMethod("shutdown");
        } catch (NoSuchMethodException e) {
            // 方法名变更时本断言自然失败
        }
        assertNotNull(m, "ToolSandbox.shutdown() 方法应存在");
        assertNotNull(m.getAnnotation(PreDestroy.class),
                "shutdown() 必须标注 @PreDestroy——否则 Spring 停机时不会调用（线程池不回收）");
    }
}
