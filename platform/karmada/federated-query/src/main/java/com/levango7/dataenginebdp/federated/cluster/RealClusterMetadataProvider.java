package com.levango7.dataenginebdp.federated.cluster;

import com.levango7.dataenginebdp.federated.governance.FederatedGovernanceView;
import com.levango7.dataenginebdp.federated.governance.FederatedMetadataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实集群元数据提供者。
 *
 * <p>通过 Karmada 控制面 REST API 获取联邦集群列表，再调用各集群的 Catalog API
 * 拉取表元数据，将结果映射为 {@link FederatedGovernanceView.TableMetadata}。
 *
 * <p>调用链：
 * <ol>
 *   <li>若配置了 {@code federated.cluster.karmada-api}，则调用
 *       {@code GET /apis/cluster.karmada.io/v1alpha1/clusters} 获取 member cluster 列表</li>
 *   <li>对指定 clusterId，调用其 Catalog API {@code GET /api/v1/catalog/tables} 获取表元数据</li>
 *   <li>将 JSON 响应映射为 {@link FederatedGovernanceView.TableMetadata}</li>
 * </ol>
 *
 * <p>mTLS：复用 {@link com.levango7.dataenginebdp.federated.config.MtlsConfig#clusterWebClient}
 * 构造的 {@link WebClient}，自动装载双向证书。
 */
@Slf4j
public class RealClusterMetadataProvider implements FederatedMetadataService.ClusterMetadataProvider {

    private static final String KARMADA_CLUSTERS_PATH =
            "/apis/cluster.karmada.io/v1alpha1/clusters";

    private final WebClient webClient;
    private final FederatedClusterProperties props;

    public RealClusterMetadataProvider(WebClient webClient, FederatedClusterProperties props) {
        this.webClient = webClient;
        this.props = props;
    }

    @Override
    public List<FederatedGovernanceView.TableMetadata> fetchTableMetadata(String clusterId) {
        if (clusterId == null || clusterId.isEmpty()) {
            return Collections.emptyList();
        }
        FederatedClusterProperties.ClusterConfig cluster = props.findCluster(clusterId);
        if (cluster == null || cluster.getCatalogUrl() == null) {
            log.warn("Cluster {} not configured or catalogUrl missing, return empty", clusterId);
            return Collections.emptyList();
        }
        if (!cluster.isEnabled()) {
            log.info("Cluster {} disabled, skip metadata fetch", clusterId);
            return Collections.emptyList();
        }

        String catalogUrl = cluster.getCatalogUrl();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = webClient.get()
                    .uri(catalogUrl + "/api/v1/catalog/tables")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(props.getResponseTimeout())
                    .block();
            return parseTables(resp, clusterId);
        } catch (Exception e) {
            log.error("Fetch table metadata failed: cluster={} url={} err={}",
                    clusterId, catalogUrl, e.getMessage(), e);
            throw new RuntimeException("Fetch table metadata failed for cluster " + clusterId
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * 通过 Karmada 控制面 API 获取所有 member cluster 名称列表。
     *
     * @return 集群名列表，未配置 Karmada API 则返回配置的 clusters 列表
     */
    public List<String> listClustersFromKarmada() {
        String karmadaApi = props.getKarmadaApi();
        if (karmadaApi == null || karmadaApi.isBlank()) {
            return props.getEnabledClusterNames();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = webClient.get()
                    .uri(karmadaApi + KARMADA_CLUSTERS_PATH)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(props.getResponseTimeout())
                    .block();
            return parseClusterNames(resp);
        } catch (Exception e) {
            log.error("List clusters from Karmada failed: {} err={}", karmadaApi, e.getMessage(), e);
            return props.getEnabledClusterNames();
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<FederatedGovernanceView.TableMetadata> parseTables(Map<String, Object> resp, String clusterId) {
        if (resp == null) {
            return Collections.emptyList();
        }
        Object data = resp.get("data");
        if (!(data instanceof List)) {
            // 兼容直接返回列表的 API
            if (resp.containsKey("tableName")) {
                return List.of(parseTable(resp, clusterId));
            }
            return Collections.emptyList();
        }
        List<FederatedGovernanceView.TableMetadata> tables = new ArrayList<>();
        for (Object item : (List<Object>) data) {
            if (item instanceof Map) {
                tables.add(parseTable((Map<String, Object>) item, clusterId));
            }
        }
        return tables;
    }

    @SuppressWarnings("unchecked")
    private FederatedGovernanceView.TableMetadata parseTable(Map<String, Object> raw, String clusterId) {
        String database = str(raw.getOrDefault("databaseName", "default"));
        String table = str(raw.getOrDefault("tableName", "unknown"));
        String tableId = str(raw.getOrDefault("id", clusterId + ":" + database + "." + table));
        String tableType = str(raw.getOrDefault("tableType", "MANAGED"));
        String description = str(raw.get("description"));
        Long rowCount = longValue(raw.get("rowCount"));
        Long sizeInBytes = longValue(raw.get("sizeInBytes"));
        Instant lastModified = instantValue(raw.get("lastModified"));

        List<FederatedGovernanceView.ColumnMetadata> columns = new ArrayList<>();
        Object cols = raw.get("columns");
        if (cols instanceof List) {
            int ordinal = 0;
            for (Object c : (List<Object>) cols) {
                if (c instanceof Map) {
                    Map<String, Object> colMap = (Map<String, Object>) c;
                    columns.add(FederatedGovernanceView.ColumnMetadata.builder()
                            .name(str(colMap.getOrDefault("name", "col" + ordinal)))
                            .type(str(colMap.getOrDefault("type", "STRING")))
                            .nullable(boolValue(colMap.get("nullable"), true))
                            .primaryKey(boolValue(colMap.get("primaryKey"), false))
                            .comment(str(colMap.get("comment")))
                            .ordinal(intValue(colMap.getOrDefault("ordinal", ordinal), ordinal))
                            .build());
                }
                ordinal++;
            }
        }

        Map<String, String> properties = new LinkedHashMap<>();
        Object propsObj = raw.get("properties");
        if (propsObj instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) propsObj).entrySet()) {
                if (e.getValue() != null) {
                    properties.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
        }

        return FederatedGovernanceView.TableMetadata.builder()
                .tableId(tableId)
                .database(database)
                .table(table)
                .fullName(database + "." + table)
                .clusterId(clusterId)
                .tableType(tableType)
                .columns(columns)
                .description(description)
                .properties(properties)
                .rowCount(rowCount)
                .sizeInBytes(sizeInBytes)
                .lastModified(lastModified)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<String> parseClusterNames(Map<String, Object> resp) {
        if (resp == null) {
            return Collections.emptyList();
        }
        Object items = resp.get("items");
        if (!(items instanceof List)) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (Object item : (List<Object>) items) {
            if (item instanceof Map) {
                Map<String, Object> meta = (Map<String, Object>) ((Map<String, Object>) item).get("metadata");
                if (meta != null) {
                    String name = str(meta.get("name"));
                    if (name != null && !name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    // ------------------------------------------------------------------
    // 类型转换工具
    // ------------------------------------------------------------------

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intValue(Object o, int def) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o));
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    private static Long longValue(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o != null) {
            try {
                return Long.parseLong(String.valueOf(o));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean boolValue(Object o, boolean def) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o != null) {
            return Boolean.parseBoolean(String.valueOf(o));
        }
        return def;
    }

    private static Instant instantValue(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}