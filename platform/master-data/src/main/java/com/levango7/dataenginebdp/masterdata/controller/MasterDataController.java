package com.levango7.dataenginebdp.masterdata.controller;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.masterdata.model.dto.CreateMasterDataDTO;
import com.levango7.dataenginebdp.masterdata.model.dto.UpdateMasterDataDTO;
import com.levango7.dataenginebdp.masterdata.model.entity.MasterData;
import com.levango7.dataenginebdp.masterdata.service.MasterDataService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 主数据记录 REST 控制器。
 *
 * <p>提供主数据记录的 CRUD 与按模型分页查询端点。
 * 多租户隔离：所有操作通过 {@link #requireTenant()} 从 {@link TenantContext}
 * 取得租户 ID，缺失则 fail-closed。</p>
 */
@Slf4j
@RestController
@Tag(name = "主数据-记录管理", description = "主数据记录CRUD与查询")
@RequiredArgsConstructor
@RequestMapping("/api/v1/master-data")
public class MasterDataController {

    private final MasterDataService masterDataService;

    /** 创建主数据记录 */
    @Operation(summary = "创建主数据记录")
    @PostMapping
    public ResponseEntity<MasterData> create(@Valid @RequestBody CreateMasterDataDTO dto) {
        String tenantId = requireTenant();
        MasterData created = masterDataService.create(dto, tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 按模型分页查询主数据记录。
     *
     * @param modelCode 模型编码（可选，不传则查询全部模型）
     * @param page      页码（从 0 开始，默认 0）
     * @param size      每页大小（默认 20，上限 200）
     * @return 主数据记录分页结果
     */
    @Operation(summary = "按模型分页查询主数据记录")
    @GetMapping
    public ResponseEntity<Page<MasterData>> query(
            @RequestParam(name = "modelCode", required = false) String modelCode,
            @RequestParam(name = "page", required = false, defaultValue = "0") Integer page,
            @RequestParam(name = "size", required = false, defaultValue = "20") Integer size) {
        String tenantId = requireTenant();
        return ResponseEntity.ok(masterDataService.query(modelCode, tenantId, page, size));
    }

    /** 更新主数据记录 */
    @Operation(summary = "更新主数据记录")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @Valid @RequestBody UpdateMasterDataDTO dto) {
        String tenantId = requireTenant();
        MasterData updated = masterDataService.update(id, dto, tenantId);
        if (updated == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "master_data_not_found",
                            "message", "MasterData " + id + " not found"));
        }
        return ResponseEntity.ok(updated);
    }

    /** 删除主数据记录 */
    @Operation(summary = "删除主数据记录")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        String tenantId = requireTenant();
        boolean removed = masterDataService.delete(id, tenantId);
        if (!removed) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "master_data_not_found",
                            "message", "MasterData " + id + " not found"));
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