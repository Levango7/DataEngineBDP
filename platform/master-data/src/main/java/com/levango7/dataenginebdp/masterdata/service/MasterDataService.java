package com.levango7.dataenginebdp.masterdata.service;

import com.levango7.dataenginebdp.masterdata.model.dto.CreateMasterDataDTO;
import com.levango7.dataenginebdp.masterdata.model.dto.UpdateMasterDataDTO;
import com.levango7.dataenginebdp.masterdata.model.entity.MasterData;
import com.levango7.dataenginebdp.masterdata.repository.MasterDataRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 主数据记录 CRUD 服务（基于 Spring Data JPA 持久化实现）。
 *
 * <p>使用 {@link MasterDataRepository} 将 MasterData 持久化到关系型数据库。
 * 多租户隔离：所有操作按 tenantId 过滤，避免跨租户数据泄漏。</p>
 */
@Service
public class MasterDataService {

    private final MasterDataRepository masterDataRepository;

    public MasterDataService(MasterDataRepository masterDataRepository) {
        this.masterDataRepository = masterDataRepository;
    }

    /**
     * 创建主数据记录。
     *
     * @param dto      创建请求
     * @param tenantId 租户 ID
     * @return 创建后的主数据记录
     */
    public MasterData create(CreateMasterDataDTO dto, String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        MasterData masterData = new MasterData();
        masterData.setModelCode(dto.getModelCode());
        masterData.setDataKey(dto.getDataKey());
        masterData.setDataValue(dto.getDataValue());
        masterData.setAttributes(dto.getAttributes());
        masterData.setVersion(dto.getVersion() == null ? "1.0.0" : dto.getVersion());
        masterData.setStatus(dto.getStatus() == null ? "ACTIVE" : dto.getStatus());
        masterData.setTenantId(tenantId);
        masterData.setCreatedAt(now);
        masterData.setUpdatedAt(now);
        return masterDataRepository.save(masterData);
    }

    /**
     * 按模型编码查询主数据记录。
     *
     * @param modelCode 模型编码（null 时查询全部）
     * @param tenantId  租户 ID
     * @return 主数据记录列表
     */
    public List<MasterData> query(String modelCode, String tenantId) {
        if (modelCode != null && !modelCode.isBlank()) {
            return masterDataRepository.findByModelCodeAndTenantId(modelCode, tenantId);
        }
        return masterDataRepository.findByTenantId(tenantId);
    }

    /**
     * 根据 ID 与租户 ID 获取主数据记录。
     *
     * @param id       记录 ID
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的记录；否则 {@code null}
     */
    public MasterData getById(Long id, String tenantId) {
        if (id == null || tenantId == null) {
            return null;
        }
        return masterDataRepository.findByIdAndTenantId(id, tenantId).orElse(null);
    }

    /**
     * 更新主数据记录（租户隔离）。
     *
     * @param id       记录 ID
     * @param dto      更新请求
     * @param tenantId 租户 ID
     * @return 更新后的记录；若不存在或不属于该租户则 {@code null}
     */
    public MasterData update(Long id, UpdateMasterDataDTO dto, String tenantId) {
        if (id == null || tenantId == null) {
            return null;
        }
        Optional<MasterData> existingOpt = masterDataRepository.findByIdAndTenantId(id, tenantId);
        if (existingOpt.isEmpty()) {
            return null;
        }
        MasterData existing = existingOpt.get();
        if (dto.getDataKey() != null) {
            existing.setDataKey(dto.getDataKey());
        }
        if (dto.getDataValue() != null) {
            existing.setDataValue(dto.getDataValue());
        }
        if (dto.getAttributes() != null) {
            existing.setAttributes(dto.getAttributes());
        }
        if (dto.getVersion() != null) {
            existing.setVersion(dto.getVersion());
        }
        if (dto.getStatus() != null) {
            existing.setStatus(dto.getStatus());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        return masterDataRepository.save(existing);
    }

    /**
     * 删除主数据记录（租户隔离）。
     *
     * @param id       记录 ID
     * @param tenantId 租户 ID
     * @return 删除成功返回 {@code true}；不存在或不属于该租户返回 {@code false}
     */
    public boolean delete(Long id, String tenantId) {
        if (id == null || tenantId == null) {
            return false;
        }
        Optional<MasterData> existing = masterDataRepository.findByIdAndTenantId(id, tenantId);
        if (existing.isEmpty()) {
            return false;
        }
        masterDataRepository.delete(existing.get());
        return true;
    }
}