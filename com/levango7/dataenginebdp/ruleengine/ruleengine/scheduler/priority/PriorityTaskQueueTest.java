package com.shuqing.bigdata.ruleengine.scheduler.priority;

import com.shuqing.bigdata.ruleengine.scheduler.service.SchedulerTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PriorityTaskQueue 单元测试。
 */
class PriorityTaskQueueTest {

    private SchedulerTask task(String id, TaskPriority p, LocalDateTime t) {
        return SchedulerTask.builder()
                .taskId(id).priority(p).createdAt(t)
                .tenantId("t1").ruleId(1L).build();
    }

    @Test
    @DisplayName("offer + poll — FIFO 单元素")
    void offerPoll_singleElement() {
        PriorityTaskQueue q = new PriorityTaskQueue();
        SchedulerTask t = task("t1", TaskPriority.MEDIUM, LocalDateTime.now());
        q.offer(t);

        assertThat(q.size()).isEqualTo(1);
        assertThat(q.poll()).isEqualTo(t);
        assertThat(q.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("高优先级先出队")
    void poll_highPriorityFirst() {
        PriorityTaskQueue q = new PriorityTaskQueue();
        LocalDateTime now = LocalDateTime.now();
        q.offer(task("low", TaskPriority.LOW, now));
        q.offer(task("high", TaskPriority.HIGH, now));
        q.offer(task("medium", TaskPriority.MEDIUM, now));

        assertThat(q.poll().getTaskId()).isEqualTo("high");
        assertThat(q.poll().getTaskId()).isEqualTo("medium");
        assertThat(q.poll().getTaskId()).isEqualTo("low");
    }

    @Test
    @DisplayName("同优先级按 createdAt FIFO")
    void poll_samePriorityFifoByCreatedAt() {
        PriorityTaskQueue q = new PriorityTaskQueue();
        LocalDateTime t1 = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime t2 = t1.plusSeconds(1);
        LocalDateTime t3 = t2.plusSeconds(1);
        q.offer(task("c", TaskPriority.MEDIUM, t3));
        q.offer(task("a", TaskPriority.MEDIUM, t1));
        q.offer(task("b", TaskPriority.MEDIUM, t2));

        assertThat(q.poll().getTaskId()).isEqualTo("a");
        assertThat(q.poll().getTaskId()).isEqualTo("b");
        assertThat(q.poll().getTaskId()).isEqualTo("c");
    }

    @Test
    @DisplayName("poll 空队列返回 null")
    void poll_empty_returnsNull() {
        PriorityTaskQueue q = new PriorityTaskQueue();
        assertThat(q.poll()).isNull();
        assertThat(q.peek()).isNull();
    }

    @Test
    @DisplayName("remove — 按 taskId 移除")
    void remove_byTaskId() {
        PriorityTaskQueue q = new PriorityTaskQueue();
        LocalDateTime now = LocalDateTime.now();
        q.offer(task("a", TaskPriority.HIGH, now));
        q.offer(task("b", TaskPriority.LOW, now));

        assertThat(q.remove("a")).isTrue();
        assertThat(q.size()).isEqualTo(1);
        assertThat(q.poll().getTaskId()).isEqualTo("b");
    }

    @Test
    @DisplayName("remove — 不存在返回 false")
    void remove_nonExistent() {
        PriorityTaskQueue q = new PriorityTaskQueue();
        q.offer(task("a", TaskPriority.HIGH, LocalDateTime.now()));
        assertThat(q.remove("zzz")).isFalse();
    }

    @Test
    @DisplayName("drainIf — 按条件批量移除")
    void drainIf_removesMatching() {
        PriorityTaskQueue q = new PriorityTaskQueue();
        LocalDateTime now = LocalDateTime.now();
        q.offer(task("a", TaskPriority.HIGH, now));
        q.offer(task("b", TaskPriority.LOW, now));
        q.offer(task("c", TaskPriority.LOW, now));

        List<SchedulerTask> removed = q.drainIf(t -> t.getPriority() == TaskPriority.LOW);
        assertThat(removed).hasSize(2);
        assertThat(q.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("snapshot — 返回有序快照但不影响队列")
    void snapshot_orderedAndNonDestructive() {
        PriorityTaskQueue q = new PriorityTaskQueue();
        LocalDateTime now = LocalDateTime.now();
        q.offer(task("low", TaskPriority.LOW, now));
        q.offer(task("high", TaskPriority.HIGH, now));

        List<SchedulerTask> snap = q.snapshot();
        assertThat(snap.get(0).getTaskId()).isEqualTo("high");
        assertThat(q.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("poll 带超时 — 空队列超时返回 null")
    void poll_withTimeout_emptyReturnsNull() throws Exception {
        PriorityTaskQueue q = new PriorityTaskQueue();
        long start = System.currentTimeMillis();
        SchedulerTask result = q.poll(100);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(result).isNull();
        assertThat(elapsed).isGreaterThanOrEqualTo(80L);
    }

    @Test
    @DisplayName("offer 后 poll 带超时立即返回")
    void poll_withTimeout_availableReturnsImmediately() throws Exception {
        PriorityTaskQueue q = new PriorityTaskQueue();
        SchedulerTask t = task("t1", TaskPriority.HIGH, LocalDateTime.now());
        q.offer(t);

        long start = System.currentTimeMillis();
        SchedulerTask result = q.poll(1000);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(result).isEqualTo(t);
        assertThat(elapsed).isLessThan(100L);
    }
}