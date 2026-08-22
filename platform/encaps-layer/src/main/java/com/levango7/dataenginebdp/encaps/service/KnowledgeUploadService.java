package com.levango7.dataenginebdp.encaps.service;

import com.levango7.dataenginebdp.encaps.model.KnowledgeBaseEntity;
import com.levango7.dataenginebdp.encaps.model.KnowledgeDocumentEntity;
import com.levango7.dataenginebdp.encaps.repository.KnowledgeBaseRepository;
import com.levango7.dataenginebdp.encaps.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 知识库文档上传 + 向量化服务。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>保存上传文件到本地存储目录（可由 {@code app.knowledge.upload-dir} 配置）</li>
 *   <li>写入 {@link KnowledgeDocumentEntity} 元数据记录</li>
 *   <li>更新所属知识库的文档数统计</li>
 *   <li>调用向量引擎生成 embedding（当前为占位实现，真实场景下调用
 *       knowledge-engine 切片 + 向量化 + 入库）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeUploadService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentRepository documentRepository;

    /** 上传文件存储根目录，默认 ./data/knowledge-uploads。 */
    @Value("${app.knowledge.upload-dir:./data/knowledge-uploads}")
    private String uploadDir;

    /** 列出知识库下全部文档。 */
    @Transactional(readOnly = true)
    public List<KnowledgeDocumentEntity> listDocuments(String tenantId, Long knowledgeBaseId) {
        return documentRepository.findByKnowledgeBaseIdAndTenantIdOrderByUploadedAtDesc(
                knowledgeBaseId, tenantId);
    }

    /** 文档详情。 */
    @Transactional(readOnly = true)
    public Optional<KnowledgeDocumentEntity> getDocument(String tenantId, Long docId) {
        return documentRepository.findByIdAndTenantId(docId, tenantId);
    }

    /**
     * 上传文档并触发向量化。
     *
     * @param tenantId        租户 ID
     * @param knowledgeBaseId 知识库 ID
     * @param file            上传的文件
     * @return 文档实体（含向量化状态）
     */
    @Transactional
    public KnowledgeDocumentEntity uploadAndVectorize(
            String tenantId, Long knowledgeBaseId, MultipartFile file) {
        // 校验知识库存在
        KnowledgeBaseEntity kb = knowledgeBaseRepository
                .findByIdAndTenantId(knowledgeBaseId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "知识库不存在: id=" + knowledgeBaseId));

        // 保存文件到本地
        String storagePath = storeFile(tenantId, knowledgeBaseId, file);

        // 写文档元数据
        KnowledgeDocumentEntity doc = KnowledgeDocumentEntity.builder()
                .knowledgeBaseId(knowledgeBaseId)
                .fileName(file.getOriginalFilename())
                .fileSize(file.getSize())
                .fileType(extractFileType(file.getOriginalFilename()))
                .storagePath(storagePath)
                .chunkCount(0)
                .vectorCount(0)
                .status("uploaded")
                .tenantId(tenantId)
                .uploadedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        KnowledgeDocumentEntity saved = documentRepository.save(doc);
        log.info("上传文档: docId={}, kb={}, file={}, size={}, tenant={}",
                saved.getId(), knowledgeBaseId, saved.getFileName(), saved.getFileSize(), tenantId);

        // 触发向量化（占位实现：标记为 vectorized，并模拟切片/向量数）
        vectorize(saved, kb);

        // 更新知识库文档数
        long count = documentRepository.countByKnowledgeBaseIdAndTenantId(knowledgeBaseId, tenantId);
        kb.setDocCount((int) count);
        kb.setUpdatedAt(Instant.now());
        knowledgeBaseRepository.save(kb);

        return saved;
    }

    /** 删除文档（同时清理元数据；本地文件保留以便审计）。 */
    @Transactional
    public boolean deleteDocument(String tenantId, Long docId) {
        Optional<KnowledgeDocumentEntity> doc = documentRepository.findByIdAndTenantId(docId, tenantId);
        if (doc.isEmpty()) {
            return false;
        }
        KnowledgeDocumentEntity entity = doc.get();
        documentRepository.delete(entity);

        // 更新所属知识库文档数
        knowledgeBaseRepository.findByIdAndTenantId(entity.getKnowledgeBaseId(), tenantId)
                .ifPresent(kb -> {
                    long count = documentRepository.countByKnowledgeBaseIdAndTenantId(
                            kb.getId(), tenantId);
                    kb.setDocCount((int) count);
                    kb.setUpdatedAt(Instant.now());
                    knowledgeBaseRepository.save(kb);
                });
        log.info("删除文档: docId={}, kb={}, tenant={}", docId, entity.getKnowledgeBaseId(), tenantId);
        return true;
    }

    /* ============================ 私有辅助 ============================ */

    /** 保存上传文件到本地存储目录。 */
    private String storeFile(String tenantId, Long kbId, MultipartFile file) {
        try {
            Path dir = Paths.get(uploadDir, tenantId, String.valueOf(kbId));
            Files.createDirectories(dir);
            String fileName = System.currentTimeMillis() + "-"
                    + sanitizeFileName(file.getOriginalFilename());
            Path target = dir.resolve(fileName);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (IOException e) {
            throw new IllegalStateException("保存上传文件失败: " + e.getMessage(), e);
        }
    }

    /** 提取文件扩展名（小写，不含点）。 */
    private String extractFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "unknown";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    /** 文件名净化：移除路径分隔符，避免目录穿越。 */
    private String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return "unnamed";
        }
        return fileName.replace("/", "_").replace("\\", "_").replace("..", "_");
    }

    /**
     * 向量化占位实现。
     *
     * <p><b>⚠️ 占位实现警告：</b>当前按文件大小估算切片数后直接标记为"vectorized"，
     * 不调用 embedding 模型，不具备语义检索能力。生产环境应配置真实向量引擎 API
     * （通过 {@code app.knowledge.embedding-api} 配置），完成 切片→embedding→写入向量库 全流程。</p>
     */
    private void vectorize(KnowledgeDocumentEntity doc, KnowledgeBaseEntity kb) {
        log.warn("使用占位向量化实现（按文件大小估算），未调用 embedding 模型。生产环境请配置 app.knowledge.embedding-api");
        // 模拟切片：按文件大小估算切片数（每 2KB 一个切片，最少 1 个）
        long size = doc.getFileSize() == null ? 0L : doc.getFileSize();
        int chunkCount = Math.max(1, (int) (size / 2048));
        int vectorCount = chunkCount;
        doc.setChunkCount(chunkCount);
        doc.setVectorCount(vectorCount);
        doc.setStatus("vectorized");
        doc.setUpdatedAt(Instant.now());
        documentRepository.save(doc);
        log.info("向量化完成: docId={}, chunks={}, vectors={}, kb={}",
                doc.getId(), chunkCount, vectorCount, kb.getId());
    }
}