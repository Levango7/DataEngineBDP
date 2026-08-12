package com.levango7.dataenginebdp.ruleengine.scheduler.elastic;

import com.levango7.dataenginebdp.ruleengine.scheduler.priority.PriorityTaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LoadMonitor 单元测试。
 */
class LoadMonitorTest {

    private PriorityTaskQueue queue;
    private LoadMonitor monitor;
    private WorkerPool workerPool;

    @BeforeEach
    void setUp() {
        queue = new PriorityTaskQueue();
        monitor = new LoadMonitor(queue);
        // 用空 handler 创建 worker 池，不启动线程，仅用于计数
        workerPool = new WorkerPool(queue, task -> { }, 100L, 1, 4);
        monitor.bind(workerPool);
    }

    @Test
    @DisplayName("queueSize — 反映队列长度")
    void queueSize() {
        assertThat(monitor.queueSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("workerCount — 未启动为 0")
    void workerCount_beforeStart() {
        assertThat(monitor.workerCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("avgLoad — worker 为 0 时返回 0")
    void avgLoad_noWorkers() {
        assertThat(monitor.avgLoad()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("utilization — worker 为 0 时返回 0")
    void utilization_noWorkers() {
        assertThat(monitor.utilization()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("recordCompleted / totalCompletedTasks — 累计正确")
    void completedCounter() {
        monitor.recordCompleted();
        monitor.recordCompleted();
        assertThat(monitor.totalCompletedTasks()).isEqualTo(2L);
    }

    @Test
    @DisplayName("recordRejected / totalRejectedTasks — 累计正确")
    void rejectedCounter() {
        monitor.recordRejected();
        assertThat(monitor.totalRejectedTasks()).isEqualTo(1L);
    }

    @Test
    @DisplayName("avgLoad — 启动 worker 后计算正确")
    void avgLoad_withWorkers() {
        workerPool.start(2);
        // 队列空，负载 0
        assertThat(monitor.avgLoad()).isEqualTo(0.0);
        workerPool.stop();
    }

    @Test
    @DisplayName("activeTaskCount — taskStarted/Finished 更新计数")
    void activeTaskCount_viaCallbacks() {
        workerPool.start(2);
        workerPool.taskStarted();
        workerPool.taskStarted();
        assertThat(monitor.activeTaskCount()).isEqualTo(2);
        workerPool.taskFinished();
        assertThat(monitor.activeTaskCount()).isEqualTo(1);
        workerPool.stop();
    }
}