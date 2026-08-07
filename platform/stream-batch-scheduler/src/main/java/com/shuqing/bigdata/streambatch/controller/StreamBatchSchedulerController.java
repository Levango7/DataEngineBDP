package com.shuqing.bigdata.streambatch.controller;

import com.shuqing.bigdata.streambatch.model.DagExecutionResult;
import com.shuqing.bigdata.streambatch.model.StreamBatchDag;
import com.shuqing.bigdata.streambatch.router.QueryMode;
import com.shuqing.bigdata.streambatch.router.ViewSelectionResult;
import com.shuqing.bigdata.streambatch.service.StreamBatchOrchestrationService;
import com.shuqing.bigdata.streambatch.service.ViewRouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
@RequestMapping("/api/v1/stream-batch")
@RequiredArgsConstructor
public class StreamBatchSchedulerController {

    private final StreamBatchOrchestrationService orchestrationService;
    private final ViewRouterService viewRouterService;

    /**
     * 提交流批 DAG。
     *
     * @param dag 流批 DAG
     * @return 执行结果
     */
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
    @GetMapping("/dags")
    public ResponseEntity<Map<String, DagExecutionResult>> getAllHistory() {
        return ResponseEntity.ok(orchestrationService.getAllHistory());
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