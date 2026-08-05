package com.shuqing.bigdata.governance.collector.service;

import com.shuqing.bigdata.governance.collector.model.ColumnMetadata;
import com.shuqing.bigdata.governance.collector.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 元数据写入 Catalog 服务。
 *
 * <p>使用 Spring WebFlux {@link WebClient} 调用 Catalog REST API，
 * 将采集到的 {@link TableMetadata} 写入 Catalog，供血缘/质量/查询等下游消费。</p>
 *
 * <p>Catalog API：
 * <ul>
 *   <li>{@code POST http://catalog:8082/api/v1/catalog/tables} — 创建/更新表元数据</li>
 * </ul></p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>非阻塞 WebClient，避免阻塞采集调度线程</li>
 *   <li>失败重试 3 次，每次间隔 1s（指数退避）</li>
 *   <li>批量写入时单表失败不影响其他表，返回成功计数</li>
 * </ul></p>
 */
@Service
public class MetadataWriterService {

    private static final Logger log = LoggerFactory.getLogger(MetadataWriterService.class);

    /** 默认写入超时时间（秒） */
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    /** 默认重试次数 */
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final WebClient webClient;
    private final String catalogBaseUrl;
    private final long timeoutSeconds;
    private final int maxRetries;

    /**
     * 构造写入服务。
     *
     * @param webClientBuilder WebClient 构造器
     * @param catalogBaseUrl   Catalog 基地址，从配置 {@code app.catalog.base-url} 读取
     * @param timeoutSeconds   单次写入超时秒数，从配置 {@code app.catalog.timeout-seconds} 读取
     * @param maxRetries       最大重试次数，从配置 {@code app.catalog.max-retries} 读取
     */
    public MetadataWriterService(WebClient.Builder webClientBuilder,
                                 @Value("${app.catalog.base-url:http://catalog:8082}") String catalogBaseUrl,
                                 @Value("${app.catalog.timeout-seconds:30}") long timeoutSeconds,
                                 @Value("${app.catalog.max-retries:3}") int maxRetries) {
        this.webClient = webClientBuilder.baseUrl(catalogBaseUrl).build();
        this.catalogBaseUrl = catalogBaseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
    }

    /**
     * 写入单张表元数据到 Catalog。
     *
     * @param metadata 表元数据
     * @return 写入成功返回 {@code true}；失败返回 {@code false}
     */
    public boolean writeTableMetadata(TableMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        Map<String, Object> body = toCatalogPayload(metadata);
        try {
            webClient.post()
                    .uri("/api/v1/catalog/tables")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();
            log.debug("Wrote table {}.{} to catalog", metadata.getDatabaseName(), metadata.getTableName());
            return true;
        } catch (Exception e) {
            log.error("Failed to write table {}.{} to catalog: {}",
                    metadata.getDatabaseName(), metadata.getTableName(), e.getMessage());
            return false;
        }
    }

    /**
     * 批量写入表元数据。
     *
     * <p>逐表写入，单表失败不影响其他表。返回成功写入的表数。</p>
     *
     * @param tables 表元数据列表
     * @return 成功写入的表数
     */
    public int writeBatch(List<TableMetadata> tables) {
        if (tables == null || tables.isEmpty()) {
            return 0;
        }
        AtomicInteger successCount = new AtomicInteger(0);
        for (TableMetadata table : tables) {
            if (writeWithRetry(table)) {
                successCount.incrementAndGet();
            }
        }
        return successCount.get();
    }

    /**
     * 带重试的写入。
     *
     * @param metadata 表元数据
     * @return 最终是否成功
     */
    private boolean writeWithRetry(TableMetadata metadata) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (writeTableMetadata(metadata)) {
                return true;
            }
            if (attempt < maxRetries) {
                try {
                    // 指数退避：1s, 2s, 4s...
                    Thread.sleep(1000L * (1L << (attempt - 1)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 将 {@link TableMetadata} 转换为 Catalog API 请求体。
     *
     * <p>字段命名与 Catalog 服务 {@code Table} 模型对齐：
     * {@code databaseName/tableName/description/columns/partitionKeys/properties}。</p>
     *
     * @param metadata 表元数据
     * @return 请求体 Map
     */
    private Map<String, Object> toCatalogPayload(TableMetadata metadata) {
        Map<String, Object> body = new HashMap<>();
        body.put("databaseName", metadata.getDatabaseName());
        body.put("tableName", metadata.getTableName());
        if (metadata.getDescription() != null) {
            body.put("description", metadata.getDescription());
        }

        // 列：与 Catalog Column 模型对齐
        List<Map<String, Object>> columns = new ArrayList<>();
        if (metadata.getColumns() != null) {
            for (ColumnMetadata col : metadata.getColumns()) {
                Map<String, Object> c = new HashMap<>();
                c.put("name", col.getName());
                c.put("type", col.getType());
                if (col.getComment() != null) {
                    c.put("description", col.getComment());
                }
                c.put("nullable", col.isNullable());
                columns.add(c);
            }
        }
        body.put("columns", columns);

        if (metadata.getPartitionKeys() != null) {
            body.put("partitionKeys", metadata.getPartitionKeys());
        }
        if (metadata.getProperties() != null) {
            body.put("properties", metadata.getProperties());
        }

        // 附加采集源信息，便于 Catalog 区分来源
        Map<String, String> props = metadata.getProperties();
        if (props == null) {
            props = new HashMap<>();
        }
        if (metadata.getSourceType() != null) {
            props.put("_sourceType", metadata.getSourceType());
        }
        if (metadata.getTableType() != null) {
            props.put("_tableType", metadata.getTableType());
        }
        body.put("properties", props);

        return body;
    }

    /**
     * 暴露 Catalog 基地址，供健康检查/调试使用。
     *
     * @return Catalog 基地址
     */
    public String getCatalogBaseUrl() {
        return catalogBaseUrl;
    }
}