package com.levango7.dataenginebdp.encaps.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.encaps.model.ApiDefinitionEntity;
import com.levango7.dataenginebdp.encaps.repository.ApiDefinitionRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
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
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API 目录端点（ROADMAP 前后端接线：前端 /apis）。
 */
@Slf4j
@RestController
@Tag(name = "封装网关-API目录", description = "API定义CRUD与目录管理")
@RequiredArgsConstructor
@RequestMapping("/api/v1/apis")
public class ApiCatalogController {

    private final ApiDefinitionRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 创建/更新请求体。 */
    public record ApiRequest(
            @NotBlank String name,
            @NotBlank String version,
            String category,
            @NotBlank String method,
            @NotBlank String path,
            String status,
            JsonNode full) {
    }

    /** 列表（分页契约 + category 过滤）。 */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = requireTenant();
        List<ApiDefinitionEntity> all = (category == null || category.isBlank())
                ? repository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                : repository.findByTenantIdAndCategoryOrderByCreatedAtDesc(tenantId, category);
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
                .map(a -> ResponseEntity.ok((Object) toView(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建。 */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody ApiRequest req) {
        String tenantId = requireTenant();
        ApiDefinitionEntity entity = ApiDefinitionEntity.builder()
                .name(req.name())
                .version(req.version())
                .category(req.category())
                .method(req.method().toUpperCase())
                .path(req.path())
                .status(req.status() != null ? req.status() : "draft")
                .fullJson(req.full() == null ? "{}" : req.full().toString())
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        ApiDefinitionEntity saved = repository.save(entity);
        log.info("创建 API: id={}, name={}, path={}, tenant={}",
                saved.getId(), saved.getName(), saved.getPath(), tenantId);
        return ResponseEntity.ok(toView(saved));
    }

    /** 更新。 */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody ApiRequest req) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            entity.setName(req.name());
            entity.setVersion(req.version());
            entity.setCategory(req.category());
            entity.setMethod(req.method().toUpperCase());
            entity.setPath(req.path());
            if (req.status() != null) {
                entity.setStatus(req.status());
            }
            if (req.full() != null) {
                entity.setFullJson(req.full().toString());
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
            log.info("删除 API: id={}, tenant={}", id, tenantId);
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

    /** 视图映射（核心字段 + 完整契约展开）。 */
    private Map<String, Object> toView(ApiDefinitionEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("name", e.getName());
        m.put("version", e.getVersion());
        m.put("category", e.getCategory());
        m.put("method", e.getMethod());
        m.put("path", e.getPath());
        m.put("status", e.getStatus());
        m.put("tags", List.of());
        m.put("callCount", 0);
        m.put("errorCount", 0);
        try {
            JsonNode full = objectMapper.readTree(e.getFullJson());
            full.fields().forEachRemaining(entry ->
                    m.putIfAbsent(entry.getKey(), entry.getValue()));
        } catch (Exception ignored) {
            // 无 fullJson 时跳过
        }
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }
}
