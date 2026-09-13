package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.engine.EngineUnavailableException;
import com.levango7.dataenginebdp.encaps.service.engine.FlinkClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * Flink 引擎端点（ROADMAP 前后端接线：前端 /flink）。
 *
 * <p>提供 Flink 作业列表、提交、取消、状态查询以及 Checkpoint、Savepoint、反压指标查询。
 * 统一前缀：{@code /api/v1/flink}</p>
 *
 * <ul>
 *   <li>GET  /jobs                       — Flink 作业列表</li>
 *   <li>POST /jobs                       — 提交 Flink 作业</li>
 *   <li>POST /jobs/{id}/cancel           — 取消 Flink 作业</li>
 *   <li>GET  /jobs/{id}/status           — 获取作业状态</li>
 *   <li>GET  /jobs/{id}/checkpoints      — Checkpoint 历史</li>
 *   <li>GET  /jobs/{id}/savepoints       — Savepoint 历史</li>
 *   <li>GET  /jobs/{id}/backpressure     — 反压指标</li>
 * </ul>
 */
@Slf4j
@RestController
@Tag(name = "封装数据-Flink引擎", description = "Flink作业管理与监控")
@RequiredArgsConstructor
@RequestMapping("/api/v1/flink")
@PreAuthorize("isAuthenticated()")  // R16 安全修复：类级认证校验
public class FlinkController {

    private final FlinkClient flinkClient;

    /** Flink 作业列表。 */
    @Operation(summary = "Flink 作业列表")
    @GetMapping("/jobs")
    public ResponseEntity<?> listJobs(@RequestParam(required = false) String status) {
        String tenantId = requireTenant();
        log.info("列出 Flink 作业: status={}, tenant={}", status, tenantId);
        try {
            return ResponseEntity.ok(flinkClient.listJobs(tenantId, status));
        } catch (EngineUnavailableException e) {
            log.warn("Flink 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Flink 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 提交 Flink 作业请求体。 */
    public record SubmitJobRequest(
            @NotBlank(message = "作业名称不能为空") String name,
            String sql,
            String jobUri,
            @Min(value = 1, message = "并行度最小为 1") @Max(value = 256, message = "并行度最大为 256") Integer parallelism,
            Long checkpointIntervalMs) {
    }

    /** 提交 Flink 作业。 */
    @Operation(summary = "提交 Flink 作业")
    @PostMapping("/jobs")
    public ResponseEntity<?> submitJob(@Valid @RequestBody SubmitJobRequest req) {
        String tenantId = requireTenant();
        log.info("提交 Flink 作业: name={}, tenant={}", req.name(), tenantId);
        try {
            int parallelism = req.parallelism() != null ? req.parallelism() : 1;
            long checkpointMs = req.checkpointIntervalMs() != null ? req.checkpointIntervalMs() : 60000L;
            String sql = req.sql() != null ? req.sql() : "";
            // P2-6: 创建操作返回 201 CREATED
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(flinkClient.submitJob(tenantId, req.name(), sql, parallelism, checkpointMs));
        } catch (EngineUnavailableException e) {
            log.warn("Flink 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Flink 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 取消 Flink 作业。 */
    @Operation(summary = "取消 Flink 作业")
    @PostMapping("/jobs/{id}/cancel")
    public ResponseEntity<?> cancelJob(@PathVariable String id) {
        String tenantId = requireTenant();
        log.info("取消 Flink 作业: jobId={}, tenant={}", id, tenantId);
        try {
            flinkClient.cancelJob(tenantId, id);
            return ResponseEntity.ok(Map.of("cancelled", true, "jobId", id));
        } catch (EngineUnavailableException e) {
            log.warn("Flink 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Flink 引擎不可用", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.warn("非法 Flink jobId: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "invalid jobId", "message", e.getMessage()));
        }
    }

    /** 获取作业状态。 */
    @Operation(summary = "获取作业状态")
    @GetMapping("/jobs/{id}/status")
    public ResponseEntity<?> getJobStatus(@PathVariable String id) {
        String tenantId = requireTenant();
        log.info("查询 Flink 作业状态: jobId={}, tenant={}", id, tenantId);
        try {
            return ResponseEntity.ok(flinkClient.getJobStatus(tenantId, id));
        } catch (EngineUnavailableException e) {
            log.warn("Flink 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Flink 引擎不可用", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.warn("非法 Flink jobId: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "invalid jobId", "message", e.getMessage()));
        }
    }

    /** Checkpoint 历史。 */
    @Operation(summary = "Checkpoint 历史")
    @GetMapping("/jobs/{id}/checkpoints")
    public ResponseEntity<?> getCheckpoints(@PathVariable String id) {
        String tenantId = requireTenant();
        log.info("查询 Flink Checkpoint: jobId={}, tenant={}", id, tenantId);
        try {
            List<Map<String, Object>> checkpoints = flinkClient.getCheckpoints(tenantId, id);
            return ResponseEntity.ok(checkpoints);
        } catch (EngineUnavailableException e) {
            log.warn("Flink 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Flink 引擎不可用", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.warn("非法 Flink jobId: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "invalid jobId", "message", e.getMessage()));
        }
    }

    /** Savepoint 历史。P3-12: Flink REST 暂未实现 Savepoint 历史查询，返回空列表。 */
    @Operation(summary = "Savepoint 历史")
    @GetMapping("/jobs/{id}/savepoints")
    public ResponseEntity<List<Map<String, Object>>> getSavepoints(@PathVariable String id) {
        String tenantId = requireTenant();
        log.info("查询 Flink Savepoint: jobId={}, tenant={}", id, tenantId);
        // P3-12: Flink REST API 不直接支持 Savepoint 历史查询
        // 可通过 /jobs/{id}/checkpoints 获取 checkpoint 信息作为近似
        // 当前返回空列表，前端应处理空结果
        return ResponseEntity.ok(List.of());
    }

    /** 反压指标。 */
    @Operation(summary = "查询Flink作业详情")
    @GetMapping("/jobs/{id}/backpressure")
    public ResponseEntity<?> getBackpressure(@PathVariable String id) {
        String tenantId = requireTenant();
        log.info("查询 Flink 反压: jobId={}, tenant={}", id, tenantId);
        try {
            return ResponseEntity.ok(flinkClient.getBackpressure(tenantId, id));
        } catch (EngineUnavailableException e) {
            log.warn("Flink 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Flink 引擎不可用", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.warn("非法 Flink jobId: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "invalid jobId", "message", e.getMessage()));
        }
    }

    /** 租户上下文校验（无则拒绝，防跨租户越权）。 */
    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /** 租户上下文缺失时返回 403（防跨租户越权信息泄露）。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        log.warn("操作被拒绝: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "message", e.getMessage()));
    }
}
