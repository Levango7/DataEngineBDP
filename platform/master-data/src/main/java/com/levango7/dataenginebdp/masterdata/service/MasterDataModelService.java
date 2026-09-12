package com.levango7.dataenginebdp.masterdata.service;

import com.levango7.dataenginebdp.masterdata.model.dto.CreateMasterDataModelDTO;
import com.levango7.dataenginebdp.masterdata.model.entity.MasterDataModel;
import com.levango7.dataenginebdp.masterdata.repository.MasterDataModelRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主数据模型 CRUD 服务（基于 Spring Data JPA 持久化实现）。
 *
 * <p>使用 {@link MasterDataModelRepository} 将 MasterDataModel 持久化到关系型数据库。
 * 多租户隔离：所有操作按 tenantId 过滤，避免跨租户数据泄漏。</p>
 */
@Service
public class MasterDataModelService {

    private final MasterDataModelRepository modelRepository;

    public MasterDataModelService(MasterDataModelRepository modelRepository) {
        this.modelRepository = modelRepository;
    }

    /**
     * 列出指定租户下的全部主数据模型。
     *
     * @param tenantId 租户 ID
     * @return 该租户下的全部主数据模型
     */
    public List<MasterDataModel> listAll(String tenantId) {
        return modelRepository.findByTenantId(tenantId);
    }

    /**
     * 创建主数据模型。
     *
     * @param dto      创建请求
     * @param tenantId 租户 ID
     * @return 创建后的主数据模型
     */
    public MasterDataModel create(CreateMasterDataModelDTO dto, String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        MasterDataModel model = new MasterDataModel();
        model.setCode(dto.getCode());
        model.setName(dto.getName());
        model.setFieldsSchema(dto.getFieldsSchema());
        model.setDescription(dto.getDescription());
        model.setTenantId(tenantId);
        model.setCreatedAt(now);
        model.setUpdatedAt(now);
        return modelRepository.save(model);
    }

    /**
     * 根据模型编码与租户 ID 获取主数据模型。
     *
     * @param code     模型编码
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的模型；否则 {@code null}
     */
    public MasterDataModel getByCode(String code, String tenantId) {
        if (code == null || tenantId == null) {
            return null;
        }
        return modelRepository.findByCodeAndTenantId(code, tenantId).orElse(null);
    }
}