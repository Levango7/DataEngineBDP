package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.SyncTaskEntity;
import com.levango7.dataenginebdp.encaps.repository.SyncTaskRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.IntegrateConnectorService;
import com.levango7.dataenginebdp.encaps.service.SeaTunnelClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集成端点（ROADMAP 前后端接线：前端 /integrate）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/integrate")
@Tag(name = "数据集成", description = "数据集成任务管理")
public class IntegrateController {

    private final SyncTaskRepository repository;
    private final IntegrateConnectorService connectorService;
    private final SeaTunnelClient seaTunnelClient;

    /** 创建/更新请求体（对齐前端 CreateSyncTaskParams）。 */
    public record SyncTaskRequest(
            @NotBlank String name,
            @NotBlank String sourceType,
            @NotBlank String targetType,
            String sourceTable,
            String targetTable,
            String schedule) {
    }

    /** 同步任务列表（分页契约）。 */
    @Operation(summary = "查询同步任务列表", description = "分页查询同步任务列表")
    @GetMapping("/tasks")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = requireTenant();
        List<SyncTaskEntity> all = repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        int total = all.size();
        int start = Math.min((page - 1) * size, total);
        int end = Math.min(start + size, total);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", all.subList(start, end).stream().map(this::toView).toList());
        body.put("total", total);
        body.put("page", page);
        body.put("size", size);
        return ResponseEntity.ok(body);
    }

    /** 任务详情。 */
    @Operation(summary = "查询同步任务详情", description = "按 ID 获取同步任务详情")
    @GetMapping("/tasks/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getTask(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId)
                .map(t -> ResponseEntity.ok((Object) toView(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建任务。 */
    @Operation(summary = "创建同步任务", description = "创建数据同步任务（status=pending）")
    @PostMapping("/tasks")
    @Transactional
    public ResponseEntity<Map<String, Object>> createTask(@Valid @RequestBody SyncTaskRequest req) {
        String tenantId = requireTenant();
        SyncTaskEntity entity = SyncTaskEntity.builder()
                .name(req.name())
                .sourceType(req.sourceType())
                .targetType(req.targetType())
                .sourceTable(req.sourceTable())
                .targetTable(req.targetTable())
                .schedule(req.schedule())
                .status("pending")
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        SyncTaskEntity saved = repository.save(entity);
        log.info("创建同步任务: id={}, name={}, source={}→{}", saved.getId(), saved.getName(),
                saved.getSourceType(), saved.getTargetType());
        return ResponseEntity.ok(toView(saved));
    }

    /** 更新任务。 */
    @Operation(summary = "更新同步任务", description = "按 ID 更新同步任务（name/sourceType/targetType/tables/schedule）")
    @PutMapping("/tasks/{id}")
    @Transactional
    public ResponseEntity<?> updateTask(@PathVariable Long id, @Valid @RequestBody SyncTaskRequest req) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            entity.setName(req.name());
            entity.setSourceType(req.sourceType());
            entity.setTargetType(req.targetType());
            entity.setSourceTable(req.sourceTable());
            entity.setTargetTable(req.targetTable());
            entity.setSchedule(req.schedule());
            entity.setUpdatedAt(Instant.now());
            return ResponseEntity.ok((Object) toView(repository.save(entity)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 删除任务。 */
    @Operation(summary = "删除同步任务", description = "按 ID 删除同步任务（租户隔离）")
    @DeleteMapping("/tasks/{id}")
    @Transactional
    public ResponseEntity<?> deleteTask(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            repository.delete(entity);
            log.info("删除同步任务: id={}", id);
            return ResponseEntity.ok(Map.of("deleted", true));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 列出数据源连接器。
     *
     * <p>对齐前端 {@code integrate.ts} 的 {@code listConnectors}。
     * 返回 SeaTunnel 内置 Source/Sink 连接器列表（含插件名、类型、状态）。</p>
     *
     * @return 200 + 连接器列表
     */
    @Operation(summary = "查询数据源连接器列表", description = "返回 SeaTunnel 内置 Source/Sink 连接器列表")
    @GetMapping("/connectors")
    public ResponseEntity<List<Map<String, Object>>> listConnectors() {
        log.info("列出连接器: tenant={}", TenantContext.getTenantId());
        return ResponseEntity.ok(connectorService.listConnectors());
    }

    /**
     * 立即运行同步任务。
     *
     * <p>对齐前端 {@code integrate.ts} 的 {@code runSyncTask}。
     * 调用 SeaTunnel REST API 启动作业，并将返回的 jobId 落库。</p>
     *
     * @param id 任务 ID
     * @return 200 若已触发；404 若不存在
     */
    @Operation(summary = "运行同步任务", description = "调用 SeaTunnel REST API 启动作业，并将返回的 jobId 落库")
    @PostMapping("/tasks/{id}/run")
    @Transactional
    public ResponseEntity<?> runTask(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            // 调用 SeaTunnel 启动作业
            String seatunnelJobId = seaTunnelClient.startJob(
                    id, entity.getSourceType(), entity.getTargetType(),
                    entity.getSourceTable(), entity.getTargetTable());
            entity.setStatus("running");
            entity.setSeatunnelJobId(seatunnelJobId);
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            log.info("运行同步任务: id={}, tenant={}, seatunnelJobId={}", id, tenantId, seatunnelJobId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("triggered", true);
            body.put("seatunnelJobId", seatunnelJobId);
            return ResponseEntity.ok((Object) body);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 停止同步任务。
     *
     * <p>对齐前端 {@code integrate.ts} 的 {@code stopSyncTask}。
     * 调用 SeaTunnel REST API 停止作业，并更新任务状态。</p>
     *
     * @param id 任务 ID
     * @return 200 若已停止；404 若不存在
     */
    @Operation(summary = "停止同步任务", description = "调用 SeaTunnel REST API 停止作业，并更新任务状态")
    @PostMapping("/tasks/{id}/stop")
    @Transactional
    public ResponseEntity<?> stopTask(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            // 调用 SeaTunnel 停止作业
            boolean stopped = seaTunnelClient.stopJob(entity.getSeatunnelJobId());
            entity.setStatus("stopped");
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            log.info("停止同步任务: id={}, tenant={}, seatunnelJobId={}, stopped={}",
                    id, tenantId, entity.getSeatunnelJobId(), stopped);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("stopped", true);
            body.put("seatunnelStopped", stopped);
            return ResponseEntity.ok((Object) body);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /** 视图映射。 */
    private Map<String, Object> toView(SyncTaskEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("name", e.getName());
        m.put("sourceType", e.getSourceType());
        m.put("targetType", e.getTargetType());
        m.put("sourceTable", e.getSourceTable());
        m.put("targetTable", e.getTargetTable());
        m.put("schedule", e.getSchedule());
        m.put("status", e.getStatus());
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }
}
