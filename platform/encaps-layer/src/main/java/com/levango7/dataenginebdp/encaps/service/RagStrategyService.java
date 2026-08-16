package com.levango7.dataenginebdp.encaps.service;

import com.levango7.dataenginebdp.encaps.model.RagStrategyEntity;
import com.levango7.dataenginebdp.encaps.repository.RagStrategyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * RAG 策略管理服务。
 *
 * <p>每个租户一份 RAG 策略配置；首次读取时若不存在则写入默认配置，
 * 后续 {@code update} 直接覆盖更新。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagStrategyService {

    /** 默认 TopK。 */
    private static final int DEFAULT_TOP_K = 5;
    /** 默认分数阈值。 */
    private static final double DEFAULT_SCORE_THRESHOLD = 0.7;
    /** 默认重排模型。 */
    private static final String DEFAULT_RERANKER = "bge-reranker-large";
    /** 默认切片策略。 */
    private static final String DEFAULT_CHUNK_STRATEGY = "by_paragraph";
    /** 默认检索方式。 */
    private static final String DEFAULT_RETRIEVAL = "vector";

    private final RagStrategyRepository repository;

    /** 读取租户 RAG 策略；不存在则创建默认配置。 */
    @Transactional
    public RagStrategyEntity getOrCreate(String tenantId) {
        return repository.findByTenantId(tenantId).orElseGet(() -> {
            RagStrategyEntity created = RagStrategyEntity.builder()
                    .tenantId(tenantId)
                    .topK(DEFAULT_TOP_K)
                    .scoreThreshold(DEFAULT_SCORE_THRESHOLD)
                    .rerankerModel(DEFAULT_RERANKER)
                    .citationEnabled(true)
                    .chunkStrategy(DEFAULT_CHUNK_STRATEGY)
                    .retrievalMethod(DEFAULT_RETRIEVAL)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            RagStrategyEntity saved = repository.save(created);
            log.info("初始化 RAG 策略: tenant={}", tenantId);
            return saved;
        });
    }

    /** 更新租户 RAG 策略；不存在则新建。 */
    @Transactional
    public RagStrategyEntity update(String tenantId, RagStrategyEntity patch) {
        Optional<RagStrategyEntity> existing = repository.findByTenantId(tenantId);
        RagStrategyEntity entity = existing.orElseGet(() -> RagStrategyEntity.builder()
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .build());
        if (patch.getTopK() != null) {
            entity.setTopK(patch.getTopK());
        }
        if (patch.getScoreThreshold() != null) {
            entity.setScoreThreshold(patch.getScoreThreshold());
        }
        if (patch.getRerankerModel() != null) {
            entity.setRerankerModel(patch.getRerankerModel());
        }
        if (patch.getCitationEnabled() != null) {
            entity.setCitationEnabled(patch.getCitationEnabled());
        }
        if (patch.getChunkStrategy() != null) {
            entity.setChunkStrategy(patch.getChunkStrategy());
        }
        if (patch.getRetrievalMethod() != null) {
            entity.setRetrievalMethod(patch.getRetrievalMethod());
        }
        entity.setUpdatedAt(Instant.now());
        RagStrategyEntity saved = repository.save(entity);
        log.info("更新 RAG 策略: tenant={}, topK={}, threshold={}",
                tenantId, saved.getTopK(), saved.getScoreThreshold());
        return saved;
    }
}