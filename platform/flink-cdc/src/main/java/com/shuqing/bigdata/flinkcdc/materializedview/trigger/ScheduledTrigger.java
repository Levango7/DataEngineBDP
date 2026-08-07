package com.shuqing.bigdata.flinkcdc.materializedview.trigger;

import com.shuqing.bigdata.flinkcdc.materializedview.model.MaterializedViewDef;
import com.shuqing.bigdata.flinkcdc.materializedview.model.RefreshPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 定时触发器：按固定周期对 SCHEDULED 模式的物化视图触发刷新。
 *
 * <p>使用 {@link ScheduledExecutorService} 为每个定时刷新的物化视图
 * 调度独立周期任务，互不干扰。所有任务共享一个线程池，
 * 池大小等于定时视图数量（最少 1）。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * ScheduledTrigger trigger = new ScheduledTrigger(viewDefs);
 * trigger.registerHandler(refreshEvent -> viewRefresher.refresh(refreshEvent));
 * trigger.start();  // 启动所有定时任务
 * // ...
 * trigger.stop();   // 停止所有定时任务
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public class ScheduledTrigger implements RefreshTrigger {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTrigger.class);

    /** 注册的物化视图定义列表。 */
    private final List<MaterializedViewDef> viewDefs;

    /** 物化视图名称 → 刷新周期。 */
    private final Map<String, Duration> viewIntervals = new HashMap<>();

    /** 调度线程池。 */
    private ScheduledExecutorService scheduler;

    /** 物化视图名称 → 调度任务 Future。 */
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new HashMap<>();

    /** 事件处理器。 */
    private volatile Consumer<RefreshEvent> handler;

    /** 运行状态标志。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造器。
     *
     * @param viewDefs 物化视图定义列表（仅 SCHEDULED 模式的视图会被调度）
     */
    public ScheduledTrigger(List<MaterializedViewDef> viewDefs) {
        Objects.requireNonNull(viewDefs, "物化视图定义列表不能为 null");
        this.viewDefs = viewDefs;
        initialize();
    }

    /**
     * 初始化定时刷新视图映射。
     */
    private void initialize() {
        for (MaterializedViewDef def : viewDefs) {
            if (!def.isEnabled()) {
                continue;
            }
            RefreshPolicy policy = def.getRefreshPolicy();
            if (policy == null || policy.getMode() != RefreshPolicy.Mode.SCHEDULED) {
                continue;
            }
            viewIntervals.put(def.getName(), policy.getInterval());
            log.info("定时触发器注册物化视图: {}，周期: {}", def.getName(), policy.getInterval());
        }
    }

    @Override
    public void registerHandler(Consumer<RefreshEvent> handler) {
        this.handler = Objects.requireNonNull(handler, "事件处理器不能为 null");
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (viewIntervals.isEmpty()) {
            log.info("无 SCHEDULED 模式物化视图，定时触发器空启动");
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mv-scheduled-trigger");
                t.setDaemon(true);
                return t;
            });
            return;
        }
        int poolSize = Math.max(1, viewIntervals.size());
        scheduler = Executors.newScheduledThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "mv-scheduled-trigger");
            t.setDaemon(true);
            return t;
        });
        for (Map.Entry<String, Duration> entry : viewIntervals.entrySet()) {
            String viewName = entry.getKey();
            Duration interval = entry.getValue();
            long initialDelayMs = interval.toMillis();
            long periodMs = interval.toMillis();
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                    () -> fireRefresh(viewName),
                    initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
            scheduledTasks.put(viewName, future);
            log.info("启动定时刷新任务: {}，初始延迟 {} ms，周期 {} ms", viewName, initialDelayMs, periodMs);
        }
        log.info("定时触发器已启动，调度 {} 个物化视图", viewIntervals.size());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        // 取消所有调度任务
        for (Map.Entry<String, ScheduledFuture<?>> entry : scheduledTasks.entrySet()) {
            entry.getValue().cancel(false);
        }
        scheduledTasks.clear();
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("定时触发器已停止");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 触发一次定时刷新事件。
     *
     * @param viewName 物化视图名称
     */
    private void fireRefresh(String viewName) {
        if (handler == null) {
            log.warn("物化视图 {} 定时触发但未注册处理器", viewName);
            return;
        }
        RefreshEvent event = RefreshEvent.scheduled(viewName);
        log.info("定时触发物化视图刷新: {}", viewName);
        try {
            handler.accept(event);
        } catch (Exception e) {
            log.error("处理定时刷新事件失败: view={}", viewName, e);
        }
    }

    /**
     * 手动触发一次指定物化视图的定时刷新（用于测试与运维）。
     *
     * @param viewName 物化视图名称
     */
    public void triggerNow(String viewName) {
        if (!viewIntervals.containsKey(viewName)) {
            throw new IllegalArgumentException("物化视图 " + viewName + " 未注册为 SCHEDULED 模式");
        }
        fireRefresh(viewName);
    }

    /**
     * 获取已注册的定时刷新视图名称列表（只读，供测试使用）。
     *
     * @return 视图名称列表
     */
    public List<String> getScheduledViewNames() {
        return new ArrayList<>(viewIntervals.keySet());
    }

    /**
     * 获取指定视图的刷新周期（供测试使用）。
     *
     * @param viewName 视图名称
     * @return 刷新周期；若未注册返回 null
     */
    public Duration getInterval(String viewName) {
        return viewIntervals.get(viewName);
    }
}