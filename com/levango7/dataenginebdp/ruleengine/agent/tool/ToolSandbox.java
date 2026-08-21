package com.shuqing.bigdata.ruleengine.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具调用沙箱。
 *
 * <p>在隔离线程池中执行工具调用，提供：
 * <ul>
 *   <li><b>超时控制</b>：通过 {@link Future#get(long, TimeUnit)} 强制中断超时调用</li>
 *   <li><b>异常隔离</b>：捕获工具抛出的所有异常，包装为 {@link ToolExecutionException}</li>
 *   <li><b>调用记录</b>：返回 {@link ToolInvocation} 记录工具名、参数、结果、耗时、状态</li>
 *   <li><b>线程隔离</b>：使用固定大小线程池，避免工具调用阻塞 Agent 主流程</li>
 * </ul>
 *
 * <p>沙箱不直接校验白名单（由 {@link ToolWhitelist} 在 Agent 执行前完成），
 * 也不校验配额（由 {@link com.shuqing.bigdata.ruleengine.agent.quota.QuotaEnforcer} 完成），
 * 仅聚焦"安全执行"职责。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class ToolSandbox {

    private static final Logger log = LoggerFactory.getLogger(ToolSandbox.class);

    /** 隔离执行线程池 */
    private final ExecutorService executor;

    /** 默认调用超时（毫秒） */
    private final long defaultTimeoutMs;

    /**
     * 使用默认配置构造：4 线程、30 秒超时。
     */
    public ToolSandbox() {
        this(4, 30_000L);
    }

    /**
     * 自定义构造。
     *
     * @param poolSize         线程池大小
     * @param defaultTimeoutMs 默认超时（毫秒）
     */
    public ToolSandbox(int poolSize, long defaultTimeoutMs) {
        this.executor = Executors.newFixedThreadPool(Math.max(1, poolSize), r -> {
            Thread t = new Thread(r, "tool-sandbox");
            t.setDaemon(true);
            return t;
        });
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /**
     * 在沙箱内执行工具调用。
     *
     * @param registry  工具注册中心
     * @param toolName  工具名
     * @param args      调用参数
     * @param timeoutMs 超时（毫秒）；{@code null} 使用默认
     * @return 调用记录（含结果或异常）
     */
    public ToolInvocation invoke(ToolRegistry registry, String toolName,
                                 Map<String, Object> args, Long timeoutMs) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        long timeout = timeoutMs == null ? defaultTimeoutMs : timeoutMs;
        long start = System.nanoTime();

        Tool tool = registry.find(toolName).orElse(null);
        if (tool == null) {
            return ToolInvocation.failure(toolName, args, "TOOL_NOT_FOUND",
                    "Tool '" + toolName + "' not registered", elapsedMs(start));
        }

        try {
            Future<Object> future = executor.submit((Callable<Object>) () ->
                    tool.executor().execute(args == null ? Map.of() : args));
            Object result = future.get(timeout, TimeUnit.MILLISECONDS);
            return ToolInvocation.success(toolName, args, result, elapsedMs(start));
        } catch (TimeoutException e) {
            log.warn("Tool '{}' invocation timed out after {}ms", toolName, timeout);
            return ToolInvocation.failure(toolName, args, "TOOL_TIMEOUT",
                    "Tool '" + toolName + "' timed out after " + timeout + "ms", elapsedMs(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolInvocation.failure(toolName, args, "INTERRUPTED",
                    "Tool '" + toolName + "' invocation interrupted", elapsedMs(start));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("Tool '{}' failed: {}", toolName, cause.toString());
            return ToolInvocation.failure(toolName, args, "TOOL_ERROR",
                    cause.getClass().getSimpleName() + ": " + cause.getMessage(), elapsedMs(start));
        } catch (Exception e) {
            log.warn("Tool '{}' dispatch failed: {}", toolName, e.toString());
            return ToolInvocation.failure(toolName, args, "DISPATCH_ERROR",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), elapsedMs(start));
        }
    }

    /**
     * 使用默认超时执行。
     *
     * @param registry 工具注册中心
     * @param toolName 工具名
     * @param args     调用参数
     * @return 调用记录
     */
    public ToolInvocation invoke(ToolRegistry registry, String toolName, Map<String, Object> args) {
        return invoke(registry, toolName, args, null);
    }

    /**
     * 关闭沙箱线程池。
     */
    public void shutdown() {
        executor.shutdownNow();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 工具调用记录。
     *
     * @param toolName    工具名
     * @param args        调用参数
     * @param result      结果（成功时）
     * @param errorCode   错误码（失败时）
     * @param errorMessage 错误消息（失败时）
     * @param durationMs  耗时
     * @param success     是否成功
     */
    public record ToolInvocation(
            String toolName,
            Map<String, Object> args,
            Object result,
            String errorCode,
            String errorMessage,
            long durationMs,
            boolean success) {

        public static ToolInvocation success(String toolName, Map<String, Object> args,
                                             Object result, long durationMs) {
            return new ToolInvocation(toolName, copyArgs(args), result, null, null, durationMs, true);
        }

        public static ToolInvocation failure(String toolName, Map<String, Object> args,
                                             String errorCode, String errorMessage, long durationMs) {
            return new ToolInvocation(toolName, copyArgs(args), null, errorCode, errorMessage, durationMs, false);
        }

        private static Map<String, Object> copyArgs(Map<String, Object> args) {
            return args == null ? Map.of() : new LinkedHashMap<>(args);
        }
    }

    /**
     * 工具执行异常。
     */
    public static class ToolExecutionException extends RuntimeException {
        public ToolExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}