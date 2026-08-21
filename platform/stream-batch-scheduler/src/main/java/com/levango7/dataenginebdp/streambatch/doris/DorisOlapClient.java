package com.levango7.dataenginebdp.streambatch.doris;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.streambatch.router.ViewRouterConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Doris OLAP 查询客户端（真实调用 Doris FE HTTP SQL API）。
 *
 * <p>核心职责：
 * <ol>
 *   <li>通过 Doris FE HTTP API（{@code POST /api/<db>/<query>}）执行 OLAP 查询</li>
 *   <li>命中物化视图时返回毫秒级结果（与 Phase 1 T016 对齐）</li>
 *   <li>支持 External Catalog 直读 Iceberg（湖仓集联动）</li>
 *   <li>触发物化视图刷新（{@code POST /api/<db>/<mv>/_refresh}）</li>
 * </ol>
 *
 * <p><b>真实调用路径</b>：默认 {@code realCallEnabled=true} 通过 OkHttp 真实调用 Doris FE；
 * 集群不可达时由调用方决定回退策略（不在此处隐式回退，避免掩盖问题）。
 *
 * <p>与 {@link com.levango7.dataenginebdp.streambatch.router.DorisMaterializedViewIntegration}
 * 的关系：本客户端提供底层 HTTP 调用能力，{@code DorisMaterializedViewIntegration}
 * 在 BI 视图路由器中复用本客户端触发物化视图刷新与查询。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DorisOlapClient {

    private final ViewRouterConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行 Doris OLAP 查询（真实调用 FE HTTP SQL API）。
     *
     * <p>调用 Doris FE {@code POST /api/<db>}，body 为 SQL 语句，
     * Doris 返回 JSON 结果集（含 columnNames 与 data 二维数组）。
     *
     * @param database Doris 数据库名
     * @param sql      SQL 查询语句
     * @return 查询结果（含列名与行数据）
     * @throws DorisOlapException 查询失败（HTTP 非 200 / 解析异常 / 网络异常）
     */
    public DorisQueryResult query(String database, String sql) throws DorisOlapException {
        long startMs = System.currentTimeMillis();
        String feRest = config.getDorisFeRest();
        String url = String.format("%s/api/%s", feRest, database);

        log.info("Doris OLAP 查询: url={}, sql={}", url, sql);

        OkHttpClient client = buildHttpClient();
        RequestBody body = RequestBody.create(sql, MediaType.parse("text/plain; charset=utf-8"));
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .header("Authorization", okhttp3.Credentials.basic(
                        config.getDorisUser(), config.getDorisPassword()))
                .header("Content-Type", "text/plain; charset=utf-8")
                .build();

        try (Response response = client.newCall(request).execute()) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            String respBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("Doris 查询失败: httpCode={}, body={}, elapsedMs={}",
                        response.code(), respBody, elapsedMs);
                throw new DorisOlapException(String.format(
                        "Doris 查询失败: httpCode=%d, db=%s, sql=%s, resp=%s",
                        response.code(), database, sql, respBody));
            }

            DorisQueryResult result = parseQueryResult(respBody);
            result.setElapsedMs(elapsedMs);
            result.setDatabase(database);
            result.setSql(sql);
            log.info("Doris 查询成功: rows={}, elapsedMs={}", result.getRowCount(), elapsedMs);
            return result;
        } catch (IOException e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.error("Doris 查询 IO 异常: url={}, elapsedMs={}, err={}", url, elapsedMs, e.getMessage());
            throw new DorisOlapException(String.format(
                    "Doris 查询 IO 异常: url=%s, err=%s", url, e.getMessage()), e);
        }
    }

    /**
     * 触发物化视图刷新（真实调用 Doris FE REST API）。
     *
     * <p>调用 {@code POST /api/<db>/<mv>/_refresh}，Doris 异步刷新物化视图。
     *
     * @param database         Doris 数据库
     * @param materializedView 物化视图名
     * @return {@code true} 表示刷新请求成功（不代表刷新已完成，Doris 异步执行）
     * @throws DorisOlapException 刷新请求失败
     */
    public boolean refreshMaterializedView(String database, String materializedView) throws DorisOlapException {
        String url = String.format("%s/api/%s/%s/_refresh",
                config.getDorisFeRest(), database, materializedView);
        log.info("触发 Doris 物化视图刷新: url={}", url);

        OkHttpClient client = buildHttpClient();
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create("", MediaType.parse("application/json")))
                .header("Authorization", okhttp3.Credentials.basic(
                        config.getDorisUser(), config.getDorisPassword()))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                log.error("物化视图刷新失败: httpCode={}, body={}", response.code(), body);
                throw new DorisOlapException(String.format(
                        "物化视图刷新失败: httpCode=%d, mv=%s.%s, resp=%s",
                        response.code(), database, materializedView, body));
            }
            log.info("物化视图刷新请求成功: {}.{}", database, materializedView);
            return true;
        } catch (IOException e) {
            throw new DorisOlapException(String.format(
                    "物化视图刷新 IO 异常: url=%s, err=%s", url, e.getMessage()), e);
        }
    }

    /**
     * 创建 Doris External Catalog（直读 Iceberg，湖仓集联动）。
     *
     * <p>通过 Doris FE SQL API 执行 {@code CREATE EXTERNAL CATALOG} 语句，
     * 使 Doris 可直接查询 Iceberg 表（无需数据导入）。
     *
     * @param catalogName  Catalog 名称
     * @param icebergWarehouse Iceberg warehouse 路径
     * @param icebergCatalogType Iceberg catalog 类型（hive / rest / hadoop）
     * @return {@code true} 创建成功
     * @throws DorisOlapException 创建失败
     */
    public boolean createIcebergExternalCatalog(
            String catalogName, String icebergWarehouse, String icebergCatalogType) throws DorisOlapException {
        String sql = String.format(
                "CREATE EXTERNAL CATALOG IF NOT EXISTS %s PROPERTIES ("
                        + "\"type\" = \"iceberg\", "
                        + "\"iceberg.catalog.type\" = \"%s\", "
                        + "\"warehouse\" = \"%s\""
                        + ")",
                catalogName, icebergCatalogType, icebergWarehouse);
        log.info("创建 Doris Iceberg External Catalog: catalogName={}, warehouse={}",
                catalogName, icebergWarehouse);
        // 通过 query 走默认数据库（Doris 支持 CREATE CATALOG 不依赖具体 db）
        DorisQueryResult result = query("information_schema", sql);
        return result != null;
    }

    /**
     * 解析 Doris FE HTTP API 返回的 JSON 结果集。
     *
     * <p>Doris FE /api/&lt;db&gt; 返回格式：
     * <pre>
     * {
     *   "code": "0",
     *   "msg": "success",
     *   "data": {
     *     "column_names": ["col1", "col2", ...],
     *     "rows": [["v11", "v12", ...], ["v21", "v22", ...]]
     *   }
     * }
     * </pre>
     * 或错误格式：
     * <pre>
     * { "code": "1", "msg": "error message" }
     * </pre>
     */
    private DorisQueryResult parseQueryResult(String respBody) throws DorisOlapException {
        try {
            JsonNode root = objectMapper.readTree(respBody);
            // Doris 返回 code 字段，"0" 表示成功
            String code = root.path("code").asText("");
            if (!code.isEmpty() && !"0".equals(code)) {
                String msg = root.path("msg").asText("unknown error");
                throw new DorisOlapException(String.format(
                        "Doris 返回错误: code=%s, msg=%s", code, msg));
            }

            JsonNode dataNode = root.path("data");
            List<String> columnNames = new ArrayList<>();
            List<Map<String, Object>> rows = new ArrayList<>();

            if (!dataNode.isMissingNode()) {
                JsonNode colsNode = dataNode.path("column_names");
                if (colsNode.isArray()) {
                    for (JsonNode col : colsNode) {
                        columnNames.add(col.asText());
                    }
                }

                JsonNode rowsNode = dataNode.path("rows");
                if (rowsNode.isArray()) {
                    for (JsonNode row : rowsNode) {
                        Map<String, Object> rowMap = new LinkedHashMap<>();
                        if (row.isArray()) {
                            for (int i = 0; i < row.size() && i < columnNames.size(); i++) {
                                JsonNode cell = row.get(i);
                                rowMap.put(columnNames.get(i),
                                        cell.isNull() ? null : parseCell(cell));
                            }
                        }
                        rows.add(rowMap);
                    }
                }
            }

            return DorisQueryResult.builder()
                    .columnNames(columnNames)
                    .rows(rows)
                    .success(true)
                    .rawResponse(respBody)
                    .build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new DorisOlapException("Doris 响应 JSON 解析失败: " + e.getMessage()
                    + ", resp=" + respBody, e);
        }
    }

    /**
     * 解析 JSON 单元格值（支持数字 / 字符串 / 布尔）。
     */
    private Object parseCell(JsonNode cell) {
        if (cell.isNumber()) {
            return cell.numberValue();
        }
        if (cell.isBoolean()) {
            return cell.booleanValue();
        }
        return cell.asText();
    }

    /**
     * 构建 OkHttp 客户端（含超时配置）。
     */
    private OkHttpClient buildHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 批量查询统计信息（用于 BI 看板延迟验证）。
     *
     * @param database 数据库
     * @param table    表名
     * @return 统计信息（rowCount / sizeMB 等）
     */
    public Map<String, Object> queryTableStats(String database, String table) throws DorisOlapException {
        String sql = String.format(
                "SELECT COUNT(*) AS row_count FROM `%s`.`%s`", database, table);
        DorisQueryResult result = query(database, sql);
        Map<String, Object> stats = new HashMap<>();
        stats.put("database", database);
        stats.put("table", table);
        stats.put("elapsedMs", result.getElapsedMs());
        if (!result.getRows().isEmpty()) {
            stats.put("rowCount", result.getRows().get(0).getOrDefault("row_count", 0));
        }
        return stats;
    }
}