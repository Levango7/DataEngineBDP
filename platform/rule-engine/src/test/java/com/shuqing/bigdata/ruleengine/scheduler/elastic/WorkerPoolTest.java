package com.shuqing.bigdata.ruleengine.scheduler.elastic;

import com.shuqing.bigdata.ruleengine.scheduler.priority.PriorityTaskQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkerPool 单元测试。
 */
class WorkerPoolTest {

    private WorkerPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.stop();
        }
    }

    @Test
    @DisplayName("start — 初始化到指定大小（夹逼到 [min,max]）")
    void start_initialSize() {
        PriorityTaskQueue queue = new PriorityTaskQueue();
        pool = new WorkerPool(queue, task -> { }, 100L, 1, 8);
        pool.start(3);
        assertThat(pool.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("start — initialSize 超过 maxSize 被夹逼")
    void start_clampedToMax() {
        PriorityTaskQueue queue = new PriorityTaskQueue();
        pool = new WorkerPool(queue, task -> { }, 100L, 1, 4);
        pool.start(10);
        assertThat(pool.size()).isEqualTo(4);
    }

    @Test
    @DisplayName("start — initialSize 低于 minSize 被夹逼")
    void start_clampedToMin() {
        PriorityTaskQueue queue = new PriorityTaskQueue();
        pool = new WorkerPool(queue, task -> { }, 100L, 2, 8);
        pool.start(0);
        assertThat(pool.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("scaleTo — 扩容")
    void scaleTo_up() {
        PriorityTaskQueue queue = new PriorityTaskQueue();
        pool = new WorkerPool(queue, task -> { }, 100L, 1, 8);
        pool.start(2);
        int newSize = pool.scaleTo(5);
        assertThat(newSize).isEqualTo(5);
        assertThat(pool.size()).isEqualTo(5);
    }

    @Test
    @DisplayName("scaleTo — 缩容")
    void scaleTo_down() {
        PriorityTaskQueue queue = new PriorityTaskQueue();
        pool = new WorkerPool(queue, task -> { }, 100L, 1, 8);
        pool.start(4);
        int newSize = pool.scaleTo(2);
        assertThat(newSize).isEqualTo(2);
        assertThat(pool.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("scaleTo — 不超过 maxSize")
    void scaleTo_respectsMax() {
        PriorityTaskQueue queue = new PriorityTaskQueue();
        pool = new WorkerPool(queue, task -> { }, 100L, 1, 4);
        pool.start(2);
        int newSize = pool.scaleTo(100);
        assertThat(newSize).isEqualTo(4);
    }

    @Test
    @DisplayName("scaleTo — 不低于 minSize")
    void scaleTo_respectsMin() {
        PriorityTaskQueue queue = new PriorityTaskQueue();
        pool = new WorkerPool(queue, task -> { }, 100L, 2, 8);
        pool.start(4);
        int newSize = pool.scaleTo(0);
        assertThat(newSize).isEqualTo(2);
    }

    @Test
    @DisplayName("stop — 清空 worker")
    void stop_clearsWorkers() {
        PriorityTaskQueue queue = new PriorityTaskQueue();
        pool = new WorkerPool(queue, task -> { }, 100L, 1, 8);
        pool.start(3);
        pool.stop();
        assertThat(pool.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("activeTaskCount — taskStarted/Finished 原子更新")
    void activeTaskCount() {
        PriorityTaskQueue queue = new PriorityTaskQueue();
        pool = new WorkerPool(queue, task -> { }, 100L, 1, 8);
        pool.start(2);
        pool.taskStarted();
        assertThat(pool.activeTaskCount()).isEqualTo(1);
        pool.taskFinished();
        assertThat(pool.activeTaskCount()).isEqualTo(0);
    }
}