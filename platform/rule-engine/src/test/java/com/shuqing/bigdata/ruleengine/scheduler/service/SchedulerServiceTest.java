package com.shuqing.bigdata.ruleengine.scheduler.service;

import com.shuqing.bigdata.ruleengine.model.RuleExecutionRequest;
import com.shuqing.bigdata.ruleengine.model.RuleExecutionResult;
import com.shuqing.bigdata.ruleengine.scheduler.config.SchedulerProperties;
import com.shuqing.bigdata.ruleengine.scheduler.elastic.LoadMonitor;
import com.shuqing.bigdata.ruleengine.scheduler.priority.PriorityTaskQueue;
import com.shuqing.bigdata.ruleengine.scheduler.priority.TaskPriority;
import com.shuqing.bigdata.ruleengine.scheduler.resource.ResourceAllocator;
import com.shuqing.bigdata.ruleengine.scheduler.tenant.TenantInfo;
import com.shuqing.bigdata.ruleengine.scheduler.tenant.TenantManager;
import com.shuqing.bigdata.ruleengine.service.RuleExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SchedulerService 单元测试。
 *
 * <p>mock {@link RuleExecutionService} 隔离规则执行逻辑，专注验证调度编排：
 * 租户校验、并发数控制、资源分配、状态流转、取消。</p>
 */
class SchedulerServiceTest {

    private PriorityTaskQueue queue;
    private ResourceAllocator allocator;
    private TenantManager tenantManager;
    private LoadMonitor loadMonitor;
    private RuleExecutionService ruleExecutionService;
    private SchedulerService service;

    @BeforeEach
    void setUp() {
        SchedulerProperties props = new SchedulerProperties();
        props.getDefaultQuota().setMaxCpuCores(4.0);
        props.getDefaultQuota().setMaxMemoryMb(4096L);
        props.getDefaultQuota().setMaxConcurrentTasks(2);

        queue = new PriorityTaskQueue();
        allocator = new ResourceAllocator(props);
        tenantManager = new TenantManager();
        loadMonitor = new LoadMonitor(queue);
        ruleExecutionService = mock(RuleExecutionService.class);
        service = new SchedulerService(queue, allocator, tenantManager, loadMonitor, ruleExecutionService);

        // 注册默认租户
        tenantManager.register(TenantInfo.builder().tenantId("t1").maxConcurrentTasks(2).build());
    }

    private SchedulerTask newTask(String tenantId, TaskPriority priority) {
        return SchedulerTask.builder()
                .tenantId(tenantId)
                .ruleId(1L)
                .priority(priority)
                .requiredCpu(1.0)
                .requiredMemory(512L)
                .context(Map.of())
                .build();
    }

    @Test
    @DisplayName("submit — 正常入队，状态 QUEUED")
    void submit_success() {
        SchedulerTask task = newTask("t1", TaskPriority.HIGH);
        SchedulerTask result = service.submit(task);

        assertThat(result.getTaskId()).isNotBlank();
        assertThat(result.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(queue.size()).isEqualTo(1);
        assertThat(tenantManager.get("t1").orElseThrow().getQueuedTaskCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("submit — 自动补全 taskId")
    void submit_generatesTaskId() {
        SchedulerTask task = newTask("t1", TaskPriority.MEDIUM);
        task.setTaskId(null);
        service.submit(task);
        assertThat(task.getTaskId()).isNotBlank();
    }

    @Test
    @DisplayName("submit — 未注册租户拒绝")
    void submit_unknownTenant_rejected() {
        SchedulerTask result = service.submit(newTask("unknown", TaskPriority.MEDIUM));
        assertThat(result.getStatus()).isEqualTo(TaskStatus.REJECTED);
        assertThat(result.getErrorMessage()).contains("tenant_not_allowed");
        assertThat(loadMonitor.totalRejectedTasks()).isEqualTo(1);
    }

    @Test
    @DisplayName("submit — 禁用租户拒绝")
    void submit_disabledTenant_rejected() {
        tenantManager.register(TenantInfo.builder().tenantId("t2").enabled(false).build());
        SchedulerTask result = service.submit(newTask("t2", TaskPriority.MEDIUM));
        assertThat(result.getStatus()).isEqualTo(TaskStatus.REJECTED);
    }

    @Test
    @DisplayName("submit — 并发数超限拒绝")
    void submit_concurrencyExceeded_rejected() {
        // maxConcurrentTasks=2，提交 2 个后第 3 个应拒绝
        service.submit(newTask("t1", TaskPriority.MEDIUM));
        service.submit(newTask("t1", TaskPriority.MEDIUM));
        SchedulerTask third = service.submit(newTask("t1", TaskPriority.MEDIUM));
        assertThat(third.getStatus()).isEqualTo(TaskStatus.REJECTED);
        assertThat(third.getErrorMessage()).contains("concurrency_limit");
    }

    @Test
    @DisplayName("handle — 资源分配成功，规则执行成功 → SUCCEEDED")
    void handle_success() {
        when(ruleExecutionService.execute(any(RuleExecutionRequest.class)))
                .thenReturn(RuleExecutionResult.builder().status("PASS").build());

        SchedulerTask task = newTask("t1", TaskPriority.HIGH);
        service.submit(task);
        SchedulerTask queued = queue.poll();

        service.handle(queued);

        SchedulerTask done = service.getTask(task.getTaskId());
        assertThat(done.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(done.getStartedAt()).isNotNull();
        assertThat(done.getFinishedAt()).isNotNull();
        assertThat(done.getDurationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(loadMonitor.totalCompletedTasks()).isEqualTo(1);
        // 资源已释放
        assertThat(allocator.getQuota("t1").orElseThrow().getUsedCpuCores()).isEqualTo(0.0);
        // 活跃计数归零
        assertThat(tenantManager.getActiveCount("t1")).isEqualTo(0);
    }

    @Test
    @DisplayName("handle — 规则执行返回 ERROR → FAILED")
    void handle_ruleError_failed() {
        when(ruleExecutionService.execute(any(RuleExecutionRequest.class)))
                .thenReturn(RuleExecutionResult.builder().status("ERROR").message("rule_error").build());

        SchedulerTask task = newTask("t1", TaskPriority.MEDIUM);
        service.submit(task);
        service.handle(queue.poll());

        SchedulerTask done = service.getTask(task.getTaskId());
        assertThat(done.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(done.getErrorMessage()).isEqualTo("rule_error");
    }

    @Test
    @DisplayName("handle — 规则执行抛异常 → FAILED")
    void handle_exception_failed() {
        when(ruleExecutionService.execute(any(RuleExecutionRequest.class)))
                .thenThrow(new RuntimeException("boom"));

        SchedulerTask task = newTask("t1", TaskPriority.MEDIUM);
        service.submit(task);
        service.handle(queue.poll());

        SchedulerTask done = service.getTask(task.getTaskId());
        assertThat(done.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(done.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("handle — 资源不足 → REJECTED")
    void handle_resourceUnavailable_rejected() {
        // 设置极小配额
        allocator.setQuota("t1", 0.5, 256L);
        when(ruleExecutionService.execute(any())).thenReturn(RuleExecutionResult.builder().status("PASS").build());

        SchedulerTask task = newTask("t1", TaskPriority.MEDIUM); // 需要 1.0 CPU > 0.5
        service.submit(task);
        service.handle(queue.poll());

        SchedulerTask done = service.getTask(task.getTaskId());
        assertThat(done.getStatus()).isEqualTo(TaskStatus.REJECTED);
        assertThat(done.getErrorMessage()).isEqualTo("resource_unavailable");
        assertThat(loadMonitor.totalRejectedTasks()).isEqualTo(1);
    }

    @Test
    @DisplayName("handle — 透传 tenantId 到 RuleExecutionRequest")
    void handle_passesTenantIdToExecutor() {
        ArgumentCaptor<RuleExecutionRequest> captor = ArgumentCaptor.forClass(RuleExecutionRequest.class);
        when(ruleExecutionService.execute(captor.capture()))
                .thenReturn(RuleExecutionResult.builder().status("PASS").build());

        SchedulerTask task = newTask("t1", TaskPriority.MEDIUM);
        service.submit(task);
        service.handle(queue.poll());

        assertThat(captor.getValue().getTenantId()).isEqualTo("t1");
        assertThat(captor.getValue().getRuleId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("cancel — QUEUED 任务可取消")
    void cancel_queuedTask() {
        SchedulerTask task = newTask("t1", TaskPriority.MEDIUM);
        service.submit(task);

        boolean cancelled = service.cancel(task.getTaskId());
        assertThat(cancelled).isTrue();
        assertThat(service.getTask(task.getTaskId()).getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(queue.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("cancel — 终态任务不可取消")
    void cancel_terminalTask_fails() {
        SchedulerTask task = newTask("t1", TaskPriority.MEDIUM);
        service.submit(task);
        service.cancel(task.getTaskId()); // → CANCELLED

        boolean again = service.cancel(task.getTaskId());
        assertThat(again).isFalse();
    }

    @Test
    @DisplayName("cancel — 不存在返回 false")
    void cancel_nonExistent() {
        assertThat(service.cancel("nope")).isFalse();
    }

    @Test
    @DisplayName("getTask — 不存在返回 null")
    void getTask_nonExistent() {
        assertThat(service.getTask("nope")).isNull();
    }

    @Test
    @DisplayName("listTasks — 返回所有已提交任务")
    void listTasks() {
        service.submit(newTask("t1", TaskPriority.MEDIUM));
        service.submit(newTask("t1", TaskPriority.HIGH));
        assertThat(service.listTasks()).hasSize(2);
    }

    @Test
    @DisplayName("purgeTerminated — 清理终态任务")
    void purgeTerminated() {
        SchedulerTask t1 = newTask("t1", TaskPriority.MEDIUM);
        service.submit(t1);
        service.cancel(t1.getTaskId()); // CANCELLED

        SchedulerTask t2 = newTask("t1", TaskPriority.MEDIUM);
        service.submit(t2); // QUEUED

        int removed = service.purgeTerminated();
        assertThat(removed).isEqualTo(1);
        assertThat(service.listTasks()).hasSize(1);
    }
}