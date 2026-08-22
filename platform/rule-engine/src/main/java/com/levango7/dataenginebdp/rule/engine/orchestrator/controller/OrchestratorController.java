package com.levango7.dataenginebdp.rule.engine.orchestrator.controller;

import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagGraph;
import com.levango7.dataenginebdp.rule.engine.orchestrator.scheduler.TaskResult;
import com.levango7.dataenginebdp.rule.engine.orchestrator.service.OrchestratorExtensionService;
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
import io.swagger.v3.oas.annotations.tags.Tag;

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
@Tag(name = "规则引擎-编排引擎", description = "DAG提交/执行/可视化")
@RequestMapping("/api/v1/orchestrator/dags")
public class OrchestratorController {

    private final OrchestratorService orchestratorService;
    private final OrchestratorExtensionService extensionService;

    public OrchestratorController(OrchestratorService orchestratorService,
                                  OrchestratorExtensionService extensionService) {
        this.orchestratorService = orchestratorService;
        this.extensionService = extensionService;
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

    /* ================================================================ */
    /* DAG 可视化扩展端点（对齐前端 orchestrator-viz.ts）                */
    /* ================================================================ */

    /**
     * 拉取 Agent 思考链。
     *
     * <p>对齐前端 {@code getThoughtChain}。</p>
     *
     * @param id DAG ID
     * @return 200 + 思考步骤列表
     */
    @GetMapping("/{id}/thoughts")
    public ResponseEntity<List<Map<String, Object>>> thoughts(@PathVariable String id) {
        return ResponseEntity.ok(extensionService.getThoughts(id));
    }

    /**
     * 拉取工具调用记录。
     *
     * <p>对齐前端 {@code getToolCalls}。</p>
     *
     * @param id DAG ID
     * @return 200 + 工具调用记录列表
     */
    @GetMapping("/{id}/tool-calls")
    public ResponseEntity<List<Map<String, Object>>> toolCalls(@PathVariable String id) {
        return ResponseEntity.ok(extensionService.getToolCalls(id));
    }

    /**
     * 查询待处理人工介入请求。
     *
     * <p>对齐前端 {@code getInterventions}。</p>
     *
     * @param id DAG ID
     * @return 200 + 介入请求列表
     */
    @GetMapping("/{id}/intervention")
    public ResponseEntity<List<Map<String, Object>>> intervention(@PathVariable String id) {
        return ResponseEntity.ok(extensionService.getInterventions(id));
    }

    /**
     * 提交人工审批。
     *
     * <p>对齐前端 {@code submitIntervention}（POST /intervene）。</p>
     *
     * @param id      DAG ID
     * @param payload 审批载荷
     * @return 200 + 更新后的介入请求
     */
    @PostMapping("/{id}/intervene")
    public ResponseEntity<Map<String, Object>> intervene(@PathVariable String id,
                                                         @RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(extensionService.submitIntervention(id, payload));
    }

    /**
     * 拉取检查点列表。
     *
     * <p>对齐前端 {@code getCheckpoints}。</p>
     *
     * @param id DAG ID
     * @return 200 + 检查点列表
     */
    @GetMapping("/{id}/checkpoints")
    public ResponseEntity<List<Map<String, Object>>> checkpoints(@PathVariable String id) {
        return ResponseEntity.ok(extensionService.getCheckpoints(id));
    }

    /**
     * 手动打检查点。
     *
     * <p>对齐前端 {@code createCheckpoint}（POST /checkpoint）。</p>
     *
     * @param id   DAG ID
     * @param body 含 note 字段
     * @return 200 + 检查点
     */
    @PostMapping("/{id}/checkpoint")
    public ResponseEntity<Map<String, Object>> checkpoint(@PathVariable String id,
                                                          @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(extensionService.createCheckpoint(id, body));
    }

    /**
     * 从检查点恢复执行。
     *
     * <p>对齐前端 {@code resumeFromCheckpoint}（POST /resume）。</p>
     *
     * @param id   DAG ID
     * @param body 含 checkpointId 字段
     * @return 200 + 节点结果映射
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(extensionService.resumeFromCheckpoint(id, body));
    }

    /**
     * 拉取执行历史。
     *
     * <p>对齐前端 {@code getExecutions}。</p>
     *
     * @param id DAG ID
     * @return 200 + 执行历史列表
     */
    @GetMapping("/{id}/executions")
    public ResponseEntity<List<Map<String, Object>>> executions(@PathVariable String id) {
        return ResponseEntity.ok(extensionService.getExecutions(id));
    }

    /**
     * 拉取单次回放轨迹。
     *
     * <p>对齐前端 {@code getReplayTrace}。</p>
     *
     * @param id     DAG ID
     * @param execId 执行 ID
     * @return 200 + 回放轨迹
     */
    @GetMapping("/{id}/replay/{execId}")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable String id,
                                                      @PathVariable String execId) {
        return ResponseEntity.ok(extensionService.getReplayTrace(id, execId));
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