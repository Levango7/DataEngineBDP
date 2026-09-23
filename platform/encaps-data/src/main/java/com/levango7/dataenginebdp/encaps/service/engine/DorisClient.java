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

    /** P3: JDBC 查询超时（秒）。 */
    private static final int JDBC_QUERY_TIMEOUT_SECONDS = 30;

    /**
     * 列出 Doris 节点（FE + BE）。
     *
     * <p>R13 安全修复：添加 tenantId 参数用于审计日志，HTTP API 本身不支持租户隔离，
     * 但记录 tenantId 便于追踪。</p>
     *
     * <p><b>多租户共享说明（R14 文档标注）</b>：Doris 节点为基础设施级共享资源，
     * 多租户共享为设计行为。节点列表用于集群运维监控，不暴露租户数据，
     * 因此无需按租户隔离。租户隔离在数据层（数据库/表级）通过 SET @tenant_id
     * 与行级安全策略实现，节点层不涉及租户数据访问。</p>
     *
     * @param tenantId 租户 ID（来自 JWT，用于审计）
     * @return 节点列表，含 host/port/role/status 等
     */
    public List<Map<String, Object>> listNodes(String tenantId) {
        log.debug("listNodes tenant={}", tenantId);
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
     * <p>R13 安全修复：添加 tenantId 参数，通过 SET @tenant_id 设置会话变量，
     * 供 Doris 视图/行级安全策略实现租户隔离。</p>
     *
     * @param tenantId 租户 ID（来自 JWT）
     * @return 数据库名列表
     */
    public List<String> listDatabases(String tenantId) {
        return queryStrings("SHOW DATABASES", 1, tenantId);
    }

    /**
     * 列出指定数据库的表。
     *
     * <p>R13 安全修复：添加 tenantId 参数，通过 SET @tenant_id 设置会话变量，
     * 供 Doris 视图/行级安全策略实现租户隔离。</p>
     *
     * <p>R14 安全修复：对 db 参数添加标识符白名单校验（仅允许字母数字下划线），
     * 并用反引号包裹标识符，防止 SQL 注入。</p>
     *
     * @param db       数据库名
     * @param tenantId 租户 ID（来自 JWT）
     * @return 表名列表
     * @throws IllegalArgumentException 若 db 不是合法标识符
     */
    public List<String> listTables(String db, String tenantId) {
        validateIdentifier(db);
        return queryStrings("SHOW TABLES FROM `" + db + "`", 1, tenantId);
    }

    /**
     * 校验标识符合法性（R14 安全修复）。
     *
     * <p>仅允许字母、数字、下划线，防止 SQL 注入。
     * 标识符包括数据库名、表名、Catalog 名等。</p>
     *
     * @param name 待校验的标识符
     * @throws IllegalArgumentException 若标识符非法（含空、null 或非法字符）
     */
    private void validateIdentifier(String name) {
        if (name == null || !name.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("非法标识符: " + name);
        }
    }

    /**
     * 执行 SQL 查询并返回结构化结果。
     *
     * <p>P2-9: 此重载不传递 tenantId，无法实现租户隔离。
     * 已标记为 {@code @Deprecated}，请使用 {@link #executeQuery(String, String)} 代替。</p>
     *
     * @param sql SQL 文本
     * @return 含 columns/rows/rowCount/durationMs 的结果
     * @deprecated 使用 {@link #executeQuery(String, String)} 传递 tenantId 实现租户隔离
     */
    @Deprecated(since = "R17", forRemoval = true)
    public Map<String, Object> executeQuery(String sql) {
        return executeQuery(sql, null);
    }

    /**
     * 执行 SQL 查询并返回结构化结果（带租户隔离，R12 安全修复）。
     *
     * <p>当 tenantId 非空时，在执行查询前通过 {@code SET @tenant_id = ?} 设置会话变量，
     * 供 Doris 视图/行级安全策略实现租户隔离。P2-8: 不在结果中返回 tenantId，防信息泄露
     * （tenantId 仅用于审计日志）。</p>
     *
     * @param sql      SQL 文本
     * @param tenantId 租户 ID（来自 JWT，可为 null 表示不强制租户隔离）
     * @return 含 columns/rows/rowCount/durationMs 的结果
     */
    public Map<String, Object> executeQuery(String sql, String tenantId) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = openConnection()) {
            // R12 安全修复：设置租户会话变量，供 Doris 视图/行级安全策略使用
            if (tenantId != null && !tenantId.isBlank()) {
                try (Statement setStmt = conn.createStatement()) {
                    // 2026-09-23 加固：原先仅做 `'` → `''` 转义，**未处理反斜杠**。
                    // MySQL/Doris 默认启用反斜杠转义（未设 NO_BACKSLASH_ESCAPES），
                    // 此时 `\` 可吃掉后续的闭合引号，使 `'` 转义失效：
                    //   tenantId = `a\` → SQL 片段 `'a\'` → 字符串未闭合。
                    // 故须**先转义反斜杠、再转义引号**（顺序不可颠倒）。
                    // 注：tenantId 来自 TenantContext（JWT），非请求体；且
                    // TenantPathMapper 本就拒绝含 `\` 的租户 ID，故本改动
                    // 不改变任何合法输入的行为。此为纵深防御。
                    String safeTenant = tenantId.replace("\\", "\\\\").replace("'", "''");
                    setStmt.execute("SET @tenant_id = '" + safeTenant + "'");
                }
            }
            try (Statement stmt = conn.createStatement()) {
                // P3: 设置查询超时
                stmt.setQueryTimeout(JDBC_QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = stmt.executeQuery(sql)) {
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
                // P2-8: 不在结果中返回 tenantId，防信息泄露（tenantId 仅用于审计日志）
                return result;
                }
            }
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // P3-5: 异常消息泛化，不暴露 DB 结构
            result.put("status", "FAILED");
            result.put("error", "查询执行失败");
            result.put("durationMs", System.currentTimeMillis() - start);
            log.warn("Doris 查询失败（详情已隐藏）: {}", e.getMessage());
            return result;
        }
    }

    /**
     * 执行返回单列字符串的 SQL（如 SHOW DATABASES）。
     *
     * <p>R13 安全修复：添加 tenantId 参数，在查询前设置会话变量实现租户隔离。</p>
     *
     * @param sql      SQL 文本
     * @param colIndex 列索引（从 1 开始）
     * @param tenantId 租户 ID（来自 JWT，可为 null 表示不强制租户隔离）
     * @return 字符串列表
     */
    private List<String> queryStrings(String sql, int colIndex, String tenantId) {
        List<String> result = new ArrayList<>();
        try (Connection conn = openConnection()) {
            // R13 安全修复：设置租户会话变量
            if (tenantId != null && !tenantId.isBlank()) {
                try (Statement setStmt = conn.createStatement()) {
                    // 2026-09-23 加固：同 executeQuery —— 反斜杠须先于引号转义。
                    String safeTenant = tenantId.replace("\\", "\\\\").replace("'", "''");
                    setStmt.execute("SET @tenant_id = '" + safeTenant + "'");
                }
            }
            try (Statement stmt = conn.createStatement()) {
                // P3: 设置查询超时
                stmt.setQueryTimeout(JDBC_QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        result.add(rs.getString(colIndex));
                    }
                    return result;
                }
            }
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

    /** 确保 JDBC 驱动已加载（运行时可选，P3-23: 双重检查锁定） */
    private void ensureDriver() throws ClassNotFoundException {
        if (!driverLoaded) {
            synchronized (this) {
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