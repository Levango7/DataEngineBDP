package com.levango7.dataenginebdp.encaps.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.encaps.model.AssetEntity;
import com.levango7.dataenginebdp.encaps.repository.AssetRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据资产治理端点（ROADMAP 前后端接线：前端 /governance/assets）。
 *
 * <p>路径前缀由 {@code /api/v1/assets} 调整为 {@code /api/v1/governance/assets}，
 * 以避免与 asset-exchange（Python，{@code /api/v1/assets}）路径冲突。
 * 前端 governance.ts 已同步使用 {@code /governance/assets} 前缀。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/governance/assets")
public class AssetController {

    private final AssetRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 资产质量检查结果内存存储：assetId -> 质量检查项列表。 */
    private static final Map<String, List<Map<String, Object>>> QUALITY_RESULTS = new ConcurrentHashMap<>();

    /** 资产权限内存存储：assetId -> 权限列表。 */
    private static final Map<String, List<Map<String, Object>>> ASSET_PERMISSIONS = new ConcurrentHashMap<>();

    /** 资产权限审批内存存储：tenantId -> 审批记录列表。 */
    private static final Map<String, List<Map<String, Object>>> ASSET_APPROVALS = new ConcurrentHashMap<>();

    /** 创建/更新请求体。 */
    public record AssetRequest(
            @NotBlank String name,
            @NotBlank String type,
            @NotBlank String owner,
            String description,
            Integer qualityScore,
            String securityLevel,
            JsonNode full) {
    }

    /** 列表（分页契约 + type 过滤）。 */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = requireTenant();
        List<AssetEntity> all = (type == null || type.isBlank())
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
                .map(a -> ResponseEntity.ok((Object) toView(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建。 */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody AssetRequest req) {
        String tenantId = requireTenant();
        AssetEntity entity = AssetEntity.builder()
                .name(req.name())
                .type(req.type())
                .owner(req.owner())
                .description(req.description())
                .status("published")
                .qualityScore(req.qualityScore() != null ? req.qualityScore() : 0)
                .securityLevel(req.securityLevel() != null ? req.securityLevel() : "L2")
                .fullJson(req.full() == null ? "{}" : req.full().toString())
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        AssetEntity saved = repository.save(entity);
        log.info("创建资产: id={}, name={}, type={}, tenant={}",
                saved.getId(), saved.getName(), saved.getType(), tenantId);
        return ResponseEntity.ok(toView(saved));
    }

    /** 更新。 */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody AssetRequest req) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            entity.setName(req.name());
            entity.setType(req.type());
            entity.setOwner(req.owner());
            entity.setDescription(req.description());
            if (req.qualityScore() != null) {
                entity.setQualityScore(req.qualityScore());
            }
            if (req.securityLevel() != null) {
                entity.setSecurityLevel(req.securityLevel());
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
            log.info("删除资产: id={}, tenant={}", id, tenantId);
            return ResponseEntity.ok(Map.of("deleted", true));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 获取资产 Schema。
     *
     * <p>对齐前端 {@code governance.ts} 的 {@code getAssetSchema}。
     * 通过反射 {@link AssetEntity} 获取字段名与类型作为 Schema 字段定义。</p>
     *
     * @param id 资产 ID
     * @return 200 + 资产 Schema
     */
    @GetMapping("/{id}/schema")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getSchema(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId)
                .<ResponseEntity<?>>map(a -> ResponseEntity.ok(Map.of(
                        "assetId", String.valueOf(a.getId()),
                        "fields", getAssetFields())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 通过反射获取 AssetEntity 字段定义（字段名 + 类型）。 */
    private List<Map<String, Object>> getAssetFields() {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (java.lang.reflect.Field f : AssetEntity.class.getDeclaredFields()) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", f.getName());
            field.put("type", f.getType().getSimpleName());
            fields.add(field);
        }
        return fields;
    }

    /**
     * 获取资产质量检查结果。
     *
     * <p>对齐前端 {@code governance.ts} 的 {@code getAssetQuality}。
     * 从内存质量结果存储查询（按资产 ID 隔离）。</p>
     *
     * @param id 资产 ID
     * @return 200 + 质量检查项列表
     */
    @GetMapping("/{id}/quality")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getQuality(@PathVariable Long id) {
        requireTenant();
        List<Map<String, Object>> results = QUALITY_RESULTS.getOrDefault(String.valueOf(id), List.of());
        return ResponseEntity.ok(new ArrayList<>(results));
    }

    /**
     * 获取资产权限列表。
     *
     * <p>对齐前端 {@code governance.ts} 的 {@code getAssetPermissions}。
     * 从内存权限存储查询（按资产 ID 隔离）。</p>
     *
     * @param id 资产 ID
     * @return 200 + 权限列表
     */
    @GetMapping("/{id}/permissions")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getPermissions(@PathVariable Long id) {
        requireTenant();
        List<Map<String, Object>> perms = ASSET_PERMISSIONS.getOrDefault(String.valueOf(id), List.of());
        return ResponseEntity.ok(new ArrayList<>(perms));
    }

    /**
     * 申请资产读/写权限。
     *
     * <p>对齐前端 {@code governance.ts} 的 {@code applyAssetPermission}。
     * 创建审批记录到内存审批存储，并记录操作日志。</p>
     *
     * @param id         资产 ID
     * @param permission 权限类型（read/write）
     * @return 200
     */
    @PostMapping("/{id}/apply-permission")
    public ResponseEntity<Void> applyPermission(@PathVariable Long id,
                                                 @RequestBody Map<String, String> permission) {
        String tenantId = requireTenant();
        String perm = permission.getOrDefault("permission", "read");
        // 创建审批记录到内存存储
        List<Map<String, Object>> approvals = ASSET_APPROVALS.computeIfAbsent(tenantId, k -> new ArrayList<>());
        synchronized (approvals) {
            Map<String, Object> approval = new LinkedHashMap<>();
            approval.put("id", UUID.randomUUID().toString());
            approval.put("assetId", String.valueOf(id));
            approval.put("permission", perm);
            approval.put("status", "pending");
            approval.put("tenantId", tenantId);
            approval.put("createdAt", Instant.now().toString());
            approvals.add(0, approval);
        }
        log.info("申请资产权限: id={}, permission={}, tenant={}", id, perm, tenantId);
        return ResponseEntity.ok().build();
    }

    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /** 视图映射。 */
    private Map<String, Object> toView(AssetEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("name", e.getName());
        m.put("type", e.getType());
        m.put("owner", e.getOwner());
        m.put("description", e.getDescription());
        m.put("status", e.getStatus());
        m.put("qualityScore", e.getQualityScore());
        m.put("securityLevel", e.getSecurityLevel());
        m.put("subscriberCount", 0);
        m.put("tags", Map.of());
        m.put("updateFrequency", "");
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
