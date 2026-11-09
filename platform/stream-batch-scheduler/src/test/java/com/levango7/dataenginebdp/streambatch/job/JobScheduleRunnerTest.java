package com.levango7.dataenginebdp.streambatch.job;

import com.levango7.dataenginebdp.common.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JobScheduleRunner 单元测试（可售性补缺批 3）。
 *
 * <p>语义基准（5 段 = 分 时 日 月 周，Linux 惯例；6 段 = 秒 分 时 日 月 周，Spring 原生）：
 * <ul>
 *   <li>{@code * * * * *} / {@code 0 * * * * *} = 每分钟</li>
 *   <li>{@code *&#47;5 * * * *} / {@code 0 *&#47;5 * * * *} = 每 5 分钟</li>
 *   <li>{@code 0 * * * *} = 每小时第 0 分；{@code 0 0 * * *} = 每日 0 点（前端占位符示例）</li>
 * </ul>
 * 覆盖：shouldFire 边界（首见/同分钟去重/到点触发/未到点/非 active/非法 cron/5·6 段兼容）
 * 与 poll 调度行为（到点触发一次且同分钟去重、租户上下文注入与清理、失败不阻断、
 * 查询异常不抛出、首见仅登记基准）。</p>
 */
class JobScheduleRunnerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static JobEntity job(Long id, String schedule, String status, String tenantId) {
        JobEntity j = new JobEntity();
        j.setId(id);
        j.setName("job-" + id);
        j.setSchedule(schedule);
        j.setStatus(status);
        j.setTenantId(tenantId);
        return j;
    }

    private static JobScheduleRunner runnerWith(JobRepository repo, JobService service, long jobId, long agoSeconds) {
        JobScheduleRunner runner = new JobScheduleRunner(repo, service);
        // 拨动时间基准：把该作业的 lastFiredAt 设为 N 秒前（等价于时间推进）
        runner.lastFiredAt.put(jobId, Instant.now().minusSeconds(agoSeconds));
        return runner;
    }

    // ---------- shouldFire：触发判据 ----------

    @Test
    void firstSeenNullLastFiredAt_doesNotFire() {
        Instant now = Instant.parse("2026-11-09T10:00:30Z");
        assertFalse(JobScheduleRunner.shouldFire("* * * * *", "active", null, now));
    }

    @Test
    void sameMinuteWindow_firesOnlyOnce() {
        Instant now = Instant.parse("2026-11-09T10:00:30Z");
        Instant last = Instant.parse("2026-11-09T10:00:05Z"); // 同分钟已触发
        assertFalse(JobScheduleRunner.shouldFire("* * * * *", "active", last, now));
    }

    @Test
    void everyMinuteCron_firesOnDue_5And6FieldEquivalent() {
        Instant last = Instant.parse("2026-11-09T10:00:00Z");
        Instant now = Instant.parse("2026-11-09T10:01:10Z");
        assertTrue(JobScheduleRunner.shouldFire("* * * * *", "active", last, now)); // 5 段
        assertTrue(JobScheduleRunner.shouldFire("0 * * * * *", "active", last, now)); // 6 段
    }

    @Test
    void everyFiveMinutesCron_notBeforeDue_firesOnDue() {
        Instant last = Instant.parse("2026-11-09T10:00:00Z");
        // */5 * * * *：上次 10:00，now 10:04 ⇒ 下一触发 10:05 未到
        assertFalse(JobScheduleRunner.shouldFire(
                "*/5 * * * *", "active", last, Instant.parse("2026-11-09T10:04:00Z")));
        // now 10:05:10 ⇒ 到点
        assertTrue(JobScheduleRunner.shouldFire(
                "*/5 * * * *", "active", last, Instant.parse("2026-11-09T10:05:10Z")));
        // 6 段同义
        assertFalse(JobScheduleRunner.shouldFire(
                "0 */5 * * * *", "active", last, Instant.parse("2026-11-09T10:04:00Z")));
    }

    @Test
    void hourlyAtZeroMinute_cronSemantics() {
        // 0 * * * *（5 段）= 每小时第 0 分：上次 10:00，now 10:30 ⇒ 下一触发 11:00 未到
        Instant last = Instant.parse("2026-11-09T10:00:00Z");
        assertFalse(JobScheduleRunner.shouldFire(
                "0 * * * *", "active", last, Instant.parse("2026-11-09T10:30:00Z")));
        // now 11:00:05 ⇒ 到点
        assertTrue(JobScheduleRunner.shouldFire(
                "0 * * * *", "active", last, Instant.parse("2026-11-09T11:00:05Z")));
    }

    @Test
    void frontendPlaceholderDailyMidnight_firesOnDue() {
        // 「0 0 * * *」（每日 0 点，前端 jobmgmt cronPlaceholder 示例）：
        // 日级 cron 按服务器本地时区求值 —— 用本地时间构造「昨天 0 点 → 今天 0 点 30 分」
        ZoneId zone = ZoneId.systemDefault();
        Instant last = LocalDate.now(zone).minusDays(1).atTime(0, 0).atZone(zone).toInstant();
        Instant now = LocalDate.now(zone).atTime(0, 0, 30).atZone(zone).toInstant();
        assertTrue(JobScheduleRunner.shouldFire("0 0 * * *", "active", last, now));
        // 同日 12:00 判定（上次已是今天 0 点）⇒ 下一触发为明日 0 点，未到
        Instant noon = LocalDate.now(zone).atTime(12, 0).atZone(zone).toInstant();
        Instant todayMidnight = LocalDate.now(zone).atTime(0, 0).atZone(zone).toInstant();
        assertFalse(JobScheduleRunner.shouldFire("0 0 * * *", "active", todayMidnight, noon));
    }

    @Test
    void nonActiveStatus_doesNotFire() {
        Instant last = Instant.parse("2026-11-09T10:00:00Z");
        Instant now = Instant.parse("2026-11-09T10:05:10Z");
        assertFalse(JobScheduleRunner.shouldFire("* * * * *", "draft", last, now));
        assertFalse(JobScheduleRunner.shouldFire("* * * * *", "paused", last, now));
        // active 可通过（对照）
        assertTrue(JobScheduleRunner.shouldFire("* * * * *", "active", last, now));
    }

    @Test
    void invalidCronAndNullSchedule_doNotFire() {
        Instant last = Instant.parse("2026-11-09T10:00:00Z");
        Instant now = Instant.parse("2026-11-09T10:05:10Z");
        assertFalse(JobScheduleRunner.shouldFire("not-a-cron", "active", last, now));
        assertFalse(JobScheduleRunner.shouldFire("* * * *", "active", last, now)); // 4 段：段数非法
        assertFalse(JobScheduleRunner.shouldFire("", "active", last, now));
        assertFalse(JobScheduleRunner.shouldFire(null, "active", last, now));
    }

    // ---------- poll：调度行为 ----------

    @Test
    void poll_firesWhenDueInjectsTenantAndClearsAfter() {
        JobRepository repo = mock(JobRepository.class);
        JobService service = mock(JobService.class);
        JobEntity due = job(1L, "* * * * *", "active", "tenant-a");
        when(repo.findByScheduleIsNotNull()).thenReturn(List.of(due));
        AtomicReference<String> tenantDuringRun = new AtomicReference<>();
        when(service.runScheduled(anyLong())).thenAnswer(inv -> {
            tenantDuringRun.set(TenantContext.getTenantId());
            return Optional.empty();
        });

        runnerWith(repo, service, 1L, 120).poll();

        verify(service, times(1)).runScheduled(1L);
        assertEquals("tenant-a", tenantDuringRun.get(), "执行期必须注入作业所属租户");
        assertNull(TenantContext.getTenantId(), "执行后必须清理租户上下文");
    }

    @Test
    void poll_sameMinuteSecondRound_doesNotRefire() {
        JobRepository repo = mock(JobRepository.class);
        JobService service = mock(JobService.class);
        JobEntity due = job(1L, "* * * * *", "active", "tenant-a");
        when(repo.findByScheduleIsNotNull()).thenReturn(List.of(due));
        when(service.runScheduled(anyLong())).thenReturn(Optional.empty());

        JobScheduleRunner runner = runnerWith(repo, service, 1L, 120);
        runner.poll(); // 触发一次（并把基准更新为 now）
        runner.poll(); // 同一分钟内第二轮：去重，不重复触发

        verify(service, times(1)).runScheduled(1L);
    }

    @Test
    void poll_singleJobFailure_doesNotThrowAndClearsContext() {
        JobRepository repo = mock(JobRepository.class);
        JobService service = mock(JobService.class);
        JobEntity due = job(1L, "* * * * *", "active", "tenant-a");
        when(repo.findByScheduleIsNotNull()).thenReturn(List.of(due));
        when(service.runScheduled(anyLong())).thenThrow(new IllegalStateException("boom"));

        JobScheduleRunner runner = runnerWith(repo, service, 1L, 120);

        assertDoesNotThrow(runner::poll);
        assertNull(TenantContext.getTenantId(), "失败路径同样不得残留租户上下文");
    }

    @Test
    void poll_queryException_doesNotThrow() {
        JobRepository repo = mock(JobRepository.class);
        JobService service = mock(JobService.class);
        when(repo.findByScheduleIsNotNull()).thenThrow(new RuntimeException("db down"));

        JobScheduleRunner runner = new JobScheduleRunner(repo, service);
        assertDoesNotThrow(runner::poll);
        verify(service, never()).runScheduled(anyLong());
    }

    @Test
    void poll_firstSeenOnlyRegistersBaseline() {
        JobRepository repo = mock(JobRepository.class);
        JobService service = mock(JobService.class);
        JobEntity due = job(1L, "* * * * *", "active", "tenant-a");
        when(repo.findByScheduleIsNotNull()).thenReturn(List.of(due));

        JobScheduleRunner runner = new JobScheduleRunner(repo, service);
        runner.poll(); // 首见

        verify(service, never()).runScheduled(anyLong());
        assertTrue(runner.lastFiredAt.containsKey(1L), "首见应登记基准时刻");
    }

    @Test
    void isSchedulable_judgement() {
        assertTrue(JobScheduleRunner.isSchedulable(job(3L, "* * * * *", "active", "t")));
        assertFalse(JobScheduleRunner.isSchedulable(job(4L, "  ", "active", "t")));
        assertFalse(JobScheduleRunner.isSchedulable(job(5L, "* * * * *", "draft", "t")));
    }
}
