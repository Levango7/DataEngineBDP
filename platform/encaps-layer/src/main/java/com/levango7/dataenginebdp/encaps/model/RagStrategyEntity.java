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
 * RAG 策略配置实体（ROADMAP 前后端接线：/knowledge/rag-strategy）。
 *
 * <p>每个租户一条记录，存储检索 TopK、分数阈值、重排模型、引用溯源等配置。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "knowledge_rag_strategy")
public class RagStrategyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 租户隔离（唯一）。 */
    @Column(nullable = false, length = 64, unique = true)
    private String tenantId;

    /** 检索 TopK。 */
    private Integer topK;

    /** 分数阈值（0-1）。 */
    private Double scoreThreshold;

    /** 重排模型名。 */
    @Column(length = 128)
    private String rerankerModel;

    /** 是否开启引用溯源。 */
    private Boolean citationEnabled;

    /** 切片策略：by_paragraph/by_title/by_turn/by_sentence。 */
    @Column(length = 32)
    private String chunkStrategy;

    /** 检索方式：vector/keyword/hybrid。 */
    @Column(length = 32)
    private String retrievalMethod;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}