package com.levango7.dataenginebdp.datastandard.service;

import com.levango7.dataenginebdp.datastandard.model.dto.CreateStandardDTO;
import com.levango7.dataenginebdp.datastandard.model.dto.StandardQueryDTO;
import com.levango7.dataenginebdp.datastandard.model.dto.UpdateStandardDTO;
import com.levango7.dataenginebdp.datastandard.model.entity.Standard;
import com.levango7.dataenginebdp.datastandard.repository.StandardRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 数据标准 CRUD 服务（基于 Spring Data JPA 持久化实现）。
 *
 * <p>使用 {@link StandardRepository} 将 Standard 持久化到关系型数据库。
 * 开发环境默认使用 H2 内存 / 文件数据库，生产环境通过环境变量切换 PostgreSQL。
 * 多租户隔离：所有操作按 tenantId 过滤，避免跨租户数据泄漏。</p>
 */
@Service
public class StandardService {

    private final StandardRepository standardRepository;

    public StandardService(StandardRepository standardRepository) {
        this.standardRepository = standardRepository;
    }

    /**
     * 创建数据标准。
     *
     * @param dto      创建请求
     * @param tenantId 租户 ID
     * @return 创建后的数据标准
     */
    public Standard create(CreateStandardDTO dto, String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        Standard standard = new Standard();
        standard.setName(dto.getName());
        standard.setCategory(dto.getCategory());
        standard.setDescription(dto.getDescription());
        standard.setRule(dto.getRule());
        standard.setJsonSchema(dto.getJsonSchema());
        standard.setVersion(dto.getVersion() == null ? "1.0.0" : dto.getVersion());
        standard.setStatus(dto.getStatus() == null ? "DRAFT" : dto.getStatus());
        standard.setTenantId(tenantId);
        standard.setCreatedAt(now);
        standard.setUpdatedAt(now);
        return standardRepository.save(standard);
    }

    /**
     * 分页查询数据标准。
     *
     * @param query    查询条件
     * @param tenantId 租户 ID
     * @return 数据标准分页结果
     */
    public Page<Standard> query(StandardQueryDTO query, String tenantId) {
        Pageable pageable = PageRequest.of(query.getPageOrDefault(), query.getSizeOrDefault());

        if (query.getName() != null && !query.getName().isBlank()) {
            return standardRepository.findByNameContainingAndTenantId(
                    query.getName(), tenantId, pageable);
        }
        if (query.getCategory() != null && !query.getCategory().isBlank()) {
            return standardRepository.findByTenantIdAndCategory(tenantId, query.getCategory(), pageable);
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            return standardRepository.findByTenantIdAndStatus(tenantId, query.getStatus(), pageable);
        }
        return standardRepository.findByTenantId(tenantId, pageable);
    }

    /**
     * 根据 ID 与租户 ID 获取数据标准。
     *
     * @param id       标准 ID
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的标准；否则 {@code null}
     */
    public Standard getById(Long id, String tenantId) {
        if (id == null || tenantId == null) {
            return null;
        }
        return standardRepository.findByIdAndTenantId(id, tenantId).orElse(null);
    }

    /**
     * 更新数据标准（租户隔离）。
     *
     * <p>先按 (id, tenantId) 校验存在性与租户归属，不匹配返回 {@code null}；
     * 命中则保留原 createdAt 与 tenantId，写入其余字段。</p>
     *
     * @param id       标准 ID
     * @param dto      更新请求
     * @param tenantId 租户 ID
     * @return 更新后的标准；若不存在或不属于该租户则 {@code null}
     */
    public Standard update(Long id, UpdateStandardDTO dto, String tenantId) {
        if (id == null || tenantId == null) {
            return null;
        }
        Optional<Standard> existingOpt = standardRepository.findByIdAndTenantId(id, tenantId);
        if (existingOpt.isEmpty()) {
            return null;
        }
        Standard existing = existingOpt.get();
        if (dto.getName() != null) {
            existing.setName(dto.getName());
        }
        if (dto.getCategory() != null) {
            existing.setCategory(dto.getCategory());
        }
        if (dto.getDescription() != null) {
            existing.setDescription(dto.getDescription());
        }
        if (dto.getRule() != null) {
            existing.setRule(dto.getRule());
        }
        if (dto.getJsonSchema() != null) {
            existing.setJsonSchema(dto.getJsonSchema());
        }
        if (dto.getVersion() != null) {
            existing.setVersion(dto.getVersion());
        }
        if (dto.getStatus() != null) {
            existing.setStatus(dto.getStatus());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        return standardRepository.save(existing);
    }

    /**
     * 删除数据标准（租户隔离）。
     *
     * @param id       标准 ID
     * @param tenantId 租户 ID
     * @return 删除成功返回 {@code true}；不存在或不属于该租户返回 {@code false}
     */
    public boolean delete(Long id, String tenantId) {
        if (id == null || tenantId == null) {
            return false;
        }
        Optional<Standard> existing = standardRepository.findByIdAndTenantId(id, tenantId);
        if (existing.isEmpty()) {
            return false;
        }
        standardRepository.delete(existing.get());
        return true;
    }
}