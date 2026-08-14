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
 * 知识库实体（ROADMAP 前后端接线：/knowledge）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "knowledge_base")
public class KnowledgeBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 知识库名。 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 文档数（统计）。 */
    @Column(nullable = false)
    private Integer docCount;

    /** 切片策略描述。 */
    @Column(length = 255)
    private String chunkStrategy;

    /** 检索方式描述。 */
    @Column(length = 255)
    private String retrieval;

    /** 状态：active/pending/disabled。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 租户隔离。 */
    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
