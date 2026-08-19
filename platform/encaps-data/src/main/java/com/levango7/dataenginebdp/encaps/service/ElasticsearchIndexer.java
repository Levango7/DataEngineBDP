package com.levango7.dataenginebdp.encaps.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 全文检索索引器（真实 ES 链路）。
 *
 * <p>用轻量 RestTemplate 直连 ES REST API（无需 ES 客户端依赖）：
 * 文档写入（assets/apis/standards/templates 统一索引）+ 全文检索。
 * ES 不可用时 {@link #isAvailable()} 返回 false，调用方回退 LIKE 检索。</p>
 *
 * <p>配置：APP_ES_URL 环境变量（默认 http://127.0.0.1:9201，本地演示实例）。</p>
 */
@Slf4j
@Service
public class ElasticsearchIndexer {

    /** 统一索引名。 */
    public static final String INDEX = "shuqing_catalog";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String esUrl;
    private volatile Boolean availableCache;
    private volatile long availableCheckedAt;

    public ElasticsearchIndexer(@Value("${app.elasticsearch.url:http://127.0.0.1:9201}") String esUrl) {
        this.esUrl = esUrl;
        // RestTemplate 必须设置超时（默认无超时会无限挂起，曾导致 /search 卡死）。
        // 用 JDK HttpURLConnection 工厂，零额外依赖。
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** ES 是否可用（探测 / 端点，缓存 10s 内结果）。 */
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        // 10s 缓存：缓存有效直接返回，避免每次请求都探测 ES
        if (availableCache != null && now - availableCheckedAt < 10_000) {
            return availableCache;
        }
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(esUrl + "/", String.class);
            availableCache = resp.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            availableCache = false;
            log.debug("Elasticsearch 不可达: {}", e.getMessage());
        }
        availableCheckedAt = now;
        return availableCache;
    }

    /** 确保索引存在（若不存在则创建 mapping，中文用 IK 分词器）。 */
    public void ensureIndex() {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(esUrl + "/" + INDEX, String.class);
            if (resp.getStatusCode().value() == 404) {
                ObjectNode mapping = objectMapper.createObjectNode();
                ObjectNode props = mapping.putObject("mappings").putObject("properties");
                props.putObject("id").put("type", "keyword");
                // 中文全文检索：IK 分词（ik_max_word 最大分词，召回高）
                ObjectNode name = props.putObject("name");
                name.put("type", "text");
                name.put("analyzer", "ik_max_word");
                name.put("search_analyzer", "ik_smart");
                props.putObject("type").put("type", "keyword");
                props.putObject("source").put("type", "keyword");
                ObjectNode desc = props.putObject("description");
                desc.put("type", "text");
                desc.put("analyzer", "ik_max_word");
                desc.put("search_analyzer", "ik_smart");
                props.putObject("tags").put("type", "keyword");
                props.putObject("createdAt").put("type", "date");
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                restTemplate.put(esUrl + "/" + INDEX, new HttpEntity<>(mapping.toString(), headers));
                log.info("ES 索引 {} 已创建（IK 中文分词）", INDEX);
            }
        } catch (RestClientException e) {
            log.warn("ensureIndex 失败: {}", e.getMessage());
        }
    }

    /** 写入/更新一条文档。 */
    public void indexDoc(Map<String, Object> doc) {
        String docId = String.valueOf(doc.get("docId"));
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("docId", docId);
            body.put("name", String.valueOf(doc.getOrDefault("name", "")));
            body.put("type", String.valueOf(doc.getOrDefault("type", "")));
            body.put("source", String.valueOf(doc.getOrDefault("source", "")));
            body.put("description", doc.getOrDefault("description", "") == null
                    ? "" : String.valueOf(doc.get("description")));
            if (doc.get("tags") != null) {
                body.set("tags", objectMapper.valueToTree(doc.get("tags")));
            }
            body.put("createdAt", String.valueOf(doc.getOrDefault("createdAt", "")));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.put(esUrl + "/" + INDEX + "/_doc/" + docId,
                    new HttpEntity<>(body.toString(), headers));
        } catch (RestClientException e) {
            log.warn("ES 写入失败 docId={}: {}", docId, e.getMessage());
        }
    }

    /**
     * ES 检索结果（命中列表 + 总数，供分页 hasMore 判断）。
     */
    public record SearchResult(List<Map<String, Object>> list, long total) {
    }

    /** 全文检索（query_string），返回命中文档列表 + 总数。 */
    public SearchResult search(String keyword, int from, int size) {
        List<Map<String, Object>> out = new ArrayList<>();
        long total = 0;
        try {
            ObjectNode query = objectMapper.createObjectNode();
            ObjectNode qs = query.putObject("query").putObject("query_string");
            qs.put("query", keyword);
            qs.set("fields", objectMapper.valueToTree(new String[]{"name^3", "description^1", "tags^2"}));
            query.put("from", from);
            query.put("size", size);
            query.putObject("highlight").putObject("fields")
                    .putObject("name").put("number_of_fragments", 0);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    esUrl + "/" + INDEX + "/_search",
                    new HttpEntity<>(query.toString(), headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                return new SearchResult(out, 0);
            }
            JsonNode root = objectMapper.readTree(resp.getBody());
            total = root.path("hits").path("total").path("value").asLong(0);
            JsonNode hits = root.path("hits").path("hits");
            for (JsonNode hit : hits) {
                JsonNode src = hit.path("_source");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", src.path("docId").asText());
                m.put("name", src.path("name").asText());
                m.put("type", src.path("type").asText());
                m.put("source", src.path("source").asText());
                m.put("description", src.path("description").asText());
                m.put("score", hit.path("_score").asDouble(0) / 10.0);
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("ES 检索失败: {}", e.getMessage());
        }
        return new SearchResult(out, total);
    }
}
