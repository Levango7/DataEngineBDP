package com.shuqing.bigdata.encaps.controller;

import com.shuqing.bigdata.encaps.model.Tenant;
import com.shuqing.bigdata.encaps.service.TenantService;
import jakarta.validation.Valid;
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

import java.util.List;

/**
 * 租户 CRUD REST 控制器。
 *
 * <p>统一前缀：{@code /api/v1/tenants}</p>
 * <ul>
 *   <li>POST   /          — 创建租户，返回 201</li>
 *   <li>GET    /          — 列出全部租户，返回 200</li>
 *   <li>GET    /{id}      — 获取单个租户，返回 200 或 404</li>
 *   <li>PUT    /{id}      — 更新租户，返回 200 或 404</li>
 *   <li>DELETE /{id}      — 删除租户，返回 204 或 404</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    public ResponseEntity<Tenant> create(@Valid @RequestBody Tenant tenant) {
        Tenant created = tenantService.create(tenant);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<Tenant>> list() {
        return ResponseEntity.ok(tenantService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Tenant> get(@PathVariable Long id) {
        return tenantService.get(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Tenant> update(@PathVariable Long id,
                                         @Valid @RequestBody Tenant tenant) {
        return tenantService.update(id, tenant)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (tenantService.delete(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}