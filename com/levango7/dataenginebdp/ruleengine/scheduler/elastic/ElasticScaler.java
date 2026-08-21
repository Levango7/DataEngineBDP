package com.shuqing.bigdata.ruleengine.scheduler.elastic;

import com.shuqing.bigdata.ruleengine.scheduler.config.SchedulerProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 弹性伸缩器。
 *
 * <p>定时（{@code evalIntervalMs}）评估负载并调整 {@link WorkerPool} 大小：</p>
 * <ul>
 *   <li>扩容：{@code avgLoad > scaleUpThreshold} 且 {@code workerCount < maxSize} → 扩容 1</li>
 *   <li>缩容：{@code avgLoad < scaleDownThreshold} 且 {@code workerCount > minSize}
 *       且超过冷却期 → 缩容 1</li>
 * </ul>
 *
 * <p>冷却期（{@code cooldownMs}）：刚扩容后一段时间内不缩容，避免负载瞬时波动导致
 * 扩缩抖动（thrashing）。{@link LoadMonitor} 提供负载数据。</p>
 *
 * <p>调度线程使用守护线程单线程 {@link ScheduledExecutorService}，{@link #stop()}
 * 时关闭。禁用（{@code elastic.enabled=false}）时不启动评估循环，worker 池保持初始大小。</p>
 */
@Slf4j
public class ElasticScaler {

    private final WorkerPool workerPool;
    private final LoadMonitor loadMonitor;
    private final SchedulerProperties.Elastic config;
    private final ScheduledExecutorService scheduler;

    /** 上次扩容时间戳（毫秒），用于冷却期判断 */
    private final AtomicLong lastScaleUpTime = new AtomicLong(0L);

    private volatile boolean running = false;

    public ElasticScaler(WorkerPool workerPool, LoadMonitor loadMonitor,
                         SchedulerProperties.Elastic config) {
        this.workerPool = workerPool;
        this.loadMonitor = loadMonitor;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "elastic-scaler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动弹性伸缩评估循环。
     */
    public void start() {
        if (!config.isEnabled() || running) {
            log.info("弹性伸缩未启动: enabled={}, alreadyRunning={}", config.isEnabled(), running);
            return;
        }
        running = true;
        long intervalMs = Math.max(500L, config.getEvalIntervalMs());
        scheduler.scheduleAtFixedRate(this::evaluate, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("弹性伸缩已启动: evalInterval={}ms, scaleUp>{}, scaleDown<{}, cooldown={}ms",
                intervalMs, config.getScaleUpThreshold(), config.getScaleDownThreshold(), config.getCooldownMs());
    }

    /**
     * 停止弹性伸缩。
     */
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        log.info("弹性伸缩已停止");
    }

    /**
     * 单次评估并执行扩缩容（包可见，便于测试直接调用）。
     */
    void evaluate() {
        try {
            int currentSize = workerPool.size();
            double avgLoad = loadMonitor.avgLoad();
            int queueSize = loadMonitor.queueSize();
            int active = loadMonitor.activeTaskCount();

            if (shouldScaleUp(avgLoad, currentSize)) {
                int newSize = workerPool.scaleTo(currentSize + 1);
                lastScaleUpTime.set(System.currentTimeMillis());
                log.info("触发扩容: avgLoad={}.2, queue={}, active={}, {}→{}", avgLoad, queueSize, active, currentSize, newSize);
            } else if (shouldScaleDown(avgLoad, currentSize)) {
                int newSize = workerPool.scaleTo(currentSize - 1);
                log.info("触发缩容: avgLoad={}.2, queue={}, active={}, {}→{}", avgLoad, queueSize, active, currentSize, newSize);
            }
        } catch (Throwable ex) {
            log.error("弹性伸缩评估异常", ex);
        }
    }

    private boolean shouldScaleUp(double avgLoad, int currentSize) {
        return avgLoad > config.getScaleUpThreshold() && currentSize < workerPool.getMaxSize();
    }

    private boolean shouldScaleDown(double avgLoad, int currentSize) {
        if (avgLoad >= config.getScaleDownThreshold()) {
            return false;
        }
        if (currentSize <= workerPool.getMinSize()) {
            return false;
        }
        // 冷却期检查：距上次扩容不足 cooldown 则不缩
        long lastUp = lastScaleUpTime.get();
        if (lastUp > 0 && System.currentTimeMillis() - lastUp < config.getCooldownMs()) {
            return false;
        }
        return true;
    }

    public boolean isRunning() {
        return running;
    }
}