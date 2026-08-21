package com.shuqing.bigdata.tagengine.repository;

import com.shuqing.bigdata.tagengine.entity.TagDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 标签定义持久化仓储。
 *
 * <p>基于 Spring Data JPA，提供 TagDefinition 的标准 CRUD 与按租户查询。</p>
 */
@Repository
public interface TagDefinitionRepository extends JpaRepository<TagDefinitionEntity, String> {

    /**
     * 列出指定租户的全部标签定义。
     *
     * @param tenantId 租户 ID
     * @return 标签定义列表
     */
    List<TagDefinitionEntity> findByTenantId(String tenantId);

    /**
     * 按租户与名称查找（名称租户内唯一）。
     *
     * @param tenantId 租户 ID
     * @param name     标签名称
     * @return Optional 包装的标签定义
     */
    Optional<TagDefinitionEntity> findByTenantIdAndName(String tenantId, String name);
}