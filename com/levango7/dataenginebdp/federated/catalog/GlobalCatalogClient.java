package com.shuqing.bigdata.federated.catalog;

import com.shuqing.bigdata.federated.config.FederatedQueryProperties;
import com.shuqing.bigdata.federated.model.TableLocation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局 Catalog 客户端（复用 Phase 1 platform/catalog REST API）。
 *
 * <p>platform/catalog 是 Go/Gin 实现的元数据 REST 服务，提供：
 * <ul>
 *   <li>GET  /api/v1/catalog/tables?database={name} - 列出表</li>
 *   <li>GET  /api/v1/catalog/tables/{id}            - 获取表元数据</li>
 *   <li>POST /api/v1/catalog/tables                - 注册表</li>
 * </ul>
 *
 * <p>本客户端通过 {@link WebClient} 调用上述 API，并叠加集群拓扑配置
 * （{@link FederatedQueryProperties#getClusters()}）解析出表所在集群。
 *
 * <p>表与集群的映射规则：
 * <ol>
 *   <li>表元数据中的 {@code properties.cluster} 字段优先</li>
 *   <li>否则按 database 名匹配集群（约定 database 名包含集群标识）</li>
 *   <li>否则默认落到本地集群</li>
 * </ol>
 */
@Slf4j
@Component
public class GlobalCatalogClient {

    private final WebClient webClient;
    private final FederatedQueryProperties props;
    private final Map<String, CachedTable> cache = new ConcurrentHashMap<>();

    public GlobalCatalogClient(WebClient clusterWebClient, FederatedQueryProperties props) {
        this.webClient = clusterWebClient;
        this.props = props;
    }

    /**
     * 列出指定数据库下的所有表元数据。
     *
     * @param database 数据库名（可选，null 则列出全部）
     * @return 表元数据列表（每项为 Catalog 返回的 JSON 对象）
     */
    public Mono<List<Map<String, Object>>> listTables(String database) {
        FederatedQueryProperties.CatalogConfig cfg = props.getCatalog();
        WebClient.RequestHeadersSpec<?> spec;
        if (database != null && !database.isBlank()) {
            spec = webClient.get()
                    .uri(cfg.getBaseUrl() + "/tables?database={db}", database);
        } else {
            spec = webClient.get()
                    .uri(cfg.getBaseUrl() + "/tables");
        }
        return spec.retrieve()
                .bodyToMono(Map.class)
                .map(this::extractDataList)
                .timeout(cfg.getResponseTimeout())
                .onErrorResume(e -> {
                    log.warn("listTables failed for database={}: {}", database, e.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    /**
     * 获取单张表的元数据。
     */
    public Mono<Map<String, Object>> getTable(String tableId) {
        FederatedQueryProperties.CatalogConfig cfg = props.getCatalog();
        return webClient.get()
                .uri(cfg.getBaseUrl() + "/tables/{id}", tableId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m)
                .timeout(cfg.getResponseTimeout())
                .onErrorResume(e -> {
                    log.warn("getTable failed for id={}: {}", tableId, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 定位表所在集群（核心方法）。
     *
     * <p>解析顺序：
     * <ol>
     *   <li>缓存命中（若启用缓存且未过期）</li>
     *   <li>调用 Catalog 列出 database 下的表，匹配 tableName</li>
     *   <li>从表 properties 中提取 cluster，叠加集群拓扑</li>
     * </ol>
     *
     * @param database 数据库名
     * @param table    表名
     * @return 表定位信息，未找到则返回 Mono.empty()
     */
    public Mono<TableLocation> locateTable(String database, String table) {
        String fullName = database + "." + table;
        String cacheKey = fullName;

        // 1. 缓存命中
        if (props.getCatalog().isCacheEnabled()) {
            CachedTable cached = cache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                log.debug("locateTable cache hit: {}", fullName);
                return Mono.just(cached.location);
            }
        }

        // 2. 查询 Catalog
        return listTables(database)
                .flatMap(tables -> {
                    for (Map<String, Object> t : tables) {
                        String tName = (String) t.get("tableName");
                        String dbName = (String) t.getOrDefault("databaseName", database);
                        if (table.equalsIgnoreCase(tName) && database.equalsIgnoreCase(dbName)) {
                            TableLocation loc = buildLocation(database, table, t);
                            // 写入缓存
                            if (props.getCatalog().isCacheEnabled()) {
                                cache.put(cacheKey, new CachedTable(loc, props.getCatalog().getCacheTtl()));
                            }
                            return Mono.just(loc);
                        }
                    }
                    // Catalog 中未找到：按约定降级到本地集群（仍可执行本地查询）
                    log.warn("Table {} not found in catalog, fallback to local cluster", fullName);
                    TableLocation fallback = buildLocalFallback(database, table);
                    return Mono.just(fallback);
                });
    }

    /**
     * 批量定位多张表。
     */
    public Mono<Map<String, TableLocation>> locateTables(List<String> fullNames) {
        List<Mono<TableLocation>> monos = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (String fn : fullNames) {
            String[] parts = fn.split("\\.", 2);
            String db = parts.length > 1 ? parts[0] : "default";
            String tbl = parts.length > 1 ? parts[1] : parts[0];
            keys.add(fn);
            monos.add(locateTable(db, tbl));
        }
        return Mono.zip(monos, results -> {
            Map<String, TableLocation> map = new LinkedHashMap<>();
            for (int i = 0; i < results.length; i++) {
                if (results[i] != null) {
                    map.put(keys.get(i), (TableLocation) results[i]);
                }
            }
            return map;
        });
    }

    /**
     * 清空表元数据缓存。
     */
    public void invalidateCache() {
        cache.clear();
        log.info("Catalog cache invalidated");
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractDataList(Map resp) {
        Object data = resp.get("data");
        if (data instanceof List) {
            return (List<Map<String, Object>>) data;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private TableLocation buildLocation(String database, String table, Map<String, Object> catalogTable) {
        String tableId = (String) catalogTable.get("id");
        Map<String, String> schema = extractSchema(catalogTable);
        Map<String, Object> properties = (Map<String, Object>) catalogTable.getOrDefault("properties", Collections.emptyMap());

        // 解析集群：properties.cluster 优先
        String cluster = (String) properties.get("cluster");
        boolean sharded = Boolean.parseBoolean(String.valueOf(properties.getOrDefault("sharded", "false")));
        List<String> shardClusters = (List<String>) properties.get("shardClusters");

        if (cluster == null || cluster.isBlank()) {
            // 按 database 名约定匹配集群
            cluster = matchClusterByDatabase(database);
        }
        if (shardClusters == null) {
            shardClusters = Collections.emptyList();
        }

        FederatedQueryProperties.ClusterEndpoint endpoint = props.getClusters().get(cluster);
        String clusterUrl = endpoint != null ? endpoint.getUrl() : null;
        String clusterType = endpoint != null ? endpoint.getType() : "unknown";
        boolean isLocal = endpoint != null ? endpoint.isLocal() : props.getLocalCluster().equals(cluster);

        return TableLocation.builder()
                .fullName(database + "." + table)
                .database(database)
                .table(table)
                .cluster(cluster)
                .clusterUrl(clusterUrl)
                .clusterType(clusterType)
                .local(isLocal)
                .tableId(tableId)
                .schema(schema)
                .sharded(sharded)
                .shardClusters(shardClusters)
                .build();
    }

    private TableLocation buildLocalFallback(String database, String table) {
        String localCluster = props.getLocalCluster();
        FederatedQueryProperties.ClusterEndpoint endpoint = props.getClusters().get(localCluster);
        return TableLocation.builder()
                .fullName(database + "." + table)
                .database(database)
                .table(table)
                .cluster(localCluster)
                .clusterUrl(endpoint != null ? endpoint.getUrl() : null)
                .clusterType(endpoint != null ? endpoint.getType() : "local")
                .local(true)
                .sharded(false)
                .shardClusters(Collections.emptyList())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extractSchema(Map<String, Object> catalogTable) {
        Map<String, String> schema = new LinkedHashMap<>();
        Object cols = catalogTable.get("columns");
        if (cols instanceof List) {
            for (Object c : (List<Object>) cols) {
                if (c instanceof Map) {
                    String name = (String) ((Map<String, Object>) c).get("name");
                    String type = (String) ((Map<String, Object>) c).get("type");
                    if (name != null) {
                        schema.put(name, type != null ? type : "STRING");
                    }
                }
            }
        }
        return schema;
    }

    private String matchClusterByDatabase(String database) {
        if (database == null) {
            return props.getLocalCluster();
        }
        String dbLower = database.toLowerCase();
        for (Map.Entry<String, FederatedQueryProperties.ClusterEndpoint> e : props.getClusters().entrySet()) {
            String clusterName = e.getKey().toLowerCase();
            // 约定：database 名包含集群标识（如 orders_xinchang、orders_local）
            if (dbLower.contains(clusterName) || dbLower.endsWith("_" + clusterName)) {
                return e.getKey();
            }
        }
        return props.getLocalCluster();
    }

    /** 缓存条目。 */
    private static class CachedTable {
        final TableLocation location;
        final long expireAt;

        CachedTable(TableLocation location, Duration ttl) {
            this.location = location;
            this.expireAt = System.currentTimeMillis() + ttl.toMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}