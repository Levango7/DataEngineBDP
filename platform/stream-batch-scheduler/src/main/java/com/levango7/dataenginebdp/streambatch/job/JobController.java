package com.levango7.dataenginebdp.streambatch.job;

import com.levango7.dataenginebdp.streambatch.model.DagExecutionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 作业端点（ROADMAP 前后端接线：前端 /jobs）。
 *
 * <p>CRUD + 运行/取消（转 DAG 提交）；list 返回前端 PagedResult 契约。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;
    private final JobLogService jobLogService;

    /** 创建/更新请求体（对齐前端 SubmitJobParams）。 */
    public record JobRequest(
            @NotBlank String name,
            @NotBlank String workspaceId,
            @NotBlank String type,
            String config,
            String schedule,
            String owner) {
    }

    /** 列表（分页契约 + workspace 过滤）。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String workspaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<JobEntity> result = jobService.list(workspaceId, page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", result.getContent().stream().map(this::toView).toList());
        body.put("total", result.getTotalElements());
        body.put("page", page);
        body.put("size", size);
        return ResponseEntity.ok(body);
    }

    /** 详情。 */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return jobService.get(id)
                .map(j -> ResponseEntity.ok((Object) toView(j)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody JobRequest req) {
        JobEntity job = JobEntity.builder()
                .name(req.name())
                .workspaceId(req.workspaceId())
                .type(req.type())
                .config(req.config())
                .schedule(req.schedule())
                .owner(req.owner())
                .build();
        return ResponseEntity.ok(toView(jobService.create(job)));
    }

    /** 更新。 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody JobRequest req) {
        JobEntity patch = JobEntity.builder()
                .name(req.name())
                .type(req.type())
                .config(req.config())
                .schedule(req.schedule())
                .owner(req.owner())
                .build();
        return jobService.update(id, patch)
                .map(j -> ResponseEntity.ok((Object) toView(j)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 删除。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (jobService.delete(id)) {
            return ResponseEntity.ok(Map.of("deleted", true));
        }
        return ResponseEntity.notFound().build();
    }

    /** 运行（转 DAG 提交）。 */
    @PostMapping("/{id}/run")
    public ResponseEntity<?> run(@PathVariable Long id) {
        return jobService.run(id)
                .<ResponseEntity<?>>map(result -> ResponseEntity.ok(toRunView(result)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 取消作业。
     *
     * <p>对齐前端 {@code job.ts} 的 {@code cancelJob}。
     * 调用 {@link JobService#cancel} 更新作业状态为 paused，
     * 并将 lastRunStatus 置为 CANCELLED。</p>
     *
     * @param id 作业 ID
     * @return 200 若已取消；404 若不存在
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        if (jobService.cancel(id)) {
            log.info("作业已取消: id={}", id);
            return ResponseEntity.ok(Map.of("cancelled", true));
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 获取作业运行日志。
     *
     * <p>对齐前端 {@code job.ts} 的 {@code getJobLogs}。
     * 从 DAG 运行历史（{@code dag_run} 表）聚合节点结果与错误信息，
     * 真实生产环境可扩展为从 Loki/ES 拉取实时日志。</p>
     *
     * @param id 作业 ID
     * @return 200 + 日志文本
     */
    @GetMapping("/{id}/logs")
    public ResponseEntity<String> logs(@PathVariable Long id) {
        log.info("查询作业日志: id={}", id);
        return ResponseEntity.ok(jobLogService.getJobLogs(id));
    }

    /**
     * 查询作业当前状态。
     *
     * <p>对齐前端 {@code job.ts} 的 {@code getJobStatus}。
     * 从 {@link JobService} 查询作业元数据，并推导进度百分比。</p>
     *
     * @param id 作业 ID
     * @return 200 + 状态对象；404 若不存在
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable Long id) {
        java.util.Map<String, Object> status = jobLogService.getJobStatus(id);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /** 作业视图。 */
    private Map<String, Object> toView(JobEntity j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(j.getId()));
        m.put("name", j.getName());
        m.put("workspaceId", j.getWorkspaceId());
        m.put("type", j.getType());
        m.put("config", j.getConfig());
        m.put("schedule", j.getSchedule());
        m.put("owner", j.getOwner());
        m.put("status", j.getStatus());
        m.put("lastRunStatus", j.getLastRunStatus());
        m.put("createdAt", j.getCreatedAt() == null ? null : j.getCreatedAt().toString());
        m.put("updatedAt", j.getUpdatedAt() == null ? null : j.getUpdatedAt().toString());
        return m;
    }

    /** 运行结果视图。 */
    private Map<String, Object> toRunView(DagExecutionResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dagId", result.getDagId());
        m.put("status", result.getStatus() == null ? "RUNNING" : result.getStatus().name());
        m.put("startTime", result.getStartTime() == null ? null : result.getStartTime().toString());
        return m;
    }
}
