package com.shuqing.bigdata.governance.realtime.catalog;

import com.shuqing.bigdata.governance.realtime.model.CatalogCommitEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Iceberg REST Catalog 客户端。
 *
 * <p>封装与 Iceberg REST Catalog V1/V2 API 的交互，提供：
 * <ul>
 *   <li>{@code listTables}：列出命名空间下所有表</li>
 *   <li>{@code getTableMetadata}：获取表元数据（schema、partition、properties、snapshot）</li>
 *   <li>{@code listSnapshots}：列出表快照历史</li>
 *   <li>{@code pollCommitEvents}：轮询新 commit 事件（对比已处理 snapshot-id）</li>
 * </ul>
 *
 * <p>REST API 端点遵循 Iceberg REST Catalog 规范：
 * <ul>
 *   <li>{@code GET /v1/{prefix}/namespaces/{namespace}/tables} - 列表</li>
 *   <li>{@code GET /v1/{prefix}/namespaces/{namespace}/tables/{table}} - 加载表</li>
 *   <li>{@code GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/snapshots} - 快照列表</li>
 * </ul>
 */
@Component
public class IcebergRestCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(IcebergRestCatalogClient.class);

    private final RestClient restClient;
    private final String catalogBaseUrl;

    /** 已处理的最新 snapshot-id 缓存：tableIdentifier → latestSnapshotId */
    private final ConcurrentHashMap<String, Long> processedSnapshots = new ConcurrentHashMap<>();

    /** 轮询统计：成功/失败次数，用于健康检查 */
    private final AtomicLong pollSuccessCount = new AtomicLong(0);
    private final AtomicLong pollFailureCount = new AtomicLong(0);

    public IcebergRestCatalogClient(
            @Value("${governance.iceberg.rest-catalog-url:http://localhost:8181}") String catalogUrl) {
        this.catalogBaseUrl = catalogUrl.endsWith("/")
                ? catalogUrl.substring(0, catalogUrl.length() - 1)
                : catalogUrl;
        this.restClient = RestClient.builder()
                .baseUrl(this.catalogBaseUrl)
                .defaultHeader("Accept", "application/json")
                .build();
        log.info("IcebergRestCatalogClient initialized with baseUrl={}", this.catalogBaseUrl);
    }

    /**
     * 列出命名空间下所有表。
     *
     * @param namespace 命名空间，例如 {@code default}
     * @return 表名列表；失败时返回空列表
     */
    @SuppressWarnings("unchecked")
    public List<String> listTables(String namespace) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/v1/namespaces/{ns}/tables", namespace)
                    .retrieve()
                    .body(Map.class);
            if (response != null && response.containsKey("identifiers")) {
                return (List<String>) response.get("identifiers");
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("listTables failed for namespace={}: {}", namespace, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取表元数据（加载表）。
     *
     * @param namespace 命名空间
     * @param tableName 表名
     * @return 表元数据 Map（含 schema、partition-spec、properties、current-snapshot-id 等）；
     *         失败时返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTableMetadata(String namespace, String tableName) {
        try {
            return restClient.get()
                    .uri("/v1/namespaces/{ns}/tables/{tbl}", namespace, tableName)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.warn("getTableMetadata failed for {}.{}: {}", namespace, tableName, e.getMessage());
            return null;
        }
    }

    /**
     * 列出表快照历史。
     *
     * @param namespace 命名空间
     * @param tableName 表名
     * @return 快照列表；失败时返回空列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listSnapshots(String namespace, String tableName) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/v1/namespaces/{ns}/tables/{tbl}/snapshots", namespace, tableName)
                    .retrieve()
                    .body(Map.class);
            if (response != null && response.containsKey("snapshots")) {
                return (List<Map<String, Object>>) response.get("snapshots");
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("listSnapshots failed for {}.{}: {}", namespace, tableName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 轮询指定表的 commit 事件。
     *
     * <p>对比已处理的最新 snapshot-id 与当前 current-snapshot-id，
     * 若发现新 snapshot 则构造 {@link CatalogCommitEvent} 返回。
     *
     * @param namespace 命名空间
     * @param tableName 表名
     * @return 新的 commit 事件列表（可能有多个连续 commit）；无新事件时返回空列表
     */
    public List<CatalogCommitEvent> pollCommitEvents(String namespace, String tableName) {
        String tableIdentifier = namespace + "." + tableName;
        try {
            List<Map<String, Object>> snapshots = listSnapshots(namespace, tableName);
            if (snapshots.isEmpty()) {
                return Collections.emptyList();
            }

            Long lastProcessed = processedSnapshots.get(tableIdentifier);
            List<CatalogCommitEvent> newEvents = new java.util.ArrayList<>();
            Instant now = Instant.now();

            for (Map<String, Object> snapshot : snapshots) {
                Long snapshotId = toLong(snapshot.get("snapshot-id"));
                if (snapshotId == null) {
                    continue;
                }
                // 首次轮询：记录当前 snapshot-id，不生成事件（避免初始化风暴）
                if (lastProcessed == null) {
                    processedSnapshots.put(tableIdentifier, snapshotId);
                    continue;
                }
                // 只处理比 lastProcessed 更新的 snapshot
                if (snapshotId > lastProcessed) {
                    CatalogCommitEvent event = CatalogCommitEvent.builder()
                            .eventType(determineEventType(snapshot))
                            .eventId(java.util.UUID.randomUUID().toString())
                            .namespace(namespace)
                            .tableName(tableName)
                            .tableIdentifier(tableIdentifier)
                            .oldSnapshotId(lastProcessed)
                            .newSnapshotId(snapshotId)
                            .commitTimestamp(toInstant(snapshot.get("timestamp-ms")))
                            .committer((String) snapshot.getOrDefault("committer", "unknown"))
                            .summary(extractSummary(snapshot))
                            .receivedTimestamp(now)
                            .build();
                    newEvents.add(event);
                    processedSnapshots.put(tableIdentifier, snapshotId);
                }
            }

            pollSuccessCount.incrementAndGet();
            return newEvents;
        } catch (Exception e) {
            pollFailureCount.incrementAndGet();
            log.warn("pollCommitEvents failed for {}: {}", tableIdentifier, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 手动标记某 snapshot 已处理（用于 webhook 模式跳过轮询）。
     *
     * @param tableIdentifier 表标识符
     * @param snapshotId snapshot ID
     */
    public void markProcessed(String tableIdentifier, Long snapshotId) {
        processedSnapshots.put(tableIdentifier, snapshotId);
    }

    /**
     * 获取轮询统计（用于健康检查与指标暴露）。
     *
     * @return 包含 successCount、failureCount 的 Map
     */
    public Map<String, Long> getPollStats() {
        Map<String, Long> stats = new java.util.HashMap<>();
        stats.put("successCount", pollSuccessCount.get());
        stats.put("failureCount", pollFailureCount.get());
        return stats;
    }

    // -----------------------------------------------------------------------
    // 私有辅助方法
    // -----------------------------------------------------------------------

    private String determineEventType(Map<String, Object> snapshot) {
        Object operation = snapshot.get("operation");
        if (operation != null) {
            return operation.toString();
        }
        // 默认根据 summary 推断
        Map<String, String> summary = extractSummary(snapshot);
        if (summary != null) {
            String added = summary.get("added-data-files");
            String deleted = summary.get("deleted-data-files");
            if (added != null && !"0".equals(added) && (deleted == null || "0".equals(deleted))) {
                return "append-snapshot";
            }
            if (deleted != null && !"0".equals(deleted)) {
                return "overwrite-snapshot";
            }
        }
        return "update-snapshot";
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extractSummary(Map<String, Object> snapshot) {
        Object summary = snapshot.get("summary");
        if (summary instanceof Map) {
            return (Map<String, String>) summary;
        }
        return Collections.emptyMap();
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Instant toInstant(Object value) {
        if (value == null) {
            return Instant.now();
        }
        if (value instanceof Number) {
            return Instant.ofEpochMilli(((Number) value).longValue());
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value.toString()));
        } catch (NumberFormatException e) {
            return Instant.now();
        }
    }

    /** 暴露已处理 snapshot 缓存（用于测试断言） */
    Map<String, Long> getProcessedSnapshots() {
        return Collections.unmodifiableMap(processedSnapshots);
    }

    /** 获取 catalog base url（用于测试） */
    public String getCatalogBaseUrl() {
        return catalogBaseUrl;
    }
}