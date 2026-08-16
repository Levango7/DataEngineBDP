package com.levango7.dataenginebdp.encaps.service.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Doris 客户端。
 *
 * <p>双通道访问 Doris 集群：
 * <ul>
 *   <li>HTTP 通道：调用 Doris FE HTTP API（/api/show_proc）查询 FE/BE 节点状态</li>
 *   <li>JDBC 通道：通过 MySQL 协议（默认端口 9030）执行 SQL 查询数据库/表/数据</li>
 * </ul>
 * JDBC 驱动通过 {@code Class.forName} 动态加载，运行时 classpath 需有 mysql-connector-j。
 * 连接失败时抛 {@link EngineUnavailableException}，由 Controller 转 503。</p>
 */
@Slf4j
@Service
public class DorisClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Doris FE HTTP 地址，默认 http://localhost:8030 */
    @Value("${app.engine.doris.fe-http-url:http://localhost:8030}")
    private String feHttpUrl;

    /** Doris FE JDBC 地址，默认 jdbc:mysql://localhost:9030 */
    @Value("${app.engine.doris.fe-jdbc-url:jdbc:mysql://localhost:9030}")
    private String feJdbcUrl;

    /** Doris 用户名 */
    @Value("${app.engine.doris.username:root}")
    private String username;

    /** Doris 密码 */
    @Value("${app.engine.doris.password:}")
    private String password;

    /** JDBC 驱动类名（MySQL 协议） */
    @Value("${app.engine.doris.driver-class:com.mysql.cj.jdbc.Driver}")
    private String driverClass;

    /** JDBC 驱动是否已加载标志 */
    private volatile boolean driverLoaded = false;

    /**
     * 列出 Doris 节点（FE + BE）。
     *
     * @return 节点列表，含 host/port/role/status 等
     */
    public List<Map<String, Object>> listNodes() {
        List<Map<String, Object>> result = new ArrayList<>();
        result.addAll(queryProc("/frontends", "FE"));
        result.addAll(queryProc("/backends", "BE"));
        return result;
    }

    /**
     * 调用 Doris FE show_proc 接口查询节点。
     *
     * @param path proc 路径（如 /frontends、/backends）
     * @param role 节点角色（FE/BE）
     * @return 节点列表
     */
    private List<Map<String, Object>> queryProc(String path, String role) {
        JsonNode root = getJson("/api/show_proc?path=" + path);
        JsonNode rows = root.path("data").path("rows");
        JsonNode columns = root.path("data").path("column_names");
        List<String> colNames = new ArrayList<>();
        for (JsonNode c : columns) {
            colNames.add(c.asText());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode row : rows) {
            Map<String, Object> node = new LinkedHashMap<>();
            for (int i = 0; i < colNames.size() && i < row.size(); i++) {
                node.put(colNames.get(i), row.get(i).asText());
            }
            node.put("role", role);
            // 标准化状态字段
            String alive = node.getOrDefault("Alive", "true").toString();
            node.put("status", "true".equalsIgnoreCase(alive) ? "alive" : "dead");
            result.add(node);
        }
        return result;
    }

    /**
     * 列出数据库。
     *
     * @return 数据库名列表
     */
    public List<String> listDatabases() {
        return queryStrings("SHOW DATABASES", 1);
    }

    /**
     * 列出指定数据库的表。
     *
     * @param db 数据库名
     * @return 表名列表
     */
    public List<String> listTables(String db) {
        return queryStrings("SHOW TABLES FROM " + db, 1);
    }

    /**
     * 执行 SQL 查询并返回结构化结果。
     *
     * @param sql SQL 文本
     * @return 含 columns/rows/rowCount/durationMs 的结果
     */
    public Map<String, Object> executeQuery(String sql) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                columns.add(meta.getColumnLabel(i));
            }
            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    row.add(rs.getObject(i));
                }
                rows.add(row);
            }
            result.put("columns", columns);
            result.put("rows", rows);
            result.put("rowCount", rows.size());
            result.put("durationMs", System.currentTimeMillis() - start);
            result.put("status", "SUCCESS");
            return result;
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            result.put("durationMs", System.currentTimeMillis() - start);
            return result;
        }
    }

    /**
     * 执行返回单列字符串的 SQL（如 SHOW DATABASES）。
     *
     * @param sql      SQL 文本
     * @param colIndex 列索引（从 1 开始）
     * @return 字符串列表
     */
    private List<String> queryStrings(String sql, int colIndex) {
        List<String> result = new ArrayList<>();
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString(colIndex));
            }
            return result;
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineUnavailableException("Doris 查询失败: " + e.getMessage(), e);
        }
    }

    /** 打开 JDBC 连接 */
    private Connection openConnection() throws Exception {
        ensureDriver();
        return DriverManager.getConnection(feJdbcUrl, username, password);
    }

    /** 确保 JDBC 驱动已加载（运行时可选） */
    private void ensureDriver() throws ClassNotFoundException {
        if (!driverLoaded) {
            try {
                Class.forName(driverClass);
                driverLoaded = true;
            } catch (ClassNotFoundException e) {
                throw new EngineUnavailableException(
                        "Doris JDBC 驱动缺失: " + driverClass + "，请将 mysql-connector-j 加入 classpath", e);
            }
        }
    }

    /** 发起 GET 请求并解析 JSON */
    private JsonNode getJson(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(feHttpUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new EngineUnavailableException(
                        "Doris FE HTTP 返回 " + resp.statusCode() + ": " + resp.body());
            }
            return MAPPER.readTree(resp.body());
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineUnavailableException("Doris FE 不可用: " + e.getMessage(), e);
        }
    }
}