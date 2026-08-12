package com.levango7.dataenginebdp.ruleengine.scheduler.elastic;

import com.levango7.dataenginebdp.ruleengine.scheduler.priority.PriorityTaskQueue;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker 线程池管理器。
 *
 * <p>维护一组 {@link Worker} 及其执行线程，提供启动/停止/弹性扩缩容能力。
 * 由 {@link ElasticScaler} 根据负载调用 {@link #scaleTo(int)} 动态调整大小。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>每个 worker 用独立 {@link Thread}（非线程池复用），便于按需启停单个 worker；
 *       worker 数量受 {@code maxSize} 限制</li>
 *   <li>{@code activeTaskCount} 由 worker 在任务前后调用 {@link #taskStarted()}/
 *       {@link #taskFinished()} 原子更新，供 {@link LoadMonitor} 计算利用率</li>
 *   <li>缩容时调用 {@link Worker#stop()} 通知 worker 优雅退出，不强制中断，
 *       避免任务执行被打断</li>
 * </ul>
 */
@Slf4j
public class WorkerPool {

    private final PriorityTaskQueue queue;
    private final TaskHandler handler;
    private final long pollTimeoutMs;
    private final int minSize;
    private final int maxSize;

    private final ConcurrentHashMap<String, Worker> workers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Thread> threads = new ConcurrentHashMap<>();
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    private final AtomicInteger workerSeq = new AtomicInteger(0);

    /**
     * @param queue         任务队列
     * @param handler       任务处理器
     * @param pollTimeoutMs worker 拉取超时
     * @param minSize       最小 worker 数
     * @param maxSize       最大 worker 数
     */
    public WorkerPool(PriorityTaskQueue queue, TaskHandler handler,
                      long pollTimeoutMs, int minSize, int maxSize) {
        this.queue = queue;
        this.handler = handler;
        this.pollTimeoutMs = pollTimeoutMs;
        this.minSize = Math.max(1, minSize);
        this.maxSize = Math.max(this.minSize, maxSize);
    }

    /**
     * 启动 worker 池，初始化到 {@code initialSize}（夹逼到 [minSize, maxSize]）。
     *
     * @param initialSize 初始大小
     */
    public synchronized void start(int initialSize) {
        int target = Math.min(maxSize, Math.max(minSize, initialSize));
        for (int i = 0; i < target; i++) {
            addWorker();
        }
        log.info("worker 池已启动: size={}, min={}, max={}", workers.size(), minSize, maxSize);
    }

    /**
     * 优雅停止所有 worker。
     */
    public synchronized void stop() {
        for (Worker w : workers.values()) {
            w.stop();
        }
        for (Thread t : threads.values()) {
            t.interrupt();
        }
        workers.clear();
        threads.clear();
        log.info("worker 池已停止");
    }

    /**
     * 扩缩容到指定大小（夹逼到 [minSize, maxSize]）。
     *
     * @param targetSize 目标大小
     * @return 实际调整后的大小
     */
    public synchronized int scaleTo(int targetSize) {
        int target = Math.min(maxSize, Math.max(minSize, targetSize));
        int current = workers.size();
        if (target > current) {
            int add = target - current;
            for (int i = 0; i < add; i++) {
                addWorker();
            }
            log.info("worker 池扩容: {} → {}", current, workers.size());
        } else if (target < current) {
            int remove = current - target;
            for (int i = 0; i < remove; i++) {
                removeWorker();
            }
            log.info("worker 池缩容: {} → {}", current, workers.size());
        }
        return workers.size();
    }

    /** 当前 worker 数量 */
    public int size() {
        return workers.size();
    }

    /** 当前正在执行任务的 worker 数（即活跃任务数，单 worker 单任务） */
    public int activeTaskCount() {
        return activeTaskCount.get();
    }

    /** worker 拉到任务时调用 */
    public void taskStarted() {
        activeTaskCount.incrementAndGet();
    }

    /** worker 完成任务时调用 */
    public void taskFinished() {
        activeTaskCount.decrementAndGet();
    }

    public int getMinSize() {
        return minSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

    private void addWorker() {
        String id = "worker-" + workerSeq.incrementAndGet();
        Worker worker = new Worker(id, queue, handler, pollTimeoutMs, this);
        Thread thread = new Thread(worker, "scheduler-" + id);
        thread.setDaemon(true);
        workers.put(id, worker);
        threads.put(id, thread);
        thread.start();
    }

    private void removeWorker() {
        if (workers.isEmpty()) {
            return;
        }
        // 移除任意一个 worker（迭代第一个）
        String id = workers.keys().nextElement();
        Worker w = workers.remove(id);
        Thread t = threads.remove(id);
        if (w != null) {
            w.stop();
        }
        if (t != null) {
            t.interrupt();
        }
    }
}