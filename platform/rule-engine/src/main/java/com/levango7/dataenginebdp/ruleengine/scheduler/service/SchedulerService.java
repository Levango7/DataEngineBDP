package com.levango7.dataenginebdp.ruleengine.scheduler.service;

import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionRequest;
import com.levango7.dataenginebdp.ruleengine.model.RuleExecutionResult;
import com.levango7.dataenginebdp.ruleengine.scheduler.elastic.LoadMonitor;
import com.levango7.dataenginebdp.ruleengine.scheduler.elastic.TaskHandler;
import com.levango7.dataenginebdp.ruleengine.scheduler.priority.PriorityTaskQueue;
import com.levango7.dataenginebdp.ruleengine.scheduler.resource.ResourceAllocator;
import com.levango7.dataenginebdp.ruleengine.scheduler.resource.ResourceRequest;
import com.levango7.dataenginebdp.ruleengine.scheduler.tenant.TenantContext;
import com.levango7.dataenginebdp.ruleengine.scheduler.tenant.TenantManager;
import com.levango7.dataenginebdp.ruleengine.service.RuleExecutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调度服务：任务提交、查询、取消与执行编排。
 *
 * <p>实现 {@link TaskHandler}，由 worker 线程拉取任务后回调 {@link #handle(SchedulerTask)}
 * 完成资源分配 → 租户上下文绑定 → 规则执行 → 资源释放 → 状态更新的完整链路。</p>
 *
 * <p>提交流程（{@link #submit(SchedulerTask)}）：</p>
 * <ol>
 *   <li>校验租户已注册且启用（{@link TenantManager#isAllowed(String)}）</li>
 *   <li>校验租户并发数（queued+active &lt; maxConcurrentTasks），避免单租户打满 worker</li>
 *   <li>入队（{@link PriorityTaskQueue#offer}），递增排队计数</li>
 * </ol>
 *
 * <p>执行流程（{@link #handle(SchedulerTask)}）：</p>
 * <ol>
 *   <li>递减排队计数，绑定调度租户上下文</li>
 *   <li>{@link ResourceAllocator#tryAllocate} 分配 CPU/内存；失败 → REJECTED</li>
 *   <li>递增活跃计数，置 RUNNING，调用 {@link RuleExecutionService#execute}</li>
 *   <li>finally：释放资源、递减活跃、置终态、记录完成</li>
 * </ol>
 *
 * <p>任务存储：{@link ConcurrentHashMap}，提交时写入，handle 中更新，查询/取消读取。
 * 终态任务保留以便客户端轮询结果，生产环境可加 TTL 清理。</p>
 */
@Slf4j
@Service
public class SchedulerService implements TaskHandler {

    private final PriorityTaskQueue queue;
    private final ResourceAllocator resourceAllocator;
    private final TenantManager tenantManager;
    private final LoadMonitor loadMonitor;
    private final RuleExecutionService ruleExecutionService;

    /** 任务存储：taskId → SchedulerTask（含最新状态） */
    private final ConcurrentHashMap<String, SchedulerTask> taskStore = new ConcurrentHashMap<>();

    public SchedulerService(PriorityTaskQueue queue,
                            ResourceAllocator resourceAllocator,
                            TenantManager tenantManager,
                            LoadMonitor loadMonitor,
                            RuleExecutionService ruleExecutionService) {
        this.queue = queue;
        this.resourceAllocator = resourceAllocator;
        this.tenantManager = tenantManager;
        this.loadMonitor = loadMonitor;
        this.ruleExecutionService = ruleExecutionService;
    }

    /**
     * 提交调度任务。
     *
     * @param task 任务（taskId/tenantId 可空，由本方法补全）
     * @return 提交后的任务（status=QUEUED）；被拒绝时 status=REJECTED 并带 errorMessage
     */
    public SchedulerTask submit(SchedulerTask task) {
        Objects.requireNonNull(task, "task must not be null");

        // 补全身份
        if (task.getTaskId() == null || task.getTaskId().isBlank()) {
            task.setTaskId(SchedulerTask.generateTaskId());
        }
        if (task.getTenantId() == null || task.getTenantId().isBlank()) {
            // 回退到 security.TenantContext（HTTP 请求级）
            task.setTenantId(com.levango7.dataenginebdp.ruleengine.security.TenantContext.getTenantId());
        }
        if (task.getPriority() == null) {
            task.setPriority(com.levango7.dataenginebdp.ruleengine.scheduler.priority.TaskPriority.MEDIUM);
        }
        if (task.getStatus() == null) {
            task.setStatus(TaskStatus.QUEUED);
        }
        task.setCreatedAt(LocalDateTime.now());

        String tenantId = task.getTenantId();
        if (tenantId == null || !tenantManager.isAllowed(tenantId)) {
            return reject(task, "tenant_not_allowed: " + tenantId);
        }

        // 并发数校验：queued + active < maxConcurrentTasks
        if (!checkConcurrency(tenantId)) {
            return reject(task, "tenant_concurrency_limit_exceeded: " + tenantId);
        }

        task.setStatus(TaskStatus.QUEUED);
        taskStore.put(task.getTaskId(), task);
        queue.offer(task);
        tenantManager.incrementQueued(tenantId);
        log.info("任务已提交: taskId={}, tenant={}, priority={}, ruleId={}",
                task.getTaskId(), tenantId, task.getPriority(), task.getRuleId());
        return task;
    }

    /**
     * 查询任务。
     *
     * @param taskId 任务 ID
     * @return 任务；不存在返回 null
     */
    public SchedulerTask getTask(String taskId) {
        return taskStore.get(taskId);
    }

    /**
     * 列出全部任务（快照）。
     *
     * @return 任务集合
     */
    public Collection<SchedulerTask> listTasks() {
        return List.copyOf(taskStore.values());
    }

    /**
     * 取消任务。仅 QUEUED 可取消（从队列移除）；RUNNING 为 best-effort 标记（实际执行不中断）。
     *
     * @param taskId 任务 ID
     * @return 取消成功返回 true；不存在或已终态返回 false
     */
    public boolean cancel(String taskId) {
        SchedulerTask task = taskStore.get(taskId);
        if (task == null || task.getStatus().isTerminal()) {
            return false;
        }
        if (task.getStatus() == TaskStatus.QUEUED) {
            if (queue.remove(taskId)) {
                tenantManager.decrementQueued(task.getTenantId());
            }
        }
        task.setStatus(TaskStatus.CANCELLED);
        task.setFinishedAt(LocalDateTime.now());
        log.info("任务已取消: taskId={}, tenant={}", taskId, task.getTenantId());
        return true;
    }

    /**
     * 清理终态任务（供管理/测试调用，避免内存增长）。
     *
     * @return 清理的任务数
     */
    public int purgeTerminated() {
        int[] removed = {0};
        taskStore.entrySet().removeIf(e -> {
            if (e.getValue().getStatus().isTerminal()) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }

    /**
     * worker 拉取任务后的执行入口（{@link TaskHandler} 实现）。
     */
    @Override
    public void handle(SchedulerTask task) {
        String tenantId = task.getTenantId();
        TenantContext.bind(tenantId, task.getTaskId());
        long startMs = System.currentTimeMillis();
        try {
            // 出队即递减排队计数
            tenantManager.decrementQueued(tenantId);

            ResourceRequest request = ResourceRequest.builder()
                    .cpuCores(task.getRequiredCpu())
                    .memoryMb(task.getRequiredMemory())
                    .build();

            if (!resourceAllocator.tryAllocate(tenantId, request)) {
                markTerminal(task, TaskStatus.REJECTED, "resource_unavailable", startMs);
                loadMonitor.recordRejected();
                return;
            }

            tenantManager.incrementActive(tenantId);
            task.setStatus(TaskStatus.RUNNING);
            task.setStartedAt(LocalDateTime.now());
            taskStore.put(task.getTaskId(), task);

            TaskStatus finalStatus;
            String errorMsg = null;
            try {
                RuleExecutionRequest execReq = new RuleExecutionRequest();
                execReq.setRuleId(task.getRuleId());
                execReq.setContext(task.getContext());
                execReq.setTenantId(tenantId);
                RuleExecutionResult result = ruleExecutionService.execute(execReq);
                if ("ERROR".equals(result.getStatus())) {
                    finalStatus = TaskStatus.FAILED;
                    errorMsg = result.getMessage();
                } else {
                    finalStatus = TaskStatus.SUCCEEDED;
                }
            } catch (RuntimeException ex) {
                finalStatus = TaskStatus.FAILED;
                errorMsg = ex.getMessage();
                log.error("任务执行异常: taskId={}", task.getTaskId(), ex);
            } finally {
                resourceAllocator.release(tenantId, request);
                tenantManager.decrementActive(tenantId);
            }
            markTerminal(task, finalStatus, errorMsg, startMs);
            loadMonitor.recordCompleted();
        } finally {
            TenantContext.clear();
        }
    }

    /** 并发数校验 */
    private boolean checkConcurrency(String tenantId) {
        return tenantManager.get(tenantId)
                .map(info -> info.getQueuedTaskCount() + info.getActiveTaskCount() < info.getMaxConcurrentTasks())
                .orElse(false);
    }

    /** 提交即拒绝 */
    private SchedulerTask reject(SchedulerTask task, String reason) {
        task.setStatus(TaskStatus.REJECTED);
        task.setErrorMessage(reason);
        task.setFinishedAt(LocalDateTime.now());
        taskStore.put(task.getTaskId(), task);
        loadMonitor.recordRejected();
        log.warn("任务被拒绝: taskId={}, reason={}", task.getTaskId(), reason);
        return task;
    }

    /** 置终态并更新存储 */
    private void markTerminal(SchedulerTask task, TaskStatus status, String errorMsg, long startMs) {
        task.setStatus(status);
        task.setErrorMessage(errorMsg);
        task.setFinishedAt(LocalDateTime.now());
        task.setDurationMs(System.currentTimeMillis() - startMs);
        taskStore.put(task.getTaskId(), task);
        log.info("任务终态: taskId={}, status={}, duration={}ms",
                task.getTaskId(), status, task.getDurationMs());
    }
}