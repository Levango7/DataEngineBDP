package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.KnowledgeBaseEntity;
import com.levango7.dataenginebdp.encaps.repository.KnowledgeBaseRepository;
import com.levango7.dataenginebdp.encaps.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库端点（ROADMAP 前后端接线：前端 /knowledge）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeBaseRepository repository;

    /** 创建/更新请求体。 */
    public record KnowledgeRequest(
            @NotBlank String name,
            String chunkStrategy,
            String retrieval) {
    }

    /** 列表。 */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> list() {
        String tenantId = requireTenant();
        return ResponseEntity.ok(repository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream().map(this::toView).toList());
    }

    /** 详情。 */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> get(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId)
                .map(k -> ResponseEntity.ok((Object) toView(k)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建。 */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody KnowledgeRequest req) {
        String tenantId = requireTenant();
        KnowledgeBaseEntity entity = KnowledgeBaseEntity.builder()
                .name(req.name())
                .docCount(0)
                .chunkStrategy(req.chunkStrategy())
                .retrieval(req.retrieval())
                .status("active")
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        KnowledgeBaseEntity saved = repository.save(entity);
        log.info("创建知识库: id={}, name={}, tenant={}", saved.getId(), saved.getName(), tenantId);
        return ResponseEntity.ok(toView(saved));
    }

    /** 更新。 */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody KnowledgeRequest req) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            entity.setName(req.name());
            entity.setChunkStrategy(req.chunkStrategy());
            entity.setRetrieval(req.retrieval());
            entity.setUpdatedAt(Instant.now());
            return ResponseEntity.ok((Object) toView(repository.save(entity)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 删除。 */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            repository.delete(entity);
            log.info("删除知识库: id={}, tenant={}", id, tenantId);
            return ResponseEntity.ok(Map.of("deleted", true));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 获取 RAG 策略。
     *
     * <p>对齐前端 {@code knowledge.ts} 的 {@code getRagStrategy}。
     * TODO: 接入 RAG 策略存储，当前返回默认配置占位。</p>
     *
     * @return 200 + RAG 策略
     */
    @GetMapping("/rag-strategy")
    public ResponseEntity<Map<String, Object>> getRagStrategy() {
        // TODO: 从策略存储查询租户级 RAG 配置
        log.info("获取 RAG 策略: tenant={}", TenantContext.getTenantId());
        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("topK", 5);
        strategy.put("scoreThreshold", 0.7);
        strategy.put("rerankerModel", "bge-reranker-large");
        strategy.put("citationEnabled", true);
        return ResponseEntity.ok(strategy);
    }

    /** 上传文档请求体（对齐前端 UploadDocParams）。 */
    public record UploadDocRequest(
            String kbId,
            String fileName,
            String content) {
    }

    /**
     * 上传文档。
     *
     * <p>对齐前端 {@code knowledge.ts} 的 {@code uploadDoc}。
     * TODO: 转交 knowledge-engine 切片 + 向量化，当前返回占位结果。</p>
     *
     * @param req 上传请求
     * @return 200 + 上传结果
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDoc(@RequestBody UploadDocRequest req) {
        // TODO: 转交 knowledge-engine 处理（切片 + 向量化 + 入库）
        log.info("上传文档: kb={}, file={}, tenant={}",
                req.kbId(), req.fileName(), TenantContext.getTenantId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", "doc-" + System.currentTimeMillis());
        result.put("kbId", req.kbId());
        result.put("status", "parsed");
        return ResponseEntity.ok(result);
    }

    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    private Map<String, Object> toView(KnowledgeBaseEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("name", e.getName());
        m.put("docCount", e.getDocCount());
        m.put("chunkStrategy", e.getChunkStrategy());
        m.put("retrieval", e.getRetrieval());
        m.put("status", e.getStatus());
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }
}
