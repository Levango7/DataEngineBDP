package com.levango7.dataenginebdp.tagengine.service;

import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.tagengine.entity.TagDefinitionEntity;
import com.levango7.dataenginebdp.tagengine.model.BatchComputeResult;
import com.levango7.dataenginebdp.tagengine.model.ComputeRequest;
import com.levango7.dataenginebdp.tagengine.model.TagComputeResult;
import com.levango7.dataenginebdp.tagengine.repository.TagDefinitionRepository;
import com.levango7.dataenginebdp.tagengine.store.TagStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 标签计算服务。
 *
 * <p>编排标签计算作业：</p>
 * <ul>
 *   <li>Mock 模式：直接调用 {@link TagStore#computeTag} 在内存中算</li>
 *   <li>Doris 模式：触发 Spark ETL 作业，结果通过 Stream Load 写 Doris 宽表（骨架由 TagStore 接管）</li>
 * </ul>
 *
 * <p>对应详细设计 §4 标签计算。</p>
 *
 * <p><b>多租户隔离（R10 安全修复）</b>：{@link #computeTag(String, ComputeRequest, String)}
 * 与 {@link #batchCompute(List, ComputeRequest, String)} 在计算前按 (tagId, tenantId)
 * 校验标签归属，不属于当前租户的标签跳过计算，防止跨租户计算越权。</p>
 */
@Service
public class ComputeService {

    private static final Logger log = LoggerFactory.getLogger(ComputeService.class);

    private final TagStore tagStore;
    private final TagDefinitionRepository tagDefRepo;

    @Value("${app.compute.batch-max-size:100}")
    private int batchMaxSize;

    public ComputeService(TagStore tagStore, TagDefinitionRepository tagDefRepo) {
        this.tagStore = tagStore;
        this.tagDefRepo = tagDefRepo;
    }

    /**
     * 计算单个标签（租户隔离：只能算当前租户自己的标签）。
     *
     * <p>R8 租户隔离补齐：先按当前租户校验标签归属，不属于本租户时返回
     * {@code FAILED} 结果（而不是抛异常，与批量计算的"跳过计入 failed"语义一致）。</p>
     *
     * @param tagId 标签 ID
     * @param req   计算请求
     * @return 计算结果；标签不属于当前租户时返回 status=FAILED
     * @throws IllegalStateException 缺少租户上下文（fail-closed）
     */
    public TagComputeResult computeTag(String tagId, ComputeRequest req) {
        String tenantId = requireTenant();
        if (!isTenantOwnedTag(tagId, tenantId)) {
            log.warn("ComputeService.computeTag: 标签不存在或不属于当前租户: tagId={}, tenant={}",
                    tagId, tenantId);
            return TagComputeResult.builder()
                    .tagId(tagId)
                    .status("FAILED")
                    .errorMessage("标签不存在或不属于当前租户")
                    .build();
        }
        req.setTenantId(tenantId);
        return computeTag(tagId, req, tenantId);
    }

    /**
     * 计算单个标签（租户隔离）。
     *
     * <p>先按 (tagId, tenantId) 校验标签归属，不属于该租户的标签抛
     * {@link IllegalArgumentException}，防止跨租户计算越权。</p>
     *
     * @param tagId    标签 ID
     * @param req      计算请求
     * @param tenantId 租户 ID
     * @return 计算结果
     * @throws IllegalArgumentException 标签不存在或不属于该租户
     */
    public TagComputeResult computeTag(String tagId, ComputeRequest req, String tenantId) {
        requireTenantOwnedTag(tagId, tenantId);
        log.info("ComputeService.computeTag: tagId={}, mode={}, tenant={}", tagId, req.getMode(), tenantId);
        return tagStore.computeTag(tagId, req);
    }

    /**
     * 批量计算多个标签。
     * <p>超过 {@code app.compute.batch-max-size} 时自动分批。</p>
     *
     * @param tagIds 标签 ID 列表
     * @param req    计算请求
     * @return 批量计算结果
     */
    /**
     * 批量计算多个标签（租户隔离：仅计算当前租户自己的标签，其余计入 failed）。
     *
     * @param tagIds 标签 ID 列表
     * @param req    计算请求
     * @return 批量计算结果
     * @throws IllegalStateException 缺少租户上下文（fail-closed）
     */
    public BatchComputeResult batchCompute(List<String> tagIds, ComputeRequest req) {
        return batchCompute(tagIds, req, requireTenant());
    }

    /**
     * 批量计算多个标签（租户隔离）。
     *
     * <p>仅计算属于当前租户的标签，不属于的标签跳过（计入 failed），
     * 防止跨租户计算越权。超过 {@code app.compute.batch-max-size} 时自动分批。</p>
     *
     * @param tagIds   标签 ID 列表
     * @param req      计算请求
     * @param tenantId 租户 ID
     * @return 批量计算结果
     */
    public BatchComputeResult batchCompute(List<String> tagIds, ComputeRequest req, String tenantId) {
        if (tagIds == null || tagIds.isEmpty()) {
            return BatchComputeResult.builder()
                    .results(List.of())
                    .successCount(0)
                    .failedCount(0)
                    .totalCostMs(0)
                    .build();
        }
        // 过滤出属于当前租户的标签
        List<String> ownedTagIds = tagIds.stream()
                .filter(id -> isTenantOwnedTag(id, tenantId))
                .toList();
        int skipped = tagIds.size() - ownedTagIds.size();
        if (skipped > 0) {
            log.warn("ComputeService.batchCompute: skipped {} tags not owned by tenant={}", skipped, tenantId);
        }
        // 不属于当前租户的标签：计入 failed，并补齐 FAILED 结果条目，
        // 保证 results 与请求标签一一对应（调用方据此定位被拒绝的标签）。
        List<TagComputeResult> allResults = new java.util.ArrayList<>(tagIds.size());
        for (String id : tagIds) {
            if (!ownedTagIds.contains(id)) {
                allResults.add(TagComputeResult.builder()
                        .tagId(id)
                        .status("FAILED")
                        .errorMessage("标签不存在或不属于当前租户")
                        .build());
            }
        }
        if (ownedTagIds.isEmpty()) {
            return BatchComputeResult.builder()
                    .results(allResults)
                    .successCount(0)
                    .failedCount(tagIds.size())
                    .totalCostMs(0)
                    .build();
        }
        int total = ownedTagIds.size();
        int batchSize = Math.max(1, batchMaxSize);
        log.info("ComputeService.batchCompute: total={}, batchSize={}, tenant={}", total, batchSize, tenantId);

        long success = 0;
        long failed = skipped;
        long start = System.currentTimeMillis();

        for (int i = 0; i < total; i += batchSize) {
            int end = Math.min(i + batchSize, total);
            List<String> batch = ownedTagIds.subList(i, end);
            BatchComputeResult br = tagStore.batchCompute(batch, req);
            allResults.addAll(br.getResults());
            success += br.getSuccessCount();
            failed += br.getFailedCount();
        }
        return BatchComputeResult.builder()
                .results(allResults)
                .successCount(success)
                .failedCount(failed)
                .totalCostMs(System.currentTimeMillis() - start)
                .build();
    }

    /**
     * 校验标签属于指定租户。
     *
     * @param tagId    标签 ID
     * @param tenantId 租户 ID
     * @return 属于该租户返回 true
     */
    private boolean isTenantOwnedTag(String tagId, String tenantId) {
        if (tagId == null || tenantId == null) {
            return false;
        }
        Optional<TagDefinitionEntity> opt = tagDefRepo.findById(tagId);
        return opt.isPresent() && tenantId.equals(opt.get().getTenantId());
    }

    /**
     * 要求标签属于指定租户，否则抛异常。
     *
     * @param tagId    标签 ID
     * @param tenantId 租户 ID
     * @throws IllegalArgumentException 标签不存在或不属于该租户
     */
    private void requireTenantOwnedTag(String tagId, String tenantId) {
        if (!isTenantOwnedTag(tagId, tenantId)) {
            throw new IllegalArgumentException("tag not found or tenant mismatch: " + tagId);
        }
    }

    /**
     * 取当前租户 ID；缺失时 fail-closed 抛异常。
     *
     * <p>Service 层不信任入参/请求体里的 tenantId，一律以 {@link TenantContext} 为准。</p>
     *
     * @return 当前租户 ID
     * @throws IllegalStateException 租户上下文缺失
     */
    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("ComputeService: 缺少租户上下文，拒绝计算");
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }
}
