package com.shuqing.bigdata.governance.realtime.catalog;

import com.shuqing.bigdata.governance.realtime.model.CatalogCommitEvent;
import com.shuqing.bigdata.governance.realtime.model.TableMetadata;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 元数据采集器。
 *
 * <p>在收到 {@link CatalogCommitEvent} 后异步执行，从 Iceberg REST Catalog 拉取
 * 表元数据（schema、partition、properties、snapshot 统计），构造 {@link TableMetadata}。
 *
 * <p>性能目标：元数据采集延迟（commit → 采集完成）≤ 5s。
 * 通过 Micrometer Timer 暴露 {@code governance.metadata.collect.duration} 指标。
 */
@Component
public class MetadataCollector {

    private static final Logger log = LoggerFactory.getLogger(MetadataCollector.class);

    private final IcebergRestCatalogClient catalogClient;
    private final Timer collectTimer;

    /** 采集结果缓存：tableIdentifier → 最新 TableMetadata */
    private final ConcurrentHashMap<String, TableMetadata> metadataCache = new ConcurrentHashMap<>();

    /** 采集统计 */
    private final ConcurrentHashMap<String, Long> collectStats = new ConcurrentHashMap<>();

    @Autowired
    public MetadataCollector(IcebergRestCatalogClient catalogClient,
                             MeterRegistry meterRegistry) {
        this.catalogClient = catalogClient;
        this.collectTimer = Timer.builder("governance.metadata.collect.duration")
                .description("元数据采集耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /** 测试用构造函数（无 MeterRegistry） */
    public MetadataCollector(IcebergRestCatalogClient catalogClient) {
        this.catalogClient = catalogClient;
        this.collectTimer = null;
    }

    /**
     * 采集指定表的元数据。
     *
     * <p>由 {@code CatalogEventListener} 在收到 commit 事件后异步调用。
     * 采集流程：
     * <ol>
     *   <li>调用 Iceberg REST Catalog {@code GET /v1/namespaces/{ns}/tables/{table}}</li>
     *   <li>解析 schema、partition-spec、properties、current-snapshot-id</li>
     *   <li>构造 {@link TableMetadata}，记录采集耗时</li>
     *   <li>更新缓存，触发下游血缘更新与质量评估</li>
     * </ol>
     *
     * @param event 触发采集的 commit 事件
     * @return 采集到的表元数据；失败时返回 null
     */
    public TableMetadata collect(CatalogCommitEvent event) {
        long start = System.currentTimeMillis();
        String tableId = event.getTableIdentifier();
        log.debug("Collecting metadata for table={}, eventId={}", tableId, event.getEventId());

        try {
            Map<String, Object> rawMetadata = catalogClient.getTableMetadata(
                    event.getNamespace(), event.getTableName());
            if (rawMetadata == null) {
                log.warn("Failed to fetch metadata for {}", tableId);
                collectStats.merge("failureCount", 1L, Long::sum);
                return null;
            }

            TableMetadata metadata = parseMetadata(rawMetadata, event, start);
            metadataCache.put(tableId, metadata);
            collectStats.merge("successCount", 1L, Long::sum);

            long duration = System.currentTimeMillis() - start;
            log.info("Metadata collected for {} in {}ms (snapshotId={})",
                    tableId, duration, metadata.getCurrentSnapshotId());

            if (collectTimer != null) {
                collectTimer.record(java.time.Duration.ofMillis(duration));
            }
            return metadata;
        } catch (Exception e) {
            log.error("Metadata collection failed for {}: {}", tableId, e.getMessage(), e);
            collectStats.merge("failureCount", 1L, Long::sum);
            return null;
        }
    }

    /**
     * 获取缓存的表元数据。
     *
     * @param tableIdentifier 表标识符
     * @return 缓存的元数据；不存在时返回 null
     */
    public TableMetadata getCached(String tableIdentifier) {
        return metadataCache.get(tableIdentifier);
    }

    /**
     * 获取所有缓存的表元数据（用于血缘解析与质量评估读取 schema）。
     *
     * @return 不可变的元数据缓存视图
     */
    public Map<String, TableMetadata> getAllCached() {
        return Collections.unmodifiableMap(metadataCache);
    }

    /**
     * 获取采集统计。
     *
     * @return 包含 successCount、failureCount 的 Map
     */
    public Map<String, Long> getCollectStats() {
        return Collections.unmodifiableMap(collectStats);
    }

    // -----------------------------------------------------------------------
    // 私有方法
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private TableMetadata parseMetadata(Map<String, Object> raw, CatalogCommitEvent event, long start) {
        TableMetadata.TableMetadataBuilder builder = TableMetadata.builder()
                .tableIdentifier(event.getTableIdentifier())
                .namespace(event.getNamespace())
                .tableName(event.getTableName())
                .collectedAt(java.time.Instant.now())
                .collectDurationMs(System.currentTimeMillis() - start);

        // 解析 format-version
        Object formatVersion = raw.get("format-version");
        if (formatVersion instanceof Number) {
            builder.formatVersion(((Number) formatVersion).intValue());
        } else {
            builder.formatVersion(2); // 默认 V2
        }

        // 解析 schema
        Object schemas = raw.get("schemas");
        if (schemas instanceof List && !((List<?>) schemas).isEmpty()) {
            List<Map<String, Object>> schemaList = (List<Map<String, Object>>) schemas;
            // 取当前 schema（最后一个或 current-schema-id 指定的）
            Map<String, Object> currentSchema = schemaList.get(schemaList.size() - 1);
            builder.schema(parseSchema(currentSchema));
        }

        // 解析 partition-spec
        Object partitionSpecs = raw.get("partition-specs");
        if (partitionSpecs instanceof List && !((List<?>) partitionSpecs).isEmpty()) {
            List<Map<String, Object>> specs = (List<Map<String, Object>>) partitionSpecs;
            Map<String, Object> currentSpec = specs.get(specs.size() - 1);
            builder.partitionFields(parsePartitionFields(currentSpec));
            builder.partitionStrategy((String) currentSpec.getOrDefault("spec-id", "identity").toString());
        }

        // 解析 properties
        Object properties = raw.get("properties");
        if (properties instanceof Map) {
            builder.properties((Map<String, String>) properties);
        }

        // 解析 current-snapshot-id 与 snapshot-summary
        Object currentSnapshotId = raw.get("current-snapshot-id");
        if (currentSnapshotId instanceof Number) {
            builder.currentSnapshotId(((Number) currentSnapshotId).longValue());
        }
        Object snapshots = raw.get("snapshots");
        if (snapshots instanceof List && !((List<?>) snapshots).isEmpty()) {
            List<Map<String, Object>> snapshotList = (List<Map<String, Object>>) snapshots;
            Map<String, Object> latestSnapshot = snapshotList.get(snapshotList.size() - 1);
            Object summary = latestSnapshot.get("summary");
            if (summary instanceof Map) {
                builder.snapshotSummary((Map<String, String>) summary);
            }
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private List<TableMetadata.FieldSchema> parseSchema(Map<String, Object> schema) {
        List<TableMetadata.FieldSchema> result = new ArrayList<>();
        Object fields = schema.get("fields");
        if (fields instanceof List) {
            for (Map<String, Object> field : (List<Map<String, Object>>) fields) {
                result.add(TableMetadata.FieldSchema.builder()
                        .fieldId(toInt(field.get("id")))
                        .name((String) field.get("name"))
                        .type(field.get("type") == null ? "string" : field.get("type").toString())
                        .optional(toBool(field.get("optional")))
                        .doc((String) field.get("doc"))
                        .build());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> parsePartitionFields(Map<String, Object> spec) {
        List<String> result = new ArrayList<>();
        Object fields = spec.get("fields");
        if (fields instanceof List) {
            for (Map<String, Object> field : (List<Map<String, Object>>) fields) {
                result.add((String) field.get("source-name"));
            }
        }
        return result;
    }

    private int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private boolean toBool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return false;
    }
}