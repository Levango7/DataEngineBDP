package com.levango7.dataenginebdp.datastandard.service;

import com.levango7.dataenginebdp.datastandard.model.entity.StandardCategory;
import com.levango7.dataenginebdp.datastandard.repository.StandardCategoryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据标准分类 CRUD 服务（基于 Spring Data JPA 持久化实现）。
 *
 * <p>使用 {@link StandardCategoryRepository} 将 StandardCategory 持久化到关系型数据库。
 * 多租户隔离：所有操作按 tenantId 过滤，避免跨租户数据泄漏。</p>
 */
@Service
public class StandardCategoryService {

    private final StandardCategoryRepository categoryRepository;

    public StandardCategoryService(StandardCategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * 列出指定租户下的全部分类。
     *
     * @param tenantId 租户 ID
     * @return 该租户下的全部分类
     */
    public List<StandardCategory> listAll(String tenantId) {
        return categoryRepository.findByTenantId(tenantId);
    }

    /**
     * 创建标准分类。
     *
     * @param category 分类实体
     * @param tenantId 租户 ID
     * @return 创建后的分类
     */
    public StandardCategory create(StandardCategory category, String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        category.setId(null);
        category.setTenantId(tenantId);
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        return categoryRepository.save(category);
    }

    /**
     * 根据 ID 与租户 ID 获取分类。
     *
     * @param id       分类 ID
     * @param tenantId 租户 ID
     * @return 命中且属于该租户的分类；否则 {@code null}
     */
    public StandardCategory getById(Long id, String tenantId) {
        if (id == null || tenantId == null) {
            return null;
        }
        return categoryRepository.findByIdAndTenantId(id, tenantId).orElse(null);
    }
}