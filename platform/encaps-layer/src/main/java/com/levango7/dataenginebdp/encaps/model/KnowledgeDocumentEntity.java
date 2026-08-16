package com.levango7.dataenginebdp.encaps.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 知识库文档实体（ROADMAP 前后端接线：/knowledge/{id}/documents）。
 *
 * <p>每条记录对应知识库中一个文档，记录文件元数据与向量化状态。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属知识库 ID。 */
    @Column(nullable = false)
    private Long knowledgeBaseId;

    /** 文档名。 */
    @Column(nullable = false, length = 255)
    private String fileName;

    /** 文件大小（字节）。 */
    private Long fileSize;

    /** 文件类型：pdf/txt/md/docx 等。 */
    @Column(length = 32)
    private String fileType;

    /** 存储路径（本地或对象存储）。 */
    @Column(length = 512)
    private String storagePath;

    /** 切片数。 */
    private Integer chunkCount;

    /** 向量数。 */
    private Integer vectorCount;

    /** 状态：uploaded/parsed/vectorized/failed。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 失败原因。 */
    @Column(length = 1024)
    private String errorMessage;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant uploadedAt;

    private Instant updatedAt;
}