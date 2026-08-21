package com.shuqing.bigdata.ruleengine.scheduler.controller;

import com.shuqing.bigdata.ruleengine.scheduler.config.SchedulerProperties;
import com.shuqing.bigdata.ruleengine.scheduler.elastic.LoadMonitor;
import com.shuqing.bigdata.ruleengine.scheduler.elastic.WorkerPool;
import com.shuqing.bigdata.ruleengine.scheduler.priority.TaskPriority;
import com.shuqing.bigdata.ruleengine.scheduler.resource.ResourceAllocator;
import com.shuqing.bigdata.ruleengine.scheduler.resource.ResourceQuota;
import com.shuqing.bigdata.ruleengine.scheduler.service.SchedulerService;
import com.shuqing.bigdata.ruleengine.scheduler.service.SchedulerTask;
import com.shuqing.bigdata.ruleengine.scheduler.service.TaskStatus;
import com.shuqing.bigdata.ruleengine.scheduler.tenant.TenantInfo;
import com.shuqing.bigdata.ruleengine.scheduler.tenant.TenantManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 调度引擎 REST 控制器。
 *
 * <p>提供任务提交/查询/取消、调度器状态、租户管理与资源配额管理端点。
 * 基路径 {@code /api/v1/scheduler}。</p>
 *
 * <p>端点总览：</p>
 * <ul>
 *   <li>任务：POST /tasks、GET /tasks、GET /tasks/{id}、DELETE /tasks/{id}</li>
 *   <li>状态：GET /status</li>
 *   <li>租户：POST /tenants、GET /tenants、PUT /tenants/{id}/enabled</li>
 *   <li>配额：GET /quotas、PUT /quotas/{tenantId}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/scheduler")
public class SchedulerController {

    private final SchedulerService schedulerService;
    private final TenantManager tenantManager;
    private final ResourceAllocator resourceAllocator;
    private final LoadMonitor loadMonitor;
    private final WorkerPool workerPool;
    private final SchedulerProperties properties;

    public SchedulerController(SchedulerService schedulerService,
                               TenantManager tenantManager,
                               ResourceAllocator resourceAllocator,
                               LoadMonitor loadMonitor,
                               WorkerPool workerPool,
                               SchedulerProperties properties) {
        this.schedulerService = schedulerService;
        this.tenantManager = tenantManager;
        this.resourceAllocator = resourceAllocator;
        this.loadMonitor = loadMonitor;
        this.workerPool = workerPool;
        this.properties = properties;
    }

    // ==================== 任务 API ====================

    /** 提交调度任务 */
    @PostMapping("/tasks")
    public ResponseEntity<TaskSubmitResponse> submitTask(@RequestBody TaskSubmitRequest request) {
        SchedulerTask task = SchedulerTask.builder()
                .ruleId(request.getRuleId())
                .priority(request.getPriority() != null ? request.getPriority() : TaskPriority.MEDIUM)
                .requiredCpu(request.getRequiredCpu() != null ? request.getRequiredCpu() : 1.0)
                .requiredMemory(request.getRequiredMemory() != null ? request.getRequiredMemory() : 512L)
                .context(request.getContext())
                .tenantId(request.getTenantId())
                .userId(request.getUserId())
                .build();
        SchedulerTask submitted = schedulerService.submit(task);
        TaskSubmitResponse resp = TaskSubmitResponse.builder()
                .taskId(submitted.getTaskId())
                .status(submitted.getStatus())
                .tenantId(submitted.getTenantId())
                .createdAt(submitted.getCreatedAt())
                .errorMessage(submitted.getErrorMessage())
                .build();
        HttpStatus httpStatus = submitted.getStatus() == TaskStatus.REJECTED
                ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.ACCEPTED;
        return ResponseEntity.status(httpStatus).body(resp);
    }

    /** 查询单个任务 */
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        SchedulerTask task = schedulerService.getTask(taskId);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "task_not_found", "message", "Task " + taskId + " not found"));
        }
        return ResponseEntity.ok(toStatusResponse(task));
    }

    /** 列出全部任务 */
    @GetMapping("/tasks")
    public ResponseEntity<List<TaskStatusResponse>> listTasks() {
        List<TaskStatusResponse> list = schedulerService.listTasks().stream()
                .map(this::toStatusResponse)
                .toList();
        return ResponseEntity.ok(list);
    }

    /** 取消任务 */
    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<?> cancelTask(@PathVariable String taskId) {
        boolean cancelled = schedulerService.cancel(taskId);
        if (!cancelled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "task_not_cancellable",
                            "message", "Task " + taskId + " not found or already terminal"));
        }
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", "CANCELLED"));
    }

    // ==================== 调度器状态 ====================

    /** 查询调度引擎运行状态 */
    @GetMapping("/status")
    public ResponseEntity<SchedulerStatusResponse> status() {
        SchedulerStatusResponse resp = SchedulerStatusResponse.builder()
                .enabled(properties.isEnabled())
                .queueSize(loadMonitor.queueSize())
                .workerCount(loadMonitor.workerCount())
                .activeTaskCount(loadMonitor.activeTaskCount())
                .avgLoad(loadMonitor.avgLoad())
                .utilization(loadMonitor.utilization())
                .totalCompleted(loadMonitor.totalCompletedTasks())
                .totalRejected(loadMonitor.totalRejectedTasks())
                .build();
        return ResponseEntity.ok(resp);
    }

    // ==================== 租户管理 ====================

    /** 注册/更新租户 */
    @PostMapping("/tenants")
    public ResponseEntity<TenantInfo> registerTenant(@RequestBody Map<String, Object> body) {
        String tenantId = (String) body.get("tenantId");
        String name = (String) body.getOrDefault("name", tenantId);
        int maxConcurrent = body.containsKey("maxConcurrentTasks")
                ? ((Number) body.get("maxConcurrentTasks")).intValue() : properties.getDefaultQuota().getMaxConcurrentTasks();
        boolean enabled = body.containsKey("enabled")
                ? Boolean.parseBoolean(String.valueOf(body.get("enabled"))) : true;
        TenantInfo info = TenantInfo.builder()
                .tenantId(tenantId)
                .name(name)
                .maxConcurrentTasks(maxConcurrent)
                .enabled(enabled)
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(tenantManager.register(info));
    }

    /** 列出全部租户 */
    @GetMapping("/tenants")
    public ResponseEntity<Collection<TenantInfo>> listTenants() {
        return ResponseEntity.ok(tenantManager.listAll());
    }

    /** 启用/禁用租户 */
    @PutMapping("/tenants/{tenantId}/enabled")
    public ResponseEntity<?> setTenantEnabled(@PathVariable String tenantId,
                                              @RequestParam boolean enabled) {
        boolean ok = tenantManager.setEnabled(tenantId, enabled);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "tenant_not_found", "message", "Tenant " + tenantId + " not found"));
        }
        return ResponseEntity.ok(Map.of("tenantId", tenantId, "enabled", enabled));
    }

    // ==================== 资源配额管理 ====================

    /** 列出全部资源配额 */
    @GetMapping("/quotas")
    public ResponseEntity<Collection<ResourceQuota>> listQuotas() {
        return ResponseEntity.ok(resourceAllocator.listAll());
    }

    /** 设置租户资源配额 */
    @PutMapping("/quotas/{tenantId}")
    public ResponseEntity<ResourceQuota> setQuota(@PathVariable String tenantId,
                                                  @RequestBody Map<String, Object> body) {
        double maxCpu = body.containsKey("maxCpuCores")
                ? ((Number) body.get("maxCpuCores")).doubleValue() : properties.getDefaultQuota().getMaxCpuCores();
        long maxMem = body.containsKey("maxMemoryMb")
                ? ((Number) body.get("maxMemoryMb")).longValue() : properties.getDefaultQuota().getMaxMemoryMb();
        return ResponseEntity.ok(resourceAllocator.setQuota(tenantId, maxCpu, maxMem));
    }

    // ==================== 辅助 ====================

    private TaskStatusResponse toStatusResponse(SchedulerTask task) {
        return TaskStatusResponse.builder()
                .taskId(task.getTaskId())
                .tenantId(task.getTenantId())
                .userId(task.getUserId())
                .ruleId(task.getRuleId())
                .priority(task.getPriority())
                .status(task.getStatus())
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .finishedAt(task.getFinishedAt())
                .durationMs(task.getDurationMs())
                .errorMessage(task.getErrorMessage())
                .build();
    }
}