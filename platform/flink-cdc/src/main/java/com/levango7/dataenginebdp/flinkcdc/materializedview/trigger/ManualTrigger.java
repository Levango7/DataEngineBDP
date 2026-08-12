package com.levango7.dataenginebdp.flinkcdc.materializedview.trigger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 手动触发器：通过 REST API 或运维接口显式触发物化视图刷新。
 *
 * <p>与 {@link CdcChangeTrigger} 和 {@link ScheduledTrigger} 不同，
 * 手动触发器不主动监听任何事件源，仅在被显式调用 {@link #trigger(String, String)}
 * 时产生刷新事件。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * ManualTrigger trigger = new ManualTrigger();
 * trigger.registerHandler(refreshEvent -> viewRefresher.refresh(refreshEvent));
 * trigger.start();
 * // 由 REST Controller 调用
 * trigger.trigger("mv_order_summary", "admin");
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public class ManualTrigger implements RefreshTrigger {

    private static final Logger log = LoggerFactory.getLogger(ManualTrigger.class);

    /** 事件处理器。 */
    private volatile Consumer<RefreshEvent> handler;

    /** 运行状态标志。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 累计触发次数（供监控使用）。 */
    private final java.util.concurrent.atomic.AtomicLong triggerCount =
            new java.util.concurrent.atomic.AtomicLong(0);

    @Override
    public void registerHandler(Consumer<RefreshEvent> handler) {
        this.handler = Objects.requireNonNull(handler, "事件处理器不能为 null");
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("手动触发器已启动");
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("手动触发器已停止");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 手动触发指定物化视图的刷新。
     *
     * @param viewName 物化视图名称
     * @param operator 操作人（用于审计日志）
     * @return 生成的刷新事件；若触发器未启动或未注册处理器返回 null
     * @throws NullPointerException 若 viewName 为 null
     */
    public RefreshEvent trigger(String viewName, String operator) {
        Objects.requireNonNull(viewName, "物化视图名称不能为 null");
        if (!running.get()) {
            log.warn("手动触发器未启动，忽略触发请求: view={}", viewName);
            return null;
        }
        if (handler == null) {
            log.warn("手动触发器未注册处理器，忽略触发请求: view={}", viewName);
            return null;
        }
        String op = operator == null ? "unknown" : operator;
        RefreshEvent event = RefreshEvent.manual(viewName, op);
        triggerCount.incrementAndGet();
        log.info("手动触发物化视图刷新: {}，操作人: {}", viewName, op);
        try {
            handler.accept(event);
            return event;
        } catch (Exception e) {
            log.error("处理手动刷新事件失败: view={}", viewName, e);
            return null;
        }
    }

    /**
     * 获取累计触发次数（供监控使用）。
     *
     * @return 累计触发次数
     */
    public long getTriggerCount() {
        return triggerCount.get();
    }
}