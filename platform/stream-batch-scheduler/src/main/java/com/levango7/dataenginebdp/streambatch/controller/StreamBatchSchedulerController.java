package com.levango7.dataenginebdp.streambatch.controller;

import com.levango7.dataenginebdp.streambatch.model.DagExecutionResult;
import com.levango7.dataenginebdp.streambatch.model.StreamBatchDag;
import com.levango7.dataenginebdp.streambatch.router.QueryMode;
import com.levango7.dataenginebdp.streambatch.router.ViewSelectionResult;
import com.levango7.dataenginebdp.streambatch.service.StreamBatchOrchestrationService;
import com.levango7.dataenginebdp.streambatch.service.ViewRouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import java.util.Map;

/**
 * 流批统一调度 REST API 控制器。
 *
 * <p>提供以下端点：
 * <ul>
 *   <li>{@code POST /api/v1/stream-batch/dags} — 提交流批 DAG</li>
 *   <li>{@code GET /api/v1/stream-batch/dags/{dagId}} — 查询 DAG 执行结果</li>
 *   <li>{@code GET /api/v1/stream-batch/dags} — 查询所有 DAG 执行历史</li>
 *   <li>{@code POST /api/v1/stream-batch/router/route} — BI 视图路由</li>
 * </ul>
 */
@Slf4j
@RestController
@Tag(name = "流批调度-统一编排", description = "流批DAG提交与BI视图路由")
@RequestMapping("/api/v1/stream-batch")
@RequiredArgsConstructor
public class StreamBatchSchedulerController {

    private final StreamBatchOrchestrationService orchestrationService;
    private final ViewRouterService viewRouterService;
    private final com.levango7.dataenginebdp.streambatch.run.DagRunService dagRunService;

    /**
     * 提交流批 DAG。
     *
     * @param dag 流批 DAG
     * @return 执行结果
     */
    @Operation(summary = "提交流批 DAG")
    @PostMapping("/dags")
    public ResponseEntity<DagExecutionResult> submitDag(@Valid @RequestBody StreamBatchDag dag) {
        log.info("收到 DAG 提交请求: dagId={}, name={}", dag.getDagId(), dag.getName());
        DagExecutionResult result = orchestrationService.submitDag(dag);
        return ResponseEntity.ok(result);
    }

    /**
     * 查询 DAG 执行结果。
     *
     * @param dagId DAG ID
     * @return 执行结果
     */
    @Operation(summary = "查询 DAG 执行结果")
    @GetMapping("/dags/{dagId}")
    public ResponseEntity<DagExecutionResult> getDagResult(@PathVariable String dagId) {
        DagExecutionResult result = orchestrationService.getDagResult(dagId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 查询所有 DAG 执行历史。
     *
     * @return DAG 执行历史 Map
     */
    @Operation(summary = "查询所有 DAG 执行历史")
    @GetMapping("/dags")
    public ResponseEntity<Map<String, DagExecutionResult>> getAllHistory() {
        return ResponseEntity.ok(orchestrationService.getAllHistory());
    }

    /**
     * 分页查询某 DAG 的运行历史（任务运维中心）。
     *
     * @param dagId  DAG ID
     * @param status 状态过滤（可空）
     * @param page   页号
     * @param size   每页大小
     * @return 分页运行历史
     */
    @Operation(summary = "分页查询某 DAG 的运行历史（任务运维中心）")
    @GetMapping("/dags/{dagId}/runs")
    public ResponseEntity<?> listRuns(
            @PathVariable String dagId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        com.levango7.dataenginebdp.streambatch.model.ExecutionStatus st = null;
        if (status != null && !status.isBlank()) {
            st = com.levango7.dataenginebdp.streambatch.model.ExecutionStatus.valueOf(status.toUpperCase());
        }
        return ResponseEntity.ok(dagRunService.listRuns(dagId, st, page, size));
    }

    /**
     * 失败重跑：按历史 runId 复原参数重新执行。
     *
     * @param dagId       DAG ID
     * @param runId       历史 runId
     * @param triggeredBy 触发人（header X-Operator）
     * @return 新执行结果
     */
    @Operation(summary = "失败重跑：按历史 runId 复原参数重新执行")
    @PostMapping("/dags/{dagId}/runs/{runId}/rerun")
    public ResponseEntity<DagExecutionResult> rerun(
            @PathVariable String dagId,
            @PathVariable Long runId,
            @RequestHeader(value = "X-Operator", defaultValue = "anonymous") String triggeredBy) {
        log.info("请求重跑: dagId={}, runId={}, operator={}", dagId, runId, triggeredBy);
        return ResponseEntity.ok(dagRunService.rerun(dagId, runId, triggeredBy));
    }

    /**
     * 补数据：按时间区间生成回填实例。
     *
     * @param dagId  DAG ID
     * @param req    补数据请求（startDate/endDate/intervalDays）
     * @param triggeredBy 触发人
     * @return 生成实例数
     */
    @Operation(summary = "补数据：按时间区间生成回填实例")
    @PostMapping("/dags/{dagId}/backfill")
    public ResponseEntity<Map<String, Object>> backfill(
            @PathVariable String dagId,
            @RequestBody BackfillRequest req,
            @RequestHeader(value = "X-Operator", defaultValue = "anonymous") String triggeredBy) {
        log.info("请求补数据: dagId={}, range=[{} ~ {}], operator={}",
                dagId, req.startDate(), req.endDate(), triggeredBy);
        int created = dagRunService.backfill(
                dagId, req.startDate(), req.endDate(),
                req.intervalDays() <= 0 ? 1 : req.intervalDays(), triggeredBy);
        return ResponseEntity.ok(Map.of(
                "dagId", dagId, "created", created,
                "startDate", req.startDate().toString(), "endDate", req.endDate().toString()));
    }

    /** 补数据请求体。 */
    public record BackfillRequest(java.time.LocalDate startDate,
                                  java.time.LocalDate endDate,
                                  int intervalDays) {
    }

    /**
     * BI 视图路由。
     *
     * @param table               查询的 Iceberg 表全名
     * @param queryMode           查询模式（OFFLINE / REALTIME / AUTO）
     * @param originalSql         原始 SQL
     * @param latencyRequirementMs 延迟要求（毫秒，AUTO 模式用）
     * @return 视图选择结果
     */
    @Operation(summary = "BI 视图路由")
    @PostMapping("/router/route")
    public ResponseEntity<ViewSelectionResult> routeQuery(
            @RequestParam String table,
            @RequestParam(defaultValue = "AUTO") QueryMode queryMode,
            @RequestBody String originalSql,
            @RequestParam(required = false) Long latencyRequirementMs) {
        log.info("收到视图路由请求: table={}, mode={}", table, queryMode);
        ViewSelectionResult result = viewRouterService.routeQuery(
                table, queryMode, originalSql, latencyRequirementMs);
        return ResponseEntity.ok(result);
    }
}