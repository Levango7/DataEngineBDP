package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.KnowledgeBaseEntity;
import com.levango7.dataenginebdp.encaps.model.KnowledgeDocumentEntity;
import com.levango7.dataenginebdp.encaps.model.RagStrategyEntity;
import com.levango7.dataenginebdp.encaps.repository.KnowledgeBaseRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.KnowledgeUploadService;
import com.levango7.dataenginebdp.encaps.service.RagStrategyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库端点（ROADMAP 前后端接线：前端 /knowledge）。
 *
 * <p>统一前缀：{@code /api/v1/knowledge}</p>
 * <ul>
 *   <li>GET    /                            — 知识库列表</li>
 *   <li>GET    /{id}                        — 知识库详情</li>
 *   <li>POST   /                            — 创建知识库</li>
 *   <li>PUT    /{id}                        — 更新知识库</li>
 *   <li>DELETE /{id}                        — 删除知识库</li>
 *   <li>GET    /rag-strategy                — 获取 RAG 策略</li>
 *   <li>PUT    /rag-strategy                — 更新 RAG 策略</li>
 *   <li>POST   /upload                      — 上传文档（multipart/form-data）</li>
 *   <li>GET    /{id}/documents              — 知识库文档列表</li>
 *   <li>DELETE /{id}/documents/{docId}      — 删除文档</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/knowledge")
@Tag(name = "知识库", description = "知识库管理")
public class KnowledgeController {

    private final KnowledgeBaseRepository repository;
    private final RagStrategyService ragStrategyService;
    private final KnowledgeUploadService knowledgeUploadService;

    /** 创建/更新请求体。 */
    public record KnowledgeRequest(
            @NotBlank String name,
            String chunkStrategy,
            String retrieval) {
    }

    /** RAG 策略更新请求体（对齐前端 RagStrategyParams）。 */
    public record RagStrategyRequest(
            Integer topK,
            Double scoreThreshold,
            String rerankerModel,
            Boolean citationEnabled,
            String chunkStrategy,
            String retrievalMethod) {
    }

    /** 上传文档请求体（兼容旧版 JSON 上传，对齐前端 UploadDocParams）。 */
    public record UploadDocRequest(
            String kbId,
            String fileName,
            String content) {
    }

    /* ============================ 知识库 CRUD ============================ */

    /** 列表。 */
    @Operation(summary = "查询知识库列表", description = "查询当前租户全部知识库")
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> list() {
        String tenantId = requireTenant();
        return ResponseEntity.ok(repository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream().map(this::toView).toList());
    }

    /** 详情。 */
    @Operation(summary = "查询知识库详情", description = "按 ID 获取知识库详情")
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> get(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId)
                .map(k -> ResponseEntity.ok((Object) toView(k)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 创建。 */
    @Operation(summary = "创建知识库", description = "创建知识库（docCount=0, status=active）")
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
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(saved));
    }

    /** 更新。 */
    @Operation(summary = "更新知识库", description = "按 ID 更新知识库（name/chunkStrategy/retrieval）")
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
    @Operation(summary = "删除知识库", description = "按 ID 删除知识库（租户隔离）")
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

    /* ============================ RAG 策略 ============================ */

    /** 获取 RAG 策略（不存在则初始化默认配置）。 */
    @Operation(summary = "获取 RAG 策略", description = "获取当前租户 RAG 策略（不存在则初始化默认配置）")
    @GetMapping("/rag-strategy")
    public ResponseEntity<Map<String, Object>> getRagStrategy() {
        String tenantId = requireTenant();
        RagStrategyEntity entity = ragStrategyService.getOrCreate(tenantId);
        return ResponseEntity.ok(toRagView(entity));
    }

    /** 更新 RAG 策略。 */
    @Operation(summary = "更新 RAG 策略", description = "更新 RAG 策略（topK/scoreThreshold/rerankerModel/citationEnabled 等）")
    @PutMapping("/rag-strategy")
    public ResponseEntity<Map<String, Object>> updateRagStrategy(
            @RequestBody RagStrategyRequest req) {
        String tenantId = requireTenant();
        RagStrategyEntity patch = RagStrategyEntity.builder()
                .topK(req.topK())
                .scoreThreshold(req.scoreThreshold())
                .rerankerModel(req.rerankerModel())
                .citationEnabled(req.citationEnabled())
                .chunkStrategy(req.chunkStrategy())
                .retrievalMethod(req.retrievalMethod())
                .build();
        RagStrategyEntity updated = ragStrategyService.update(tenantId, patch);
        return ResponseEntity.ok(toRagView(updated));
    }

    /* ============================ 文档管理 ============================ */

    /**
     * 上传文档（multipart/form-data，对齐前端 el-upload）。
     *
     * @param id   知识库 ID
     * @param file 上传的文件
     */
    @Operation(summary = "上传文档（multipart）", description = "上传文档到指定知识库（multipart/form-data），自动分块与向量化")
    @PostMapping("/{id}/documents")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @PathVariable Long id,
            @RequestParam("file") @NotNull MultipartFile file) {
        String tenantId = requireTenant();
        KnowledgeDocumentEntity doc = knowledgeUploadService.uploadAndVectorize(tenantId, id, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDocumentView(doc));
    }

    /**
     * 兼容旧版 JSON 上传（对齐前端 UploadDocParams）。
     *
     * <p>不保存实际文件，仅记录元数据；推荐使用 {@code /{id}/documents} multipart 上传。</p>
     */
    @Operation(summary = "上传文档（JSON 兼容）", description = "兼容旧版 JSON 上传（仅记录元数据，推荐使用 multipart 上传）")
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDoc(@RequestBody UploadDocRequest req) {
        String tenantId = requireTenant();
        log.info("JSON 上传文档（兼容）: kb={}, file={}, tenant={}",
                req.kbId(), req.fileName(), tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", "doc-" + System.currentTimeMillis());
        result.put("kbId", req.kbId());
        result.put("fileName", req.fileName());
        result.put("status", "parsed");
        return ResponseEntity.ok(result);
    }

    /** 知识库文档列表。 */
    @Operation(summary = "查询知识库文档列表", description = "按知识库 ID 查询文档列表")
    @GetMapping("/{id}/documents")
    public ResponseEntity<List<Map<String, Object>>> listDocuments(@PathVariable Long id) {
        String tenantId = requireTenant();
        List<KnowledgeDocumentEntity> docs = knowledgeUploadService.listDocuments(tenantId, id);
        return ResponseEntity.ok(docs.stream().map(this::toDocumentView).toList());
    }

    /** 删除文档。 */
    @Operation(summary = "删除文档", description = "按知识库 ID 与文档 ID 删除文档")
    @DeleteMapping("/{id}/documents/{docId}")
    public ResponseEntity<?> deleteDocument(
            @PathVariable Long id,
            @PathVariable Long docId) {
        String tenantId = requireTenant();
        boolean deleted = knowledgeUploadService.deleteDocument(tenantId, docId);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    /* ============================ 私有辅助 ============================ */

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

    /** RAG 策略视图（对齐前端 RagStrategy）。 */
    private Map<String, Object> toRagView(RagStrategyEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("topK", e.getTopK());
        m.put("scoreThreshold", e.getScoreThreshold());
        m.put("rerankerModel", e.getRerankerModel());
        m.put("citationEnabled", e.getCitationEnabled());
        m.put("chunkStrategy", e.getChunkStrategy());
        m.put("retrievalMethod", e.getRetrievalMethod());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }

    /** 文档视图（对齐前端 KnowledgeDocument）。 */
    private Map<String, Object> toDocumentView(KnowledgeDocumentEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("knowledgeBaseId", String.valueOf(e.getKnowledgeBaseId()));
        m.put("fileName", e.getFileName());
        m.put("fileSize", e.getFileSize());
        m.put("fileType", e.getFileType());
        m.put("chunkCount", e.getChunkCount());
        m.put("vectorCount", e.getVectorCount());
        m.put("status", e.getStatus());
        m.put("errorMessage", e.getErrorMessage());
        m.put("uploadedAt", e.getUploadedAt() == null ? null : e.getUploadedAt().toString());
        return m;
    }
}
