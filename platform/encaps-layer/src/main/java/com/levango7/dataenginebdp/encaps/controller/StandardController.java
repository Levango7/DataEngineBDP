package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.StandardEntity;
import com.levango7.dataenginebdp.encaps.repository.StandardRepository;
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
 * 主数据标准端点（ROADMAP 前后端接线：前端 /standards）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/standards")
public class StandardController {

    private final StandardRepository repository;

    /** 创建/更新请求体（对齐前端 CreateStandardParams）。 */
    public record StandardRequest(
            @NotBlank String name,
            @NotBlank String type,
            String rule,
            String description) {
    }

    /** 列表（分页契约 + type 过滤）。 */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = requireTenant();
        List<StandardEntity> all = (type == null || type.isBlank())
                ? repository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                : repository.findByTenantIdAndTypeOrderByCreatedAtDesc(tenantId, type);
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
                .map(s -> ResponseEntity.ok((Object) toView(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建。 */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody StandardRequest req) {
        String tenantId = requireTenant();
        StandardEntity entity = StandardEntity.builder()
                .name(req.name())
                .type(req.type())
                .rule(req.rule())
                .description(req.description())
                .status("active")
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        StandardEntity saved = repository.save(entity);
        log.info("创建标准: id={}, name={}, type={}, tenant={}",
                saved.getId(), saved.getName(), saved.getType(), tenantId);
        return ResponseEntity.ok(toView(saved));
    }

    /** 更新。 */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody StandardRequest req) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            entity.setName(req.name());
            entity.setType(req.type());
            entity.setRule(req.rule());
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
            log.info("删除标准: id={}, tenant={}", id, tenantId);
            return ResponseEntity.ok(Map.of("deleted", true));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 查询落标率统计。
     *
     * <p>对齐前端 {@code standard.ts} 的 {@code getSummary}。
     * TODO: 接入真实落标率统计，当前返回占位统计。</p>
     *
     * @return 200 + 落标率统计
     */
    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> summary() {
        String tenantId = requireTenant();
        List<StandardEntity> all = repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        int total = all.size();
        // TODO: 计算真实已落标数（需关联资产元数据）
        int applied = 0;
        double applyRate = total == 0 ? 0.0 : (applied * 100.0 / total);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("applied", applied);
        summary.put("applyRate", applyRate);
        return ResponseEntity.ok(summary);
    }

    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /** 视图映射。 */
    private Map<String, Object> toView(StandardEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("name", e.getName());
        m.put("type", e.getType());
        m.put("rule", e.getRule());
        m.put("description", e.getDescription());
        m.put("status", e.getStatus());
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }
}
