package com.levango7.dataenginebdp.encaps.repository;

import com.levango7.dataenginebdp.encaps.model.KnowledgeDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库文档仓储。
 */
@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, Long> {

    /** 列出知识库下全部文档（按上传时间倒序）。 */
    List<KnowledgeDocumentEntity> findByKnowledgeBaseIdAndTenantIdOrderByUploadedAtDesc(
            Long knowledgeBaseId, String tenantId);

    /** 单个文档详情（租户隔离）。 */
    Optional<KnowledgeDocumentEntity> findByIdAndTenantId(Long id, String tenantId);

    /** 统计知识库下文档数。 */
    long countByKnowledgeBaseIdAndTenantId(Long knowledgeBaseId, String tenantId);
}