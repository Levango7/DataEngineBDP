package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.engine.EngineUnavailableException;
import com.levango7.dataenginebdp.encaps.service.engine.FlinkClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
public class FlinkController {

    private final FlinkClient flinkClient;

    /** Flink 作业列表。 */
    @GetMapping("/jobs")
    public ResponseEntity<?> listJobs(@RequestParam(required = false) String status) {
        log.info("列出 Flink 作业: status={}, tenant={}", status, TenantContext.getTenantId());
        try {
            return ResponseEntity.ok(flinkClient.listJobs(status));
        } catch (EngineUnavailableException e) {
            log.warn("Flink 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Flink 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 提交 Flink 作业请求体。 */
    public record SubmitJobRequest(
            String name,
            String sql,
            String jobUri,
            Integer parallelism,
            Long checkpointIntervalMs) {
    }

    /** 提交 Flink 作业。 */
    @PostMapping("/jobs")
    public ResponseEntity<?> submitJob(@RequestBody SubmitJobRequest req) {
        log.info("提交 Flink 作业: name={}, tenant={}", req.name(), TenantContext.getTenantId());
        try {
            int parallelism = req.parallelism() != null ? req.parallelism() : 1;
            long checkpointMs = req.checkpointIntervalMs() != null ? req.checkpointIntervalMs() : 60000L;
            String sql = req.sql() != null ? req.sql() : "";
            return ResponseEntity.ok(flinkClient.submitJob(req.name(), sql, parallelism, checkpointMs));
        } catch (EngineUnavailableException e) {
            log.warn("Flink 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Flink 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 取消 Flink 作业。 */
    @PostMapping("/jobs/{id}/cancel")
    public ResponseEntity<?> cancelJob(@PathVariable String id) {
        log.info("取消 Flink 作业: jobId={}, tenant={}", id, TenantContext.getTenantId());
        try {
            flinkClient.cancelJob(id);
            return ResponseEntity.ok(Map.of("cancelled", true, "jobId", id));
        } catch (EngineUnavailableException e) {
            log.warn("Flink 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Flink 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 获取作业状态。 */
    @GetMapping("/jobs/{id}/status")
    public ResponseEntity<?> getJobStatus(@PathVariable String id) {
        log.info("查询 Flink 作业状态: jobId={}, tenant={}", id, TenantContext.getTenantId());
        try {
            return ResponseEntity.ok(flinkClient.getJobStatus(id));
        } catch (EngineUnavailableException e) {
            log.warn("Flink 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Flink 引擎不可用", "message", e.getMessage()));
        }
    }

    /** Checkpoint 历史。 */
    @GetMapping("/jobs/{id}/checkpoints")
    public ResponseEntity<?> getCheckpoints(@PathVariable String id) {
        log.info("查询 Flink Checkpoint: jobId={}, tenant={}", id, TenantContext.getTenantId());
        try {
            List<Map<String, Object>> checkpoints = flinkClient.getCheckpoints(id);
            return ResponseEntity.ok(checkpoints);
        } catch (EngineUnavailableException e) {
            log.warn("Flink 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Flink 引擎不可用", "message", e.getMessage()));
        }
    }

    /** Savepoint 历史。 */
    @GetMapping("/jobs/{id}/savepoints")
    public ResponseEntity<List<Map<String, Object>>> getSavepoints(@PathVariable String id) {
        log.info("查询 Flink Savepoint: jobId={}, tenant={}", id, TenantContext.getTenantId());
        // Flink REST 暂未实现 Savepoint 历史查询，返回空列表
        return ResponseEntity.ok(List.of());
    }

    /** 反压指标。 */
    @GetMapping("/jobs/{id}/backpressure")
    public ResponseEntity<?> getBackpressure(@PathVariable String id) {
        log.info("查询 Flink 反压: jobId={}, tenant={}", id, TenantContext.getTenantId());
        try {
            return ResponseEntity.ok(flinkClient.getBackpressure(id));
        } catch (EngineUnavailableException e) {
            log.warn("Flink 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Flink 引擎不可用", "message", e.getMessage()));
        }
    }
}
