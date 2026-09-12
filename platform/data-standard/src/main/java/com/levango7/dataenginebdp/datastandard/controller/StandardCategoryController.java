package com.levango7.dataenginebdp.datastandard.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.datastandard.model.entity.StandardCategory;
import com.levango7.dataenginebdp.datastandard.service.StandardCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据标准分类 REST 控制器。
 *
 * <p>提供标准分类的列表与创建端点。
 * 多租户隔离：所有操作通过 {@link #requireTenant()} 从 {@link TenantContext}
 * 取得租户 ID，缺失则 fail-closed。</p>
 */
@Slf4j
@RestController
@Tag(name = "数据标准-分类管理", description = "标准分类列表与创建")
@RequiredArgsConstructor
@RequestMapping("/api/v1/standard-categories")
public class StandardCategoryController {

    private final StandardCategoryService categoryService;

    /** 列出所有标准分类 */
    @Operation(summary = "列出所有标准分类")
    @GetMapping
    public ResponseEntity<List<StandardCategory>> listAll() {
        String tenantId = requireTenant();
        return ResponseEntity.ok(categoryService.listAll(tenantId));
    }

    /** 创建标准分类 */
    @Operation(summary = "创建标准分类")
    @PostMapping
    public ResponseEntity<StandardCategory> create(@RequestBody StandardCategory category) {
        String tenantId = requireTenant();
        StandardCategory created = categoryService.create(category, tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
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