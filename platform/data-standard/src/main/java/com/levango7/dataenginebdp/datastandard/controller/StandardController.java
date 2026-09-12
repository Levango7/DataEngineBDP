package com.levango7.dataenginebdp.datastandard.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.datastandard.model.dto.CreateStandardDTO;
import com.levango7.dataenginebdp.datastandard.model.dto.StandardQueryDTO;
import com.levango7.dataenginebdp.datastandard.model.dto.UpdateStandardDTO;
import com.levango7.dataenginebdp.datastandard.model.entity.Standard;
import com.levango7.dataenginebdp.datastandard.service.StandardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 数据标准 REST 控制器。
 *
 * <p>提供数据标准的 CRUD 与分页查询端点。
 * 多租户隔离：所有操作通过 {@link #requireTenant()} 从 {@link TenantContext}
 * 取得租户 ID，缺失则 fail-closed（禁止匿名跨租户访问）。</p>
 */
@Slf4j
@RestController
@Tag(name = "数据标准-标准管理", description = "数据标准CRUD与分页查询")
@RequiredArgsConstructor
@RequestMapping("/api/v1/standards")
public class StandardController {

    private final StandardService standardService;

    /** 创建数据标准 */
    @Operation(summary = "创建数据标准")
    @PostMapping
    public ResponseEntity<Standard> create(@Valid @RequestBody CreateStandardDTO dto) {
        String tenantId = requireTenant();
        Standard created = standardService.create(dto, tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 分页查询数据标准 */
    @Operation(summary = "分页查询数据标准")
    @GetMapping
    public ResponseEntity<Page<Standard>> query(@Valid StandardQueryDTO query) {
        String tenantId = requireTenant();
        return ResponseEntity.ok(standardService.query(query, tenantId));
    }

    /** 获取单个数据标准 */
    @Operation(summary = "获取单个数据标准")
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        String tenantId = requireTenant();
        Standard standard = standardService.getById(id, tenantId);
        if (standard == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "standard_not_found",
                            "message", "Standard " + id + " not found"));
        }
        return ResponseEntity.ok(standard);
    }

    /** 更新数据标准 */
    @Operation(summary = "更新数据标准")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @Valid @RequestBody UpdateStandardDTO dto) {
        String tenantId = requireTenant();
        Standard updated = standardService.update(id, dto, tenantId);
        if (updated == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "standard_not_found",
                            "message", "Standard " + id + " not found"));
        }
        return ResponseEntity.ok(updated);
    }

    /** 删除数据标准 */
    @Operation(summary = "删除数据标准")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        String tenantId = requireTenant();
        boolean removed = standardService.delete(id, tenantId);
        if (!removed) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "standard_not_found",
                            "message", "Standard " + id + " not found"));
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 从 {@link TenantContext} 获取租户 ID，缺失则 fail-closed。
     *
     * @return 当前请求的租户 ID
     * @throws IllegalStateException 若 TenantContext 未设置租户 ID
     */
    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }
}