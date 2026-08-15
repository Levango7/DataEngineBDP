package com.levango7.dataenginebdp.governance.collector.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.governance.collector.model.CollectionResult;
import com.levango7.dataenginebdp.governance.collector.model.ColumnMetadata;
import com.levango7.dataenginebdp.governance.collector.model.MetadataSource;
import com.levango7.dataenginebdp.governance.collector.model.TableMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Iceberg REST Catalog 元数据采集器（#13 真实 Hook）。
 *
 * <p>通过 Iceberg REST Catalog API 采集元数据：
 * <ol>
 *   <li>{@code GET {url}/v1/{prefix}/namespaces} — 列出命名空间（库）</li>
 *   <li>{@code GET {url}/v1/{prefix}/namespaces/{ns}/tables} — 列出表</li>
 *   <li>{@code GET {url}/v1/{prefix}/namespaces/{ns}/tables/{table}} — 表 schema</li>
 * </ol>
 * 数据源配置：{@code type=iceberg}，{@code url} 指向 REST Catalog 端点，
 * 可选 {@code prefix}（默认 {@code catalog}）。</p>
 */
@Slf4j
@Component
public class IcebergRestMetadataCollector implements MetadataCollector {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestTemplate restTemplate;

    public IcebergRestMetadataCollector() {
        // 必须设置超时（无超时会在 Catalog 不可达时挂起——同类问题教训）
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public String getType() {
        return MetadataSource.TYPE_ICEBERG;
    }

    @Override
    public CollectionResult collect(MetadataSource source) {
        CollectionResult result = new CollectionResult();
        result.setSuccess(false);
        result.setSourceId(source.getId());
        result.setSourceName(source.getName());
        result.setSourceType(getType());
        result.setStartedAt(LocalDateTime.now());
        long start = System.currentTimeMillis();
        try {
            String base = buildBaseUrl(source);
            List<TableMetadata> tables = new ArrayList<>();
            for (String ns : listNamespaces(base)) {
                for (String table : listTables(base, ns)) {
                    TableMetadata metadata = getTableSchema(base, ns, table);
                    if (metadata != null) {
                        tables.add(metadata);
                    }
                }
            }
            result.setTables(tables);
            result.setSuccess(true);
            log.info("Iceberg REST 采集完成: source={}, tables={}", source.getName(), tables.size());
        } catch (Exception e) {
            log.error("Iceberg REST 采集失败: source={}, err={}", source.getName(), e.getMessage());
            result.setErrorMessage("Iceberg REST 采集失败: " + e.getMessage());
        }
        result.setFinishedAt(LocalDateTime.now());
        result.setDurationMs(System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public boolean testConnection(MetadataSource source) {
        try {
            String base = buildBaseUrl(source);
            ResponseEntity<String> resp = restTemplate.getForEntity(base + "/namespaces", String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            log.debug("Iceberg REST 连接测试失败: {}", e.getMessage());
            return false;
        }
    }

    /** 构建 REST Catalog 基础 URL（含 prefix）。 */
    private String buildBaseUrl(MetadataSource source) {
        String url = source.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Iceberg REST Catalog url 不能为空");
        }
        String prefix = source.getConnectionProps() != null
                && source.getConnectionProps().contains("prefix=")
                ? source.getConnectionProps().split("prefix=")[1].split("[;&]")[0]
                : "catalog";
        // url 以 /v1 结尾则直接用，否则拼接 /v1/{prefix}
        String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return trimmed + "/v1/" + prefix;
    }

    /** 列出命名空间（库）。 */
    private List<String> listNamespaces(String base) throws Exception {
        List<String> namespaces = new ArrayList<>();
        ResponseEntity<String> resp = restTemplate.getForEntity(base + "/namespaces", String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            return namespaces;
        }
        JsonNode root = MAPPER.readTree(resp.getBody());
        JsonNode arr = root.path("namespaces");
        if (arr.isArray()) {
            for (JsonNode ns : arr) {
                // 命名空间可以是数组 ["ns"] 或字符串
                if (ns.isArray() && ns.size() > 0) {
                    namespaces.add(ns.get(0).asText());
                } else if (ns.isTextual()) {
                    namespaces.add(ns.asText());
                }
            }
        }
        return namespaces;
    }

    /** 列出命名空间下的表。 */
    private List<String> listTables(String base, String namespace) throws Exception {
        List<String> tables = new ArrayList<>();
        String nsEnc = java.net.URLEncoder.encode(namespace, java.nio.charset.StandardCharsets.UTF_8);
        ResponseEntity<String> resp = restTemplate.getForEntity(base + "/namespaces/" + nsEnc + "/tables", String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            return tables;
        }
        JsonNode root = MAPPER.readTree(resp.getBody());
        JsonNode arr = root.path("identifiers");
        if (arr.isArray()) {
            for (JsonNode id : arr) {
                JsonNode name = id.path("name");
                if (name.isTextual()) {
                    tables.add(name.asText());
                }
            }
        }
        return tables;
    }

    /** 拉取表 schema。 */
    private TableMetadata getTableSchema(String base, String namespace, String table) throws Exception {
        String nsEnc = java.net.URLEncoder.encode(namespace, java.nio.charset.StandardCharsets.UTF_8);
        String tbEnc = java.net.URLEncoder.encode(table, java.nio.charset.StandardCharsets.UTF_8);
        ResponseEntity<String> resp = restTemplate.getForEntity(
                base + "/namespaces/" + nsEnc + "/tables/" + tbEnc, String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            return null;
        }
        JsonNode root = MAPPER.readTree(resp.getBody());

        TableMetadata metadata = new TableMetadata();
        metadata.setDatabaseName(namespace);
        metadata.setTableName(table);
        metadata.setSourceType(getType());
        metadata.setTableType("ICEBERG_TABLE");

        // schema: {"schema":{"fields":[{"name":...,"type":...}]}}
        JsonNode schema = root.path("schema");
        List<ColumnMetadata> columns = new ArrayList<>();
        JsonNode fields = schema.path("fields");
        if (fields.isArray()) {
            for (JsonNode field : fields) {
                ColumnMetadata column = new ColumnMetadata();
                column.setName(field.path("name").asText());
                column.setType(field.path("type").asText());
                columns.add(column);
            }
        }
        metadata.setColumns(columns);

        // 分区键
        JsonNode partitions = root.path("partition-spec").path("fields");
        if (partitions.isArray() && partitions.size() > 0) {
            List<String> partitionKeys = new ArrayList<>();
            for (JsonNode p : partitions) {
                partitionKeys.add(p.path("name").asText());
            }
            metadata.setPartitionKeys(partitionKeys);
        }

        // 快照信息 → properties
        JsonNode snapshots = root.path("snapshots");
        if (snapshots.isArray()) {
            metadata.getProperties().put("snapshotCount", String.valueOf(snapshots.size()));
        }
        return metadata;
    }
}
