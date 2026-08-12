package com.levango7.dataenginebdp.ruleengine.scheduler.elastic;

import com.levango7.dataenginebdp.ruleengine.scheduler.priority.PriorityTaskQueue;
import com.levango7.dataenginebdp.ruleengine.scheduler.service.SchedulerTask;
import lombok.extern.slf4j.Slf4j;

/**
 * Worker：单线程任务消费者。
 *
 * <p>循环从 {@link PriorityTaskQueue} 阻塞拉取任务，委托 {@link TaskHandler} 执行。
 * 设计为不退出循环：handler 异常被捕获并记录，避免线程死亡导致吞吐下降。
 * 仅在 {@link #stop()} 时通过中断标志退出。</p>
 *
 * <p>每个 worker 持有唯一 {@code workerId}，便于日志关联与 {@link WorkerPool} 管理。</p>
 */
@Slf4j
public class Worker implements Runnable {

    private final String workerId;
    private final PriorityTaskQueue queue;
    private final TaskHandler handler;
    private final long pollTimeoutMs;
    private final WorkerPool ownerPool;
    private volatile boolean running = true;

    /**
     * @param workerId      worker 标识
     * @param queue         任务队列
     * @param handler       任务处理器
     * @param pollTimeoutMs 拉取超时（毫秒），超时后空转一轮再重试，便于响应 stop
     * @param ownerPool     所属 worker 池，用于任务前后更新活跃计数
     */
    public Worker(String workerId, PriorityTaskQueue queue, TaskHandler handler,
                  long pollTimeoutMs, WorkerPool ownerPool) {
        this.workerId = workerId;
        this.queue = queue;
        this.handler = handler;
        this.pollTimeoutMs = pollTimeoutMs;
        this.ownerPool = ownerPool;
    }

    public String getWorkerId() {
        return workerId;
    }

    /** 停止 worker：拉取循环在下一次检查时退出 */
    public void stop() {
        this.running = false;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void run() {
        log.info("worker 启动: id={}", workerId);
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                SchedulerTask task;
                try {
                    task = queue.poll(pollTimeoutMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (task == null) {
                    continue;
                }
                executeTask(task);
            }
        } finally {
            log.info("worker 退出: id={}", workerId);
        }
    }

    private void executeTask(SchedulerTask task) {
        ownerPool.taskStarted();
        try {
            handler.handle(task);
        } catch (Throwable ex) {
            // handler 应自处理异常并置终态；此处兜底防止 worker 死亡
            log.error("worker 捕获未处理异常: workerId={}, taskId={}", workerId, task.getTaskId(), ex);
        } finally {
            ownerPool.taskFinished();
        }
    }
}