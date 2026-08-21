package com.shuqing.bigdata.ruleengine.scheduler.priority;

import com.shuqing.bigdata.ruleengine.scheduler.service.SchedulerTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * 优先级任务队列。
 *
 * <p>线程安全的优先级队列，按以下键稳定排序出队：</p>
 * <ol>
 *   <li>{@link TaskPriority#weight()} 降序（高优先级先出）</li>
 *   <li>{@code createdAt} 升序（同优先级 FIFO，避免饥饿）</li>
 *   <li>{@code taskId} 升序（最终 tie-breaker，保证全序稳定）</li>
 * </ol>
 *
 * <p>命名说明：JDK 已有 {@link java.util.PriorityQueue}，本类命名为
 * {@code PriorityTaskQueue} 以消除 import 歧义并强调"任务"语义，
 * 对应需求中的"PriorityQueue"组件。</p>
 *
 * <p>线程安全策略：内部委托 {@link PriorityQueue}（非线程安全），
 * 所有读写通过 {@link ReentrantLock} 串行化。{@code poll} 提供带超时的等待，
 * 由 {@link java.util.concurrent.locks.Condition} 实现，供 worker 阻塞拉取。</p>
 */
public class PriorityTaskQueue {

    /** 排序器：权重降序 → 创建时间升序 → taskId 升序 */
    private static final Comparator<SchedulerTask> TASK_COMPARATOR = Comparator
            .comparingInt((SchedulerTask t) -> t.getPriority().weight()).reversed()
            .thenComparing(SchedulerTask::getCreatedAt)
            .thenComparing(SchedulerTask::getTaskId);

    private final PriorityQueue<SchedulerTask> queue;
    private final ReentrantLock lock = new ReentrantLock();
    private final java.util.concurrent.locks.Condition notEmpty = lock.newCondition();

    public PriorityTaskQueue() {
        this.queue = new PriorityQueue<>(TASK_COMPARATOR);
    }

    /**
     * 入队任务。
     *
     * @param task 任务，taskId 不能为空
     */
    public void offer(SchedulerTask task) {
        Objects.requireNonNull(task, "task must not be null");
        if (task.getTaskId() == null) {
            throw new IllegalArgumentException("task.taskId must not be null");
        }
        lock.lock();
        try {
            queue.offer(task);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 非阻塞出队。
     *
     * @return 队首任务；队空返回 {@code null}
     */
    public SchedulerTask poll() {
        lock.lock();
        try {
            return queue.poll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 阻塞出队，带超时。
     *
     * @param timeoutMillis 超时毫秒；&le; 0 表示不等待
     * @return 队首任务；超时返回 {@code null}
     * @throws InterruptedException 等待被中断
     */
    public SchedulerTask poll(long timeoutMillis) throws InterruptedException {
        lock.lock();
        try {
            if (timeoutMillis <= 0) {
                return queue.poll();
            }
            long nanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (queue.isEmpty()) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return queue.poll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 查看队首但不移除。
     *
     * @return 队首任务；队空返回 {@code null}
     */
    public SchedulerTask peek() {
        lock.lock();
        try {
            return queue.peek();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 当前队列长度。
     *
     * @return 长度
     */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 是否为空。
     *
     * @return 空返回 true
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 按条件移除任务（用于取消任务）。
     *
     * @param taskId 任务 ID
     * @return 移除成功返回 true；不存在返回 false
     */
    public boolean remove(String taskId) {
        lock.lock();
        try {
            return queue.removeIf(t -> taskId.equals(t.getTaskId()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 批量移除满足条件的任务（用于租户禁用时清理排队任务）。
     *
     * @param filter 过滤条件，返回 true 的任务被移除
     * @return 被移除的任务列表
     */
    public List<SchedulerTask> drainIf(Predicate<SchedulerTask> filter) {
        lock.lock();
        try {
            List<SchedulerTask> removed = new ArrayList<>();
            queue.removeIf(t -> {
                if (filter.test(t)) {
                    removed.add(t);
                    return true;
                }
                return false;
            });
            return removed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 快照当前队列内容（按排序顺序），用于状态查询/调试。
     *
     * @return 任务的有序快照列表
     */
    public List<SchedulerTask> snapshot() {
        lock.lock();
        try {
            List<SchedulerTask> copy = new ArrayList<>(queue);
            copy.sort(TASK_COMPARATOR);
            return copy;
        } finally {
            lock.unlock();
        }
    }
}