package com.levango7.dataenginebdp.encaps.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.encaps.model.TemplateEntity;
import com.levango7.dataenginebdp.encaps.repository.TemplateRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
 * 行业应用模板端点（ROADMAP 前后端接线：前端 /templates）。
 *
 * <p>meta 字段独立，完整模板 JSON（parameters/dataFlow 等）整体透传。
 * 列表返回 TemplateMeta 视图，详情返回完整 Template。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/templates")
@Tag(name = "模板管理", description = "行业应用模板CRUD")
public class TemplateController {

    private final TemplateRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 创建请求体（对齐前端 CreateTemplateParams）。 */
    public record TemplateRequest(
            @NotBlank String name,
            @NotBlank String industry,
            String version,
            String description,
            String author,
            JsonNode full) {
    }

    /** 列表（分页契约 + industry 过滤，返回 meta 视图）。 */
    @Operation(summary = "查询模板列表", description = "分页查询，支持 industry 过滤，返回 meta 视图")
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String industry,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = requireTenant();
        List<TemplateEntity> all = (industry == null || industry.isBlank())
                ? repository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                : repository.findByTenantIdAndIndustryOrderByCreatedAtDesc(tenantId, industry);
        int total = all.size();
        int start = Math.min((page - 1) * size, total);
        int end = Math.min(start + size, total);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", all.subList(start, end).stream().map(this::toMeta).toList());
        body.put("total", total);
        body.put("page", page);
        body.put("size", size);
        return ResponseEntity.ok(body);
    }

    /** 详情（完整 Template 视图）。 */
    @Operation(summary = "查询模板详情", description = "按 ID 获取完整 Template 视图（meta + fullJson 展开）")
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> get(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId)
                .map(t -> ResponseEntity.ok((Object) toFull(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建。 */
    @Operation(summary = "创建模板", description = "创建行业应用模板（新版本，status=dev）")
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody TemplateRequest req) {
        String tenantId = requireTenant();
        TemplateEntity entity = TemplateEntity.builder()
                .name(req.name())
                .industry(req.industry())
                .version(req.version() != null ? req.version() : "1.0.0")
                .description(req.description())
                .author(req.author())
                .status("dev")
                .installCount(0)
                .fullJson(req.full() == null ? "{}" : req.full().toString())
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        TemplateEntity saved = repository.save(entity);
        log.info("创建模板: id={}, name={}, industry={}, tenant={}",
                saved.getId(), saved.getName(), saved.getIndustry(), tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toMeta(saved));
    }

    /** 更新。 */
    @Operation(summary = "更新模板", description = "按 ID 更新模板（name/industry/version/description/author/full）")
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody TemplateRequest req) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            entity.setName(req.name());
            entity.setIndustry(req.industry());
            if (req.version() != null) {
                entity.setVersion(req.version());
            }
            entity.setDescription(req.description());
            entity.setAuthor(req.author());
            if (req.full() != null) {
                entity.setFullJson(req.full().toString());
            }
            entity.setUpdatedAt(Instant.now());
            return ResponseEntity.ok((Object) toMeta(repository.save(entity)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 删除。 */
    @Operation(summary = "删除模板", description = "按 ID 删除模板（租户隔离）")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            repository.delete(entity);
            log.info("删除模板: id={}, tenant={}", id, tenantId);
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

    /** TemplateMeta 视图。 */
    private Map<String, Object> toMeta(TemplateEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("name", e.getName());
        m.put("industry", e.getIndustry());
        m.put("version", e.getVersion());
        m.put("appVersion", e.getVersion());
        m.put("description", e.getDescription());
        m.put("author", e.getAuthor());
        m.put("status", e.getStatus());
        m.put("installCount", e.getInstallCount());
        m.put("rating", 0);
        m.put("tags", List.of());
        m.put("icon", "");
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        return m;
    }

    /** 完整 Template 视图（meta + fullJson 展开）。 */
    private Map<String, Object> toFull(TemplateEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("meta", toMeta(e));
        try {
            JsonNode full = objectMapper.readTree(e.getFullJson());
            m.put("parameters", full.path("parameters"));
            m.put("dataFlow", full.path("dataFlow"));
            m.put("computeLogic", full.path("computeLogic"));
            m.put("visualization", full.path("visualization"));
            m.put("readme", full.path("readme").asText(""));
            m.put("schema", full.path("schema"));
        } catch (Exception ex) {
            m.put("parameters", List.of());
            m.put("dataFlow", Map.of());
            m.put("computeLogic", Map.of());
            m.put("visualization", Map.of());
            m.put("readme", "");
            m.put("schema", Map.of());
        }
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        return m;
    }
}
