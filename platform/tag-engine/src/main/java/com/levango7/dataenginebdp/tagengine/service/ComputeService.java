package com.levango7.dataenginebdp.tagengine.service;

import com.levango7.dataenginebdp.tagengine.model.BatchComputeResult;
import com.levango7.dataenginebdp.tagengine.model.ComputeRequest;
import com.levango7.dataenginebdp.tagengine.model.TagComputeResult;
import com.levango7.dataenginebdp.tagengine.store.TagStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

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
 */
@Service
public class ComputeService {

    private static final Logger log = LoggerFactory.getLogger(ComputeService.class);

    private final TagStore tagStore;

    @Value("${app.compute.batch-max-size:100}")
    private int batchMaxSize;

    public ComputeService(TagStore tagStore) {
        this.tagStore = tagStore;
    }

    /**
     * 计算单个标签。
     *
     * @param tagId 标签 ID
     * @param req   计算请求
     * @return 计算结果
     */
    public TagComputeResult computeTag(String tagId, ComputeRequest req) {
        log.info("ComputeService.computeTag: tagId={}, mode={}", tagId, req.getMode());
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
    public BatchComputeResult batchCompute(List<String> tagIds, ComputeRequest req) {
        if (tagIds == null || tagIds.isEmpty()) {
            return BatchComputeResult.builder()
                    .results(List.of())
                    .successCount(0)
                    .failedCount(0)
                    .totalCostMs(0)
                    .build();
        }
        int total = tagIds.size();
        int batchSize = Math.max(1, batchMaxSize);
        log.info("ComputeService.batchCompute: total={}, batchSize={}", total, batchSize);

        List<TagComputeResult> allResults = new java.util.ArrayList<>(total);
        long success = 0;
        long failed = 0;
        long start = System.currentTimeMillis();

        for (int i = 0; i < total; i += batchSize) {
            int end = Math.min(i + batchSize, total);
            List<String> batch = tagIds.subList(i, end);
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
}