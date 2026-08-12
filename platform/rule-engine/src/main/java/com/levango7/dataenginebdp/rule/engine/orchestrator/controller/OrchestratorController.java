package com.levango7.dataenginebdp.rule.engine.orchestrator.controller;

import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagGraph;
import com.levango7.dataenginebdp.rule.engine.orchestrator.scheduler.TaskResult;
import com.levango7.dataenginebdp.rule.engine.orchestrator.service.OrchestratorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 编排引擎 REST 控制器。
 *
 * <p>提供 DAG 提交、查询、执行、停止、可视化与删除端点。
 * 路径前缀 {@code /api/v1/orchestrator/dags}。</p>
 *
 * <p>端点一览：
 * <ul>
 *   <li>POST   /dags            提交 DAG</li>
 *   <li>GET    /dags            列出所有 DAG</li>
 *   <li>GET    /dags/{id}       查询 DAG 详情</li>
 *   <li>POST   /dags/{id}/run   执行 DAG</li>
 *   <li>POST   /dags/{id}/stop  停止 DAG</li>
 *   <li>GET    /dags/{id}/results 查询执行结果</li>
 *   <li>GET    /dags/{id}/mermaid 生成 Mermaid 文本</li>
 *   <li>GET    /dags/{id}/json  导出 JSON 结构</li>
 *   <li>DELETE /dags/{id}       删除 DAG</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/orchestrator/dags")
public class OrchestratorController {

    private final OrchestratorService orchestratorService;

    public OrchestratorController(OrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    /** 提交 DAG */
    @PostMapping
    public ResponseEntity<DagGraph> submit(@RequestBody DagGraph graph) {
        DagGraph saved = orchestratorService.submit(graph);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** 列出所有 DAG */
    @GetMapping
    public ResponseEntity<List<DagGraph>> listAll() {
        return ResponseEntity.ok(orchestratorService.listAll());
    }

    /** 查询 DAG 详情 */
    @GetMapping("/{id}")
    public ResponseEntity<DagGraph> getDag(@PathVariable String id) {
        DagGraph graph = orchestratorService.getDag(id);
        if (graph == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(graph);
    }

    /** 执行 DAG */
    @PostMapping("/{id}/run")
    public ResponseEntity<Map<String, TaskResult>> run(@PathVariable String id) {
        return ResponseEntity.ok(orchestratorService.runDag(id));
    }

    /** 停止 DAG */
    @PostMapping("/{id}/stop")
    public ResponseEntity<Void> stop(@PathVariable String id) {
        orchestratorService.stop(id);
        return ResponseEntity.accepted().build();
    }

    /** 查询执行结果 */
    @GetMapping("/{id}/results")
    public ResponseEntity<Map<String, TaskResult>> results(@PathVariable String id) {
        Map<String, TaskResult> results = orchestratorService.getResults(id);
        if (results == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(results);
    }

    /** 生成 Mermaid 可视化文本 */
    @GetMapping("/{id}/mermaid")
    public ResponseEntity<String> mermaid(@PathVariable String id) {
        return ResponseEntity.ok(orchestratorService.visualize(id));
    }

    /** 导出 JSON 结构 */
    @GetMapping("/{id}/json")
    public ResponseEntity<Map<String, Object>> json(@PathVariable String id) {
        return ResponseEntity.ok(orchestratorService.exportJson(id));
    }

    /** 删除 DAG */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        boolean removed = orchestratorService.delete(id);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 异常处理：非法参数（如 DAG 不存在、存在环）返回 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArg(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "bad_request", "message", e.getMessage()));
    }

    /**
     * 异常处理：状态异常（如环检测失败）返回 422。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "invalid_dag", "message", e.getMessage()));
    }
}