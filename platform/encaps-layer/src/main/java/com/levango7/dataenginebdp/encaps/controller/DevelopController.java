package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据开发端点（ROADMAP 前后端接线：前端 /develop）。
 *
 * <p>提供 Web IDE 文件树、文件内容读取、作业运行、调度提交与任务 DAG 查询。
 * 统一前缀：{@code /api/v1/develop}</p>
 *
 * <ul>
 *   <li>GET  /files          — 文件树列表</li>
 *   <li>GET  /files/content  — 读取文件内容（参数：path）</li>
 *   <li>POST /run            — 运行作业</li>
 *   <li>POST /schedule       — 提交调度</li>
 *   <li>GET  /dag            — 获取任务 DAG（参数：filePath）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/develop")
public class DevelopController {

    /** 文件树（默认返回根目录占位结构）。 */
    @GetMapping("/files")
    public ResponseEntity<List<Map<String, Object>>> getFileTree() {
        // TODO: 接入真实文件系统（如 Git 仓库 / S3 / PVC 挂载目录）
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", "root");
        root.put("name", "workspace");
        root.put("type", "folder");
        root.put("children", List.of(
                Map.of("id", "src", "name", "src", "type", "folder",
                        "children", List.of(
                                Map.of("id", "etl", "name", "etl.py",
                                        "type", "file", "path", "src/etl.py"))),
                Map.of("id", "sql", "name", "sql", "type", "folder",
                        "children", List.of(
                                Map.of("id", "dws", "name", "dws.sql",
                                        "type", "file", "path", "sql/dws.sql")))
        ));
        return ResponseEntity.ok(List.of(root));
    }

    /** 读取文件内容。 */
    @GetMapping("/files/content")
    public ResponseEntity<String> readFile(@RequestParam String path) {
        // TODO: 接入真实文件系统读取
        log.info("读取文件内容: path={}, tenant={}", path, TenantContext.getTenantId());
        String placeholder = "# 文件内容占位\n# path=" + path + "\n"
                + "# TODO: 接入真实文件系统后返回实际内容\n";
        return ResponseEntity.ok(placeholder);
    }

    /** 运行作业请求体（对齐前端 RunParams）。 */
    public record RunRequest(
            String filePath,
            String engine,
            Integer cpu,
            Integer memory,
            Integer parallelism,
            String schedule) {
    }

    /** 运行作业。 */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runJob(@RequestBody RunRequest req) {
        // TODO: 转交 stream-batch JobService 真实提交
        log.info("运行作业: file={}, engine={}, tenant={}",
                req.filePath(), req.engine(), TenantContext.getTenantId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", "run-" + System.currentTimeMillis());
        result.put("status", "running");
        result.put("logs", List.of(
                Map.of("level", "info", "text", "提交作业: " + req.filePath(),
                        "timestamp", Instant.now().toString()),
                Map.of("level", "ok", "text", "引擎: " + req.engine(),
                        "timestamp", Instant.now().toString())
        ));
        return ResponseEntity.ok(result);
    }

    /** 提交调度请求体。 */
    public record ScheduleRequest(
            String filePath,
            String schedule,
            String engine) {
    }

    /** 提交调度。 */
    @PostMapping("/schedule")
    public ResponseEntity<Void> submitSchedule(@RequestBody ScheduleRequest req) {
        // TODO: 接入调度服务（stream-batch SchedulerController）
        log.info("提交调度: file={}, schedule={}, engine={}, tenant={}",
                req.filePath(), req.schedule(), req.engine(), TenantContext.getTenantId());
        return ResponseEntity.ok().build();
    }

    /** 获取任务 DAG。 */
    @GetMapping("/dag")
    public ResponseEntity<Map<String, Object>> getTaskDag(@RequestParam String filePath) {
        // TODO: 解析 SQL/Python 生成真实 DAG
        log.info("获取任务 DAG: file={}, tenant={}", filePath, TenantContext.getTenantId());
        Map<String, Object> dag = new LinkedHashMap<>();
        dag.put("dagId", "dag-" + filePath.hashCode());
        dag.put("nodes", List.of(
                Map.of("id", "extract", "name", "Extract"),
                Map.of("id", "transform", "name", "Transform"),
                Map.of("id", "load", "name", "Load")
        ));
        dag.put("edges", List.of(
                Map.of("source", "extract", "target", "transform"),
                Map.of("source", "transform", "target", "load")
        ));
        return ResponseEntity.ok(dag);
    }
}