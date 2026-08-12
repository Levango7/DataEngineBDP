package com.levango7.dataenginebdp.ruleengine.scheduler.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.ruleengine.scheduler.config.SchedulerProperties;
import com.levango7.dataenginebdp.ruleengine.scheduler.elastic.LoadMonitor;
import com.levango7.dataenginebdp.ruleengine.scheduler.elastic.WorkerPool;
import com.levango7.dataenginebdp.ruleengine.scheduler.priority.TaskPriority;
import com.levango7.dataenginebdp.ruleengine.scheduler.resource.ResourceAllocator;
import com.levango7.dataenginebdp.ruleengine.scheduler.resource.ResourceQuota;
import com.levango7.dataenginebdp.ruleengine.scheduler.service.SchedulerService;
import com.levango7.dataenginebdp.ruleengine.scheduler.service.SchedulerTask;
import com.levango7.dataenginebdp.ruleengine.scheduler.service.TaskStatus;
import com.levango7.dataenginebdp.ruleengine.scheduler.tenant.TenantInfo;
import com.levango7.dataenginebdp.ruleengine.scheduler.tenant.TenantManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SchedulerController MockMvc 测试。
 *
 * <p>mock 全部依赖，验证 HTTP 状态码与 JSON 响应结构。</p>
 */
class SchedulerControllerTest {

    private MockMvc mockMvc;
    private SchedulerService schedulerService;
    private TenantManager tenantManager;
    private ResourceAllocator resourceAllocator;
    private LoadMonitor loadMonitor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        schedulerService = mock(SchedulerService.class);
        tenantManager = mock(TenantManager.class);
        resourceAllocator = mock(ResourceAllocator.class);
        loadMonitor = mock(LoadMonitor.class);
        WorkerPool workerPool = mock(WorkerPool.class);
        SchedulerProperties properties = new SchedulerProperties();

        SchedulerController controller = new SchedulerController(
                schedulerService, tenantManager, resourceAllocator, loadMonitor, workerPool, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private SchedulerTask submittedTask(TaskStatus status) {
        return SchedulerTask.builder()
                .taskId("task-001")
                .status(status)
                .tenantId("t1")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("POST /tasks — 提交成功返回 202")
    void submitTask_success() throws Exception {
        when(schedulerService.submit(any(SchedulerTask.class))).thenReturn(submittedTask(TaskStatus.QUEUED));

        mockMvc.perform(post("/api/v1/scheduler/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("ruleId", 1, "priority", "HIGH", "requiredCpu", 1.0, "requiredMemory", 512))))
                .andExpect(MockMvcResultMatchers.status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-001"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.tenantId").value("t1"));
    }

    @Test
    @DisplayName("POST /tasks — 被拒绝返回 429")
    void submitTask_rejected() throws Exception {
        when(schedulerService.submit(any(SchedulerTask.class)))
                .thenReturn(submittedTask(TaskStatus.REJECTED));

        mockMvc.perform(post("/api/v1/scheduler/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("ruleId", 1))))
                .andExpect(MockMvcResultMatchers.status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @DisplayName("GET /tasks/{id} — 存在返回 200")
    void getTask_found() throws Exception {
        SchedulerTask task = SchedulerTask.builder()
                .taskId("task-001").tenantId("t1").ruleId(1L)
                .priority(TaskPriority.HIGH).status(TaskStatus.SUCCEEDED)
                .createdAt(LocalDateTime.now()).durationMs(100L).build();
        when(schedulerService.getTask("task-001")).thenReturn(task);

        mockMvc.perform(get("/api/v1/scheduler/tasks/task-001"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-001"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    @DisplayName("GET /tasks/{id} — 不存在返回 404")
    void getTask_notFound() throws Exception {
        when(schedulerService.getTask("nope")).thenReturn(null);

        mockMvc.perform(get("/api/v1/scheduler/tasks/nope"))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(jsonPath("$.error").value("task_not_found"));
    }

    @Test
    @DisplayName("GET /tasks — 返回任务列表")
    void listTasks() throws Exception {
        SchedulerTask t = SchedulerTask.builder()
                .taskId("t1").status(TaskStatus.QUEUED).priority(TaskPriority.MEDIUM)
                .tenantId("t1").createdAt(LocalDateTime.now()).build();
        when(schedulerService.listTasks()).thenReturn(List.of(t));

        mockMvc.perform(get("/api/v1/scheduler/tasks"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$[0].taskId").value("t1"));
    }

    @Test
    @DisplayName("DELETE /tasks/{id} — 取消成功")
    void cancelTask_success() throws Exception {
        when(schedulerService.cancel("task-001")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/scheduler/tasks/task-001"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("DELETE /tasks/{id} — 不可取消返回 404")
    void cancelTask_notCancellable() throws Exception {
        when(schedulerService.cancel("task-001")).thenReturn(false);

        mockMvc.perform(delete("/api/v1/scheduler/tasks/task-001"))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(jsonPath("$.error").value("task_not_cancellable"));
    }

    @Test
    @DisplayName("GET /status — 返回调度器状态")
    void status() throws Exception {
        when(loadMonitor.queueSize()).thenReturn(3);
        when(loadMonitor.workerCount()).thenReturn(2);
        when(loadMonitor.activeTaskCount()).thenReturn(1);
        when(loadMonitor.avgLoad()).thenReturn(1.5);
        when(loadMonitor.utilization()).thenReturn(0.5);
        when(loadMonitor.totalCompletedTasks()).thenReturn(10L);
        when(loadMonitor.totalRejectedTasks()).thenReturn(2L);

        mockMvc.perform(get("/api/v1/scheduler/status"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.queueSize").value(3))
                .andExpect(jsonPath("$.workerCount").value(2))
                .andExpect(jsonPath("$.activeTaskCount").value(1))
                .andExpect(jsonPath("$.totalCompleted").value(10))
                .andExpect(jsonPath("$.totalRejected").value(2));
    }

    @Test
    @DisplayName("POST /tenants — 注册租户返回 201")
    void registerTenant() throws Exception {
        TenantInfo info = TenantInfo.builder().tenantId("t1").name("租户1").maxConcurrentTasks(5).enabled(true).build();
        when(tenantManager.register(any(TenantInfo.class))).thenReturn(info);

        mockMvc.perform(post("/api/v1/scheduler/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("tenantId", "t1", "name", "租户1", "maxConcurrentTasks", 5))))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(jsonPath("$.tenantId").value("t1"))
                .andExpect(jsonPath("$.maxConcurrentTasks").value(5));
    }

    @Test
    @DisplayName("GET /tenants — 返回租户列表")
    void listTenants() throws Exception {
        TenantInfo t = TenantInfo.builder().tenantId("t1").build();
        when(tenantManager.listAll()).thenReturn(List.of(t));

        mockMvc.perform(get("/api/v1/scheduler/tenants"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value("t1"));
    }

    @Test
    @DisplayName("PUT /tenants/{id}/enabled — 启用租户")
    void setTenantEnabled() throws Exception {
        when(tenantManager.setEnabled("t1", true)).thenReturn(true);

        mockMvc.perform(put("/api/v1/scheduler/tenants/t1/enabled").param("enabled", "true"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @DisplayName("PUT /tenants/{id}/enabled — 租户不存在返回 404")
    void setTenantEnabled_notFound() throws Exception {
        when(tenantManager.setEnabled("unknown", true)).thenReturn(false);

        mockMvc.perform(put("/api/v1/scheduler/tenants/unknown/enabled").param("enabled", "true"))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(jsonPath("$.error").value("tenant_not_found"));
    }

    @Test
    @DisplayName("GET /quotas — 返回配额列表")
    void listQuotas() throws Exception {
        ResourceQuota q = ResourceQuota.builder().tenantId("t1").maxCpuCores(4.0).maxMemoryMb(4096L).build();
        when(resourceAllocator.listAll()).thenReturn(List.of(q));

        mockMvc.perform(get("/api/v1/scheduler/quotas"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value("t1"))
                .andExpect(jsonPath("$[0].maxCpuCores").value(4.0));
    }

    @Test
    @DisplayName("PUT /quotas/{tenantId} — 设置配额")
    void setQuota() throws Exception {
        ResourceQuota q = ResourceQuota.builder().tenantId("t1").maxCpuCores(8.0).maxMemoryMb(8192L).build();
        when(resourceAllocator.setQuota("t1", 8.0, 8192L)).thenReturn(q);

        mockMvc.perform(put("/api/v1/scheduler/quotas/t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("maxCpuCores", 8.0, "maxMemoryMb", 8192))))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$.maxCpuCores").value(8.0))
                .andExpect(jsonPath("$.maxMemoryMb").value(8192));
    }
}