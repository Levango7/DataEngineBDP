package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.ProjectEntity;
import com.levango7.dataenginebdp.encaps.repository.ProjectRepository;
import com.levango7.dataenginebdp.encaps.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目端点（ROADMAP 前后端接线：前端 /projects）。
 *
 * <p>CRUD + 租户隔离（强制取 {@link TenantContext}）；
 * list 返回前端 PagedResult 契约（list/total/page/size）。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectRepository repository;

    /** 创建/更新请求体（对齐前端 CreateProjectParams/UpdateProjectParams）。 */
    public record ProjectRequest(
            @NotBlank String name,
            @NotBlank String domain,
            String description) {
    }

    /** 列表（分页契约，支持 domain 过滤）。 */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String domain,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = requireTenant();
        List<ProjectEntity> all = (domain == null || domain.isBlank())
                ? repository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                : repository.findByTenantIdAndDomainOrderByCreatedAtDesc(tenantId, domain);
        int total = all.size();
        int start = Math.min((page - 1) * size, total);
        int end = Math.min(start + size, total);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", all.subList(start, end).stream().map(this::toView).toList());
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return ResponseEntity.ok(result);
    }

    /** 详情。 */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> get(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId)
                .map(p -> ResponseEntity.ok((Object) toView(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建。 */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody ProjectRequest req) {
        String tenantId = requireTenant();
        ProjectEntity entity = ProjectEntity.builder()
                .name(req.name())
                .domain(req.domain())
                .description(req.description())
                .status("active")
                .datasets(0)
                .jobs(0)
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        ProjectEntity saved = repository.save(entity);
        log.info("创建项目: id={}, name={}, domain={}, tenant={}",
                saved.getId(), saved.getName(), saved.getDomain(), tenantId);
        return ResponseEntity.ok(toView(saved));
    }

    /** 更新。 */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody ProjectRequest req) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            entity.setName(req.name());
            entity.setDomain(req.domain());
            entity.setDescription(req.description());
            entity.setUpdatedAt(Instant.now());
            return ResponseEntity.ok((Object) toView(repository.save(entity)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 删除。 */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            repository.delete(entity);
            log.info("删除项目: id={}, tenant={}", id, tenantId);
            return ResponseEntity.ok(Map.of("deleted", true));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 列出项目数据集。
     *
     * <p>对齐前端 {@code project.ts} 的 {@code listDatasets}。
     * TODO: 接入真实数据集元数据存储，当前返回空列表占位。</p>
     *
     * @param id 项目 ID
     * @return 200 + 数据集列表
     */
    @GetMapping("/{id}/datasets")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> listDatasets(@PathVariable Long id) {
        requireTenant();
        // TODO: 从元数据存储查询项目关联数据集
        return ResponseEntity.ok(List.of());
    }

    /**
     * 列出项目作业。
     *
     * <p>对齐前端 {@code project.ts} 的 {@code listJobs}。
     * TODO: 接入作业存储，当前返回空列表占位。</p>
     *
     * @param id 项目 ID
     * @return 200 + 作业列表
     */
    @GetMapping("/{id}/jobs")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> listJobs(@PathVariable Long id) {
        requireTenant();
        // TODO: 从作业存储查询项目关联作业
        return ResponseEntity.ok(List.of());
    }

    /**
     * 列出项目成员。
     *
     * <p>对齐前端 {@code project.ts} 的 {@code listMembers}。
     * TODO: 接入成员存储，当前返回空列表占位。</p>
     *
     * @param id 项目 ID
     * @return 200 + 成员列表
     */
    @GetMapping("/{id}/members")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> listMembers(@PathVariable Long id) {
        requireTenant();
        // TODO: 从成员存储查询项目关联成员
        return ResponseEntity.ok(List.of());
    }

    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /** 视图映射（id 字符串化对齐前端）。 */
    private Map<String, Object> toView(ProjectEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("name", e.getName());
        m.put("domain", e.getDomain());
        m.put("description", e.getDescription());
        m.put("status", e.getStatus());
        m.put("datasets", e.getDatasets());
        m.put("jobs", e.getJobs());
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }
}
