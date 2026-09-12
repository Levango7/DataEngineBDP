package com.levango7.dataenginebdp.masterdata.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.masterdata.model.dto.CreateMasterDataModelDTO;
import com.levango7.dataenginebdp.masterdata.model.entity.MasterDataModel;
import com.levango7.dataenginebdp.masterdata.service.MasterDataModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * 主数据模型 REST 控制器。
 *
 * <p>提供主数据模型定义的列表与创建端点。
 * 多租户隔离：所有操作通过 {@link #requireTenant()} 从 {@link TenantContext}
 * 取得租户 ID，缺失则 fail-closed。</p>
 */
@Slf4j
@RestController
@Tag(name = "主数据-模型管理", description = "主数据模型定义管理")
@RequiredArgsConstructor
@RequestMapping("/api/v1/master-data/models")
public class MasterDataModelController {

    private final MasterDataModelService modelService;

    /** 列出所有主数据模型 */
    @Operation(summary = "列出所有主数据模型")
    @GetMapping
    public ResponseEntity<List<MasterDataModel>> listAll() {
        String tenantId = requireTenant();
        return ResponseEntity.ok(modelService.listAll(tenantId));
    }

    /** 创建主数据模型 */
    @Operation(summary = "创建主数据模型")
    @PostMapping
    public ResponseEntity<MasterDataModel> create(@Valid @RequestBody CreateMasterDataModelDTO dto) {
        String tenantId = requireTenant();
        MasterDataModel created = modelService.create(dto, tenantId);
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