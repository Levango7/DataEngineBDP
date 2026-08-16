package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.DevelopFileService;
import com.levango7.dataenginebdp.encaps.service.DevelopJobService;
import com.levango7.dataenginebdp.encaps.service.DevelopScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据开发端点（ROADMAP 前后端接线：前端 /develop）。
 *
 * <p>提供 Web IDE 文件树、文件内容读取、作业运行、调度提交与任务 DAG 查询。
 * 统一前缀：{@code /api/v1/develop}</p>
 *
 * <ul>
 *   <li>GET  /files          — 文件树列表（扫描工作空间目录）</li>
 *   <li>GET  /files/content  — 读取文件内容（参数：path）</li>
 *   <li>POST /run            — 运行作业（转交 stream-batch-scheduler）</li>
 *   <li>POST /schedule       — 提交调度（落库 develop_schedule）</li>
 *   <li>GET  /dag            — 获取任务 DAG（按数据分层推导依赖）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/develop")
public class DevelopController {

    private final DevelopFileService fileService;
    private final DevelopJobService jobService;
    private final DevelopScheduleService scheduleService;

    /** 文件树（扫描工作空间目录，返回真实文件结构）。 */
    @GetMapping("/files")
    public ResponseEntity<List<Map<String, Object>>> getFileTree() {
        log.info("获取文件树: tenant={}", TenantContext.getTenantId());
        return ResponseEntity.ok(fileService.getFileTree());
    }

    /** 读取文件内容（参数：path，相对工作空间根）。 */
    @GetMapping("/files/content")
    public ResponseEntity<String> readFile(@RequestParam String path) {
        log.info("读取文件内容: path={}, tenant={}", path, TenantContext.getTenantId());
        try {
            return ResponseEntity.ok(fileService.readFile(path));
        } catch (IllegalArgumentException e) {
            log.warn("读取文件被拒绝: path={}, reason={}", path, e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            log.error("读取文件失败: path={}", path, e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
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

    /** 运行作业：转交 stream-batch-scheduler 真实提交。 */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runJob(@RequestBody RunRequest req) {
        String tenantId = TenantContext.getTenantId();
        log.info("运行作业: file={}, engine={}, tenant={}", req.filePath(), req.engine(), tenantId);
        Map<String, Object> result = jobService.submitJob(
                req.filePath(), req.engine(), req.cpu(), req.memory(), req.parallelism(), tenantId);
        return ResponseEntity.ok(result);
    }

    /** 提交调度请求体。 */
    public record ScheduleRequest(
            String filePath,
            String schedule,
            String engine) {
    }

    /** 提交调度：落库 develop_schedule 表。 */
    @PostMapping("/schedule")
    public ResponseEntity<Void> submitSchedule(@RequestBody ScheduleRequest req) {
        String tenantId = TenantContext.getTenantId();
        log.info("提交调度: file={}, schedule={}, engine={}, tenant={}",
                req.filePath(), req.schedule(), req.engine(), tenantId);
        try {
            scheduleService.createSchedule(req.filePath(), req.schedule(), req.engine(), tenantId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("提交调度失败: file={}", req.filePath(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 获取任务 DAG：按数据分层（ods/dwd/dws/ads）推导依赖关系。 */
    @GetMapping("/dag")
    public ResponseEntity<Map<String, Object>> getTaskDag(@RequestParam String filePath) {
        String tenantId = TenantContext.getTenantId();
        log.info("获取任务 DAG: file={}, tenant={}", filePath, tenantId);
        return ResponseEntity.ok(scheduleService.resolveDag(filePath, tenantId));
    }
}
