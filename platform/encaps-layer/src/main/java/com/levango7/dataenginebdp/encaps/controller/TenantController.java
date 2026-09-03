package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.Tenant;
import com.levango7.dataenginebdp.encaps.repository.TenantRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户管理端点（/api/v1/tenants）。
 *
 * <p>提供租户的 CRUD 操作，供封装层集成测试与管理控制台使用。
 * 与其他 Controller 不同，租户是全局实体，不做租户隔离过滤。</p>
 *
 * <p>认证由 {@code JwtAuthFilter} 统一拦截 {@code /api/v1/**} 路径，
 * 无需在 Controller 内显式校验。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.tenant.controller.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/tenants")
@Tag(name = "租户管理", description = "租户的创建、查询、更新与删除")
public class TenantController {

    private final TenantRepository repository;

    /** 创建/更新请求体。 */
    public record TenantRequest(
            @NotBlank String name,
            String displayName,
            String namespace,
            String quotaProfile,
            String status) {
    }

    /** 列表。 */
    @Operation(summary = "查询租户列表", description = "返回所有租户列表")
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Tenant>> list() {
        return ResponseEntity.ok(repository.findAll());
    }

    /** 详情。 */
    @Operation(summary = "查询租户详情", description = "按 ID 获取租户详情")
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<Tenant> get(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建。 */
    @Operation(summary = "创建租户", description = "创建租户，返回 201")
    @PostMapping
    @Transactional
    public ResponseEntity<Tenant> create(@Valid @RequestBody TenantRequest req) {
        Tenant entity = new Tenant();
        entity.setName(req.name());
        entity.setDisplayName(req.displayName());
        entity.setNamespace(req.namespace());
        entity.setQuotaProfile(req.quotaProfile());
        entity.setStatus(req.status() != null ? req.status() : "ACTIVE");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        Tenant saved = repository.save(entity);
        log.info("创建租户: id={}, name={}, namespace={}", saved.getId(), saved.getName(), saved.getNamespace());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** 更新。 */
    @Operation(summary = "更新租户", description = "按 ID 更新租户")
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Tenant> update(@PathVariable Long id, @Valid @RequestBody TenantRequest req) {
        return repository.findById(id).map(entity -> {
            entity.setName(req.name());
            entity.setDisplayName(req.displayName());
            entity.setNamespace(req.namespace());
            entity.setQuotaProfile(req.quotaProfile());
            if (req.status() != null) {
                entity.setStatus(req.status());
            }
            entity.setUpdatedAt(LocalDateTime.now());
            return ResponseEntity.ok(repository.save(entity));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 删除。 */
    @Operation(summary = "删除租户", description = "按 ID 删除租户，返回 204")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (repository.existsById(id)) {
            repository.deleteById(id);
            log.info("删除租户: id={}", id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}