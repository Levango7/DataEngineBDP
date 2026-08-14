package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.repository.ApiDefinitionRepository;
import com.levango7.dataenginebdp.encaps.repository.AssetRepository;
import com.levango7.dataenginebdp.encaps.repository.StandardRepository;
import com.levango7.dataenginebdp.encaps.repository.TemplateRepository;
import com.levango7.dataenginebdp.encaps.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.ElasticsearchIndexer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 检索门户端点（ROADMAP 前后端接线：前端 /search）。
 *
 * <p>ES 全文检索优先（本地 ES 容器 7.17 实测），ES 不可用时回退
 * 跨资产表 LIKE 检索。ES 索引由 {@link ElasticsearchIndexer} 维护，
 * 文档在资产/API/标准/模板写入时同步（见各 Controller 调用 index 方法）。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/search")
public class SearchController {

    private final AssetRepository assetRepository;
    private final ApiDefinitionRepository apiRepository;
    private final StandardRepository standardRepository;
    private final TemplateRepository templateRepository;
    private final ElasticsearchIndexer esIndexer;

    /** 检索请求体（对齐前端 SearchQuery 最小字段）。 */
    public record SearchRequest(
            String query,
            String mode,
            Integer page,
            Integer pageSize) {
    }

    /** 执行检索（ES 全文检索优先，不可用时回退 LIKE）。 */
    @PostMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> search(@RequestBody SearchRequest req) {
        String tenantId = TenantContext.getTenantId();
        Instant start = Instant.now();
        String q = req.query() == null ? "" : req.query().trim();
        int page = req.page() != null && req.page() > 0 ? req.page() : 1;
        int pageSize = req.pageSize() != null && req.pageSize() > 0 ? req.pageSize() : 20;

        List<Map<String, Object>> results;
        boolean usedEs = false;
        if (!q.isEmpty() && esIndexer.isAvailable()) {
            usedEs = true;
            try {
                esIndexer.ensureIndex();
                syncIndexes(tenantId); // 幂等全量同步（文档 upsert，开销低）
                results = esIndexer.search(q, (page - 1) * pageSize, pageSize);
            } catch (Exception e) {
                log.warn("ES 检索异常，回退 LIKE: {}", e.getMessage());
                results = likeSearch(tenantId, q);
            }
        } else {
            results = likeSearch(tenantId, q);
        }

        int total = results.size();
        long tookMs = Duration.between(start, Instant.now()).toMillis();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", results);
        body.put("total", total);
        body.put("page", page);
        body.put("pageSize", pageSize);
        body.put("tookMs", tookMs);
        body.put("hasMore", usedEs && results.size() >= pageSize);
        body.put("suggestions", List.of());
        body.put("engine", usedEs ? "elasticsearch" : "like");
        return ResponseEntity.ok(body);
    }

    /** 全量同步：将 4 类资产写入 ES 索引（幂等 upsert）。 */
    private void syncIndexes(String tenantId) {
        assetRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).forEach(a -> {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("docId", "asset-" + a.getId());
            doc.put("name", a.getName());
            doc.put("type", a.getType());
            doc.put("source", "asset");
            doc.put("description", a.getDescription());
            doc.put("tags", List.of());
            doc.put("createdAt", a.getCreatedAt() == null ? "" : a.getCreatedAt().toString());
            esIndexer.indexDoc(doc);
        });
        apiRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).forEach(a -> {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("docId", "api-" + a.getId());
            doc.put("name", a.getName());
            doc.put("type", "api");
            doc.put("source", "api");
            doc.put("description", a.getPath());
            doc.put("tags", List.of());
            doc.put("createdAt", a.getCreatedAt() == null ? "" : a.getCreatedAt().toString());
            esIndexer.indexDoc(doc);
        });
        standardRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).forEach(s -> {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("docId", "standard-" + s.getId());
            doc.put("name", s.getName());
            doc.put("type", s.getType());
            doc.put("source", "standard");
            doc.put("description", s.getRule());
            doc.put("tags", List.of());
            doc.put("createdAt", s.getCreatedAt() == null ? "" : s.getCreatedAt().toString());
            esIndexer.indexDoc(doc);
        });
        templateRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).forEach(t -> {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("docId", "template-" + t.getId());
            doc.put("name", t.getName());
            doc.put("type", "template");
            doc.put("source", "template");
            doc.put("description", t.getDescription());
            doc.put("tags", List.of());
            doc.put("createdAt", t.getCreatedAt() == null ? "" : t.getCreatedAt().toString());
            esIndexer.indexDoc(doc);
        });
    }

    /** LIKE 回退检索（跨资产表）。 */
    private List<Map<String, Object>> likeSearch(String tenantId, String q) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (q.isEmpty()) {
            return results;
        }
        assetRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(a -> contains(a.getName(), q) || contains(a.getDescription(), q))
                .forEach(a -> results.add(Map.of(
                        "id", String.valueOf(a.getId()),
                        "name", a.getName(),
                        "type", a.getType(),
                        "source", "asset")));
        apiRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(a -> contains(a.getName(), q) || contains(a.getPath(), q))
                .forEach(a -> results.add(Map.of(
                        "id", String.valueOf(a.getId()),
                        "name", a.getName(),
                        "type", "api",
                        "source", "api")));
        standardRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(s -> contains(s.getName(), q) || contains(s.getRule(), q))
                .forEach(s -> results.add(Map.of(
                        "id", String.valueOf(s.getId()),
                        "name", s.getName(),
                        "type", s.getType(),
                        "source", "standard")));
        templateRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(t -> contains(t.getName(), q) || contains(t.getDescription(), q))
                .forEach(t -> results.add(Map.of(
                        "id", String.valueOf(t.getId()),
                        "name", t.getName(),
                        "type", "template",
                        "source", "template")));
        return results;
    }

    /** 过滤器候选项（从各表聚合名称去重）。 */
    @GetMapping("/facets")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> facets() {
        String tenantId = TenantContext.getTenantId();
        List<String> types = new ArrayList<>();
        assetRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .forEach(a -> types.add("asset:" + a.getType()));
        types.add("api");
        types.add("standard");
        types.add("template");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sources", List.of(
                Map.of("value", "asset", "label", "数据资产"),
                Map.of("value", "api", "label", "API"),
                Map.of("value", "standard", "label", "标准"),
                Map.of("value", "template", "label", "模板")));
        body.put("types", types.stream().distinct().map(t -> Map.of("value", t, "label", t)).toList());
        body.put("tags", List.of());
        return ResponseEntity.ok(body);
    }

    /** 检索建议（基于名称前缀）。 */
    @GetMapping("/suggest")
    @Transactional(readOnly = true)
    public ResponseEntity<List<String>> suggest(@RequestParam String keyword) {
        String tenantId = TenantContext.getTenantId();
        List<String> out = new ArrayList<>();
        if (keyword == null || keyword.isBlank()) {
            return ResponseEntity.ok(out);
        }
        String k = keyword.toLowerCase(Locale.ROOT);
        assetRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(a -> a.getName().toLowerCase(Locale.ROOT).contains(k))
                .limit(5).forEach(a -> out.add(a.getName()));
        apiRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(a -> a.getName().toLowerCase(Locale.ROOT).contains(k))
                .limit(3).forEach(a -> out.add(a.getName()));
        templateRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(t -> t.getName().toLowerCase(Locale.ROOT).contains(k))
                .limit(3).forEach(t -> out.add(t.getName()));
        return ResponseEntity.ok(out);
    }

    /** 检索历史（轻量：内存记录在请求线程，这里返回空——生产由独立存储提供）。 */
    @GetMapping("/history")
    public ResponseEntity<List<Object>> history(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(List.of());
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }
}
