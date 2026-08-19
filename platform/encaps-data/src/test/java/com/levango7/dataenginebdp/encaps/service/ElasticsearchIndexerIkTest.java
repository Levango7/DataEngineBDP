package com.levango7.dataenginebdp.encaps.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ElasticsearchIndexer + IK 中文分词集成测试。
 *
 * <p>ES 可用时真实执行（本地 ES 7.17 + analysis-ik 容器），不可用自动跳过
 * （CI 无 ES 不阻塞）。验证中文语义分词检索：搜"订单明细"应命中
 * "销售订单明细表"（IK 将两者拆词后匹配，而非单字）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElasticsearchIndexerIkTest {

    private ElasticsearchIndexer indexer;

    @BeforeAll
    void setUp() {
        indexer = new ElasticsearchIndexer("http://127.0.0.1:9201");
        org.junit.jupiter.api.Assumptions.assumeTrue(indexer.isAvailable(),
                "Elasticsearch 不可用（需本地 ES 7.17 + analysis-ik 容器），跳过");
        indexer.ensureIndex();
    }

    private void indexDoc(String docId, String name, String desc) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("docId", docId);
        doc.put("name", name);
        doc.put("type", "table");
        doc.put("source", "asset");
        doc.put("description", desc);
        doc.put("tags", List.of());
        doc.put("createdAt", "2026-08-15T00:00:00Z");
        indexer.indexDoc(doc);
    }

    @Test
    void ikSegmentation_findsChineseSemanticMatch() {
        indexDoc("test-ik-1", "销售订单明细表", "包含订单明细与金额");
        indexDoc("test-ik-2", "用户画像表", "用户标签与行为");

        // 等 ES refresh（默认 1s）
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {
        }

        // IK 语义分词：搜"订单明细"应命中"销售订单明细表"（拆词 [订单,明细] 匹配）
        var sr = indexer.search("订单明细", 0, 10);
        List<Map<String, Object>> results = sr.list();
        boolean hit = results.stream().anyMatch(r -> "test-ik-1".equals(r.get("id")));
        assertThat(hit).as("IK 中文检索应命中 test-ik-1，实际: %s", results).isTrue();

        // 搜"画像"应命中"用户画像表"
        var sr2 = indexer.search("画像", 0, 10);
        List<Map<String, Object>> results2 = sr2.list();
        boolean hit2 = results2.stream().anyMatch(r -> "test-ik-2".equals(r.get("id")));
        assertThat(hit2).as("IK 中文检索应命中 test-ik-2，实际: %s", results2).isTrue();
    }
}
