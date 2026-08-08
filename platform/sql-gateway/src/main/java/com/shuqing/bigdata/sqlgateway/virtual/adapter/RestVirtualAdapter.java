package com.shuqing.bigdata.sqlgateway.virtual.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.sqlgateway.virtual.ColumnDefinition;
import com.shuqing.bigdata.sqlgateway.virtual.VirtualTableDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST API 虚拟表查询适配器。
 *
 * <p>将 REST API 响应 JSON 映射为虚拟表数据。连接配置 JSON 格式：</p>
 * <pre>{@code
 * {
 *   "baseUrl": "http://api.example.com",
 *   "method": "GET",
 *   "headers": {"Authorization": "Bearer xxx"},
 *   "authToken": "optional-bearer-token",
 *   "responseDataPath": "data.items",
 *   "timeoutSeconds": 10
 * }
 * }</pre>
 *
 * <p>查询语义：</p>
 * <ul>
 *   <li>{@code getSchema}：返回虚拟表预定义列（REST 无原生 schema）；</li>
 *   <li>{@code query}：调用 REST API，按 {@code responseDataPath} 提取数据数组，
 *       将每个 JSON 对象展开为行；</li>
 *   <li>{@code predicate}：当前不支持谓词下推，全量拉取后由网关层过滤。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class RestVirtualAdapter implements VirtualAdapter {

    private static final Logger log = LoggerFactory.getLogger(RestVirtualAdapter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public List<ColumnDefinition> getSchema(VirtualTableDefinition definition) throws VirtualAdapterException {
        // REST 无原生 schema，返回虚拟表预定义列
        log.debug("获取 schema（REST 返回预定义列）table={}", definition.getTableName());
        if (definition.getColumns() == null || definition.getColumns().isEmpty()) {
            return List.of(
                    new ColumnDefinition("payload", "VARCHAR", true, "REST 响应原始 JSON")
            );
        }
        return definition.getColumns();
    }

    @Override
    public QueryResult query(VirtualTableDefinition definition, String predicate, Integer limit)
            throws VirtualAdapterException {
        log.debug("REST 查询 table={} path={} limit={}",
                definition.getTableName(), definition.getSourceObject(), limit);
        Map<String, Object> config = parseConfig(definition);
        String baseUrl = (String) config.getOrDefault("baseUrl", "");
        String method = (String) config.getOrDefault("method", "GET");
        String authToken = (String) config.get("authToken");
        String responseDataPath = (String) config.getOrDefault("responseDataPath", "");
        int timeoutSec = config.containsKey("timeoutSeconds")
                ? ((Number) config.get("timeoutSeconds")).intValue() : 10;

        String url = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + definition.getSourceObject()
                : baseUrl + definition.getSourceObject();

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec));
            if ("POST".equalsIgnoreCase(method)) {
                reqBuilder.POST(HttpRequest.BodyPublishers.ofString("{}"));
            } else {
                reqBuilder.GET();
            }
            // 注入 headers
            Object headersObj = config.get("headers");
            if (headersObj instanceof Map<?, ?> headersMap) {
                headersMap.forEach((k, v) -> reqBuilder.header(k.toString(), v.toString()));
            }
            if (authToken != null && !authToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + authToken);
            }
            HttpRequest request = reqBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new VirtualAdapterException("REST_HTTP_ERROR",
                        "REST API 返回非 200 状态码: " + response.statusCode() + " body=" + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode dataNode = extractByPath(root, responseDataPath);

            List<String> columns = new ArrayList<>();
            List<List<Object>> rows = new ArrayList<>();
            int maxRows = limit != null && limit > 0 ? limit : Integer.MAX_VALUE;

            if (dataNode.isArray()) {
                // 数组：每元素为一行
                for (JsonNode item : dataNode) {
                    if (rows.size() >= maxRows) {
                        break;
                    }
                    if (columns.isEmpty() && item.isObject()) {
                        item.fieldNames().forEachRemaining(columns::add);
                    }
                    List<Object> row = new ArrayList<>();
                    if (item.isObject()) {
                        for (String col : columns) {
                            row.add(jsonNodeToObject(item.get(col)));
                        }
                    } else {
                        row.add(item.asText());
                    }
                    rows.add(row);
                }
                if (columns.isEmpty()) {
                    columns.add("value");
                }
            } else if (dataNode.isObject()) {
                // 单对象：展开为单行
                dataNode.fieldNames().forEachRemaining(columns::add);
                List<Object> row = new ArrayList<>();
                for (String col : columns) {
                    row.add(jsonNodeToObject(dataNode.get(col)));
                }
                rows.add(row);
            } else {
                // 标量：单行单列
                columns.add("value");
                rows.add(List.of(dataNode.asText()));
            }
            log.debug("REST 查询完成 table={} rows={}", definition.getTableName(), rows.size());
            return new QueryResult(columns, rows);
        } catch (VirtualAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new VirtualAdapterException("REST_QUERY_FAILED",
                    "REST 查询失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean testConnection(VirtualTableDefinition definition) {
        try {
            Map<String, Object> config = parseConfig(definition);
            String baseUrl = (String) config.getOrDefault("baseUrl", "");
            String url = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1) + definition.getSourceObject()
                    : baseUrl + definition.getSourceObject();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 500;
        } catch (Exception e) {
            log.warn("REST 连接测试失败 table={} err={}", definition.getTableName(), e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        log.debug("REST 适配器关闭");
    }

    /**
     * 按点分路径提取 JSON 节点。
     *
     * @param root 根节点
     * @param path 点分路径（如 {@code data.items}），空则返回根
     * @return 提取的节点
     */
    private JsonNode extractByPath(JsonNode root, String path) {
        if (path == null || path.isBlank()) {
            return root;
        }
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            if (current.isObject() && current.has(part)) {
                current = current.get(part);
            } else {
                return root;
            }
        }
        return current;
    }

    /**
     * 将 JsonNode 转为 Java 对象。
     *
     * @param node JSON 节点
     * @return Java 对象
     */
    private Object jsonNodeToObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isLong()) {
            return node.asLong();
        }
        if (node.isDouble()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.toString();
    }

    private Map<String, Object> parseConfig(VirtualTableDefinition definition) {
        try {
            return objectMapper.readValue(definition.getConnectionConfig(),
                    new TypeReference<>() {});
        } catch (Exception e) {
            throw new VirtualAdapterException("CONFIG_PARSE_FAILED",
                    "连接配置解析失败: " + e.getMessage(), e);
        }
    }
}