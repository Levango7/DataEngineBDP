package com.shuqing.bigdata.ruleengine.scheduler.elastic;

import com.shuqing.bigdata.ruleengine.scheduler.priority.PriorityTaskQueue;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 负载监控器。
 *
 * <p>采集调度引擎实时负载指标，供 {@link ElasticScaler} 做扩缩决策：</p>
 * <ul>
 *   <li>{@code queueSize}：当前排队任务数（来自 {@link PriorityTaskQueue#size()}）</li>
 *   <li>{@code workerCount}：当前 worker 总数（来自 {@link WorkerPool#size()}）</li>
 *   <li>{@code activeTaskCount}：正在执行的任务数（来自 {@link WorkerPool#activeTaskCount()}）</li>
 *   <li>{@code avgLoad}：平均负载 = queueSize / workerCount，衡量每个 worker 待处理任务数</li>
 *   <li>{@code utilization}：利用率 = activeTaskCount / workerCount，衡量 worker 繁忙程度</li>
 * </ul>
 *
 * <p>同时累计 {@code totalCompletedTasks}，用于吞吐量统计。</p>
 */
@Component
public class LoadMonitor {

    private final PriorityTaskQueue queue;
    private final AtomicLong totalCompletedTasks = new AtomicLong(0);
    private final AtomicLong totalRejectedTasks = new AtomicLong(0);

    private volatile WorkerPool workerPool;

    public LoadMonitor(PriorityTaskQueue queue) {
        this.queue = queue;
    }

    /**
     * 绑定 worker 池（启动后调用）。
     *
     * @param workerPool worker 池
     */
    public void bind(WorkerPool workerPool) {
        this.workerPool = workerPool;
    }

    /** 当前队列长度 */
    public int queueSize() {
        return queue.size();
    }

    /** 当前 worker 数量 */
    public int workerCount() {
        return workerPool == null ? 0 : workerPool.size();
    }

    /** 当前活跃任务数 */
    public int activeTaskCount() {
        return workerPool == null ? 0 : workerPool.activeTaskCount();
    }

    /**
     * 平均负载 = queueSize / workerCount。
     *
     * @return 负载值；worker 数为 0 返回 0
     */
    public double avgLoad() {
        int wc = workerCount();
        if (wc == 0) {
            return 0.0;
        }
        return (double) queueSize() / wc;
    }

    /**
     * worker 利用率 = activeTaskCount / workerCount。
     *
     * @return 利用率 [0,1]；worker 数为 0 返回 0
     */
    public double utilization() {
        int wc = workerCount();
        if (wc == 0) {
            return 0.0;
        }
        return (double) activeTaskCount() / wc;
    }

    /** 累计完成任务数 */
    public long totalCompletedTasks() {
        return totalCompletedTasks.get();
    }

    /** 累计拒绝任务数 */
    public long totalRejectedTasks() {
        return totalRejectedTasks.get();
    }

    /** 任务完成时调用 */
    public void recordCompleted() {
        totalCompletedTasks.incrementAndGet();
    }

    /** 任务被拒绝时调用 */
    public void recordRejected() {
        totalRejectedTasks.incrementAndGet();
    }
}