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

    /* ================================================================ */
    /* DAG 可视化扩展端点（对齐前端 orchestrator-viz.ts）                */
    /* ================================================================ */

    /**
     * 拉取 Agent 思考链。
     *
     * <p>对齐前端 {@code getThoughtChain}。TODO: 接入 Agent 推理记录存储。</p>
     *
     * @param id DAG ID
     * @return 200 + 思考步骤列表
     */
    @GetMapping("/{id}/thoughts")
    public ResponseEntity<List<Map<String, Object>>> thoughts(@PathVariable String id) {
        // TODO: 从 Agent 推理记录存储查询
        return ResponseEntity.ok(List.of());
    }

    /**
     * 拉取工具调用记录。
     *
     * <p>对齐前端 {@code getToolCalls}。TODO: 接入工具调用记录存储。</p>
     *
     * @param id DAG ID
     * @return 200 + 工具调用记录列表
     */
    @GetMapping("/{id}/tool-calls")
    public ResponseEntity<List<Map<String, Object>>> toolCalls(@PathVariable String id) {
        // TODO: 从工具调用记录存储查询
        return ResponseEntity.ok(List.of());
    }

    /**
     * 查询待处理人工介入请求。
     *
     * <p>对齐前端 {@code getInterventions}。TODO: 接入人工介入存储。</p>
     *
     * @param id DAG ID
     * @return 200 + 介入请求列表
     */
    @GetMapping("/{id}/intervention")
    public ResponseEntity<List<Map<String, Object>>> intervention(@PathVariable String id) {
        // TODO: 从人工介入存储查询待审批节点
        return ResponseEntity.ok(List.of());
    }

    /**
     * 提交人工审批。
     *
     * <p>对齐前端 {@code submitIntervention}（POST /intervene）。TODO: 转交审批引擎。</p>
     *
     * @param id      DAG ID
     * @param payload 审批载荷
     * @return 200 + 更新后的介入请求
     */
    @PostMapping("/{id}/intervene")
    public ResponseEntity<Map<String, Object>> intervene(@PathVariable String id,
                                                         @RequestBody Map<String, Object> payload) {
        // TODO: 转交审批引擎处理
        Map<String, Object> result = new java.util.LinkedHashMap<>(payload);
        result.put("dagId", id);
        result.put("status", payload.get("decision"));
        return ResponseEntity.ok(result);
    }

    /**
     * 拉取检查点列表。
     *
     * <p>对齐前端 {@code getCheckpoints}。TODO: 接入检查点存储。</p>
     *
     * @param id DAG ID
     * @return 200 + 检查点列表
     */
    @GetMapping("/{id}/checkpoints")
    public ResponseEntity<List<Map<String, Object>>> checkpoints(@PathVariable String id) {
        // TODO: 从检查点存储查询
        return ResponseEntity.ok(List.of());
    }

    /**
     * 手动打检查点。
     *
     * <p>对齐前端 {@code createCheckpoint}（POST /checkpoint）。TODO: 接入检查点存储。</p>
     *
     * @param id   DAG ID
     * @param body 含 note 字段
     * @return 200 + 检查点
     */
    @PostMapping("/{id}/checkpoint")
    public ResponseEntity<Map<String, Object>> checkpoint(@PathVariable String id,
                                                          @RequestBody Map<String, Object> body) {
        // TODO: 创建检查点并持久化
        Map<String, Object> cp = new java.util.LinkedHashMap<>();
        cp.put("id", "cp-" + System.currentTimeMillis());
        cp.put("dagId", id);
        cp.put("kind", "MANUAL");
        cp.put("note", body.get("note"));
        return ResponseEntity.ok(cp);
    }

    /**
     * 从检查点恢复执行。
     *
     * <p>对齐前端 {@code resumeFromCheckpoint}（POST /resume）。TODO: 接入断点续跑。</p>
     *
     * @param id   DAG ID
     * @param body 含 checkpointId 字段
     * @return 200 + 节点结果映射
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        // TODO: 从检查点恢复 DAG 执行
        return ResponseEntity.ok(Map.of("dagId", id, "resumed", true,
                "checkpointId", body.getOrDefault("checkpointId", "")));
    }

    /**
     * 拉取执行历史。
     *
     * <p>对齐前端 {@code getExecutions}。TODO: 接入执行历史存储。</p>
     *
     * @param id DAG ID
     * @return 200 + 执行历史列表
     */
    @GetMapping("/{id}/executions")
    public ResponseEntity<List<Map<String, Object>>> executions(@PathVariable String id) {
        // TODO: 从执行历史存储查询
        return ResponseEntity.ok(List.of());
    }

    /**
     * 拉取单次回放轨迹。
     *
     * <p>对齐前端 {@code getReplayTrace}。TODO: 接入回放事件流存储。</p>
     *
     * @param id     DAG ID
     * @param execId 执行 ID
     * @return 200 + 回放轨迹
     */
    @GetMapping("/{id}/replay/{execId}")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable String id,
                                                      @PathVariable String execId) {
        // TODO: 从事件流存储查询回放轨迹
        Map<String, Object> trace = new java.util.LinkedHashMap<>();
        trace.put("execId", execId);
        trace.put("dagId", id);
        trace.put("events", List.of());
        return ResponseEntity.ok(trace);
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