package com.levango7.dataenginebdp.tagengine.repository;

import com.levango7.dataenginebdp.tagengine.entity.TagRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 标签规则持久化仓储。
 */
@Repository
public interface TagRuleRepository extends JpaRepository<TagRuleEntity, String> {

    /**
     * 列出指定标签的全部规则。
     *
     * @param tagId 标签 ID
     * @return 规则列表
     */
    List<TagRuleEntity> findByTagId(String tagId);
}