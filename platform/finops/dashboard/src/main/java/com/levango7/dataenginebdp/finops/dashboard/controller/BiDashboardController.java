package com.levango7.dataenginebdp.finops.dashboard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.finops.dashboard.model.DashboardEntity;
import com.levango7.dataenginebdp.finops.dashboard.repository.DashboardRepository;
import com.levango7.dataenginebdp.finops.dashboard.security.TenantContext;
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
 * BI 看板端点（ROADMAP 前后端接线：前端 /dashboards）。
 *
 * <p>独立于成本看板（{@link DashboardController}）；
 * CRUD + panels JSON 透传（前端 Panel 数组）；list 返回 PagedResult 契约。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/dashboards")
public class BiDashboardController {

    private final DashboardRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 创建/更新请求体（对齐前端 CreateDashboardParams）。 */
    public record DashboardRequest(
            @NotBlank String name,
            String description,
            JsonNode panels) {
    }

    /** 列表（分页契约 + 租户隔离）。 */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = requireTenant();
        List<DashboardEntity> all = repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        int total = all.size();
        int start = Math.min((page - 1) * size, total);
        int end = Math.min(start + size, total);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", all.subList(start, end).stream().map(this::toView).toList());
        body.put("total", total);
        body.put("page", page);
        body.put("size", size);
        return ResponseEntity.ok(body);
    }

    /** 详情。 */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> get(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId)
                .map(d -> ResponseEntity.ok((Object) toView(d)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建。 */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody DashboardRequest req) {
        String tenantId = requireTenant();
        DashboardEntity entity = DashboardEntity.builder()
                .name(req.name())
                .description(req.description())
                .panelsJson(req.panels() == null ? "[]" : req.panels().toString())
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        DashboardEntity saved = repository.save(entity);
        log.info("创建看板: id={}, name={}, tenant={}", saved.getId(), saved.getName(), tenantId);
        return ResponseEntity.ok(toView(saved));
    }

    /** 更新。 */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody DashboardRequest req) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            entity.setName(req.name());
            entity.setDescription(req.description());
            if (req.panels() != null) {
                entity.setPanelsJson(req.panels().toString());
            }
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
            log.info("删除看板: id={}, tenant={}", id, tenantId);
            return ResponseEntity.ok(Map.of("deleted", true));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /** 视图映射：panels 反序列化为数组返回。 */
    private Map<String, Object> toView(DashboardEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("name", e.getName());
        m.put("description", e.getDescription());
        try {
            m.put("panels", e.getPanelsJson() == null
                    ? List.of() : objectMapper.readTree(e.getPanelsJson()));
        } catch (Exception ex) {
            m.put("panels", List.of());
        }
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }
}
