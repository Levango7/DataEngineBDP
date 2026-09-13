package com.levango7.dataenginebdp.encaps.service.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * IoTDB 客户端。
 *
 * <p>通过 IoTDB JDBC 接口（默认 jdbc:iotdb://localhost:6667/）执行 SQL：
 * <ul>
 *   <li>SHOW STORAGE GROUP — 存储组列表</li>
 *   <li>SHOW DEVICES      — 设备列表</li>
 *   <li>SHOW TIMESERIES   — 时序列表</li>
 *   <li>SELECT ...        — 数据查询</li>
 * </ul>
 * JDBC 驱动通过 {@code Class.forName} 动态加载，运行时 classpath 需有 iotdb-jdbc。
 * 连接失败时抛 {@link EngineUnavailableException}，由 Controller 转 503。</p>
 *
 * <p>方法接受可选的连接参数（jdbcUrl/username/password），null 时使用全局默认配置，
 * 支持多实例场景（每个实例对应数据源表一条 iotdb 记录）。</p>
 */
@Slf4j
@Service
public class IoTDBClient {

    /** IoTDB JDBC 地址，默认 jdbc:iotdb://localhost:6667/ */
    @Value("${app.engine.iotdb.jdbc-url:jdbc:iotdb://localhost:6667/}")
    private String defaultJdbcUrl;

    /** IoTDB 用户名 */
    @Value("${app.engine.iotdb.username:root}")
    private String defaultUsername;

    /** IoTDB 密码 */
    @Value("${app.engine.iotdb.password:root}")
    private String defaultPassword;

    /** JDBC 驱动类名 */
    @Value("${app.engine.iotdb.driver-class:org.apache.iotdb.jdbc.IoTDBDriver}")
    private String driverClass;

    /** JDBC 驱动是否已加载标志 */
    private volatile boolean driverLoaded = false;

    /** P2-4: IoTDB device/时序名白名单：仅允许字母数字下划线点（防 SQL 注入）。 */
    private static final Pattern DEVICE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]+$");

    /** P3: JDBC 连接超时（秒）。 */
    private static final int JDBC_CONNECT_TIMEOUT_SECONDS = 10;

    /** P3: JDBC 查询超时（秒）。 */
    private static final int JDBC_QUERY_TIMEOUT_SECONDS = 30;

    /** 连接参数 */
    public record ConnParams(String jdbcUrl, String username, String password) {
    }

    /**
     * 列出存储组。
     *
     * @param conn 连接参数（null 用默认）
     * @return 存储组名列表
     */
    public List<String> listStorageGroups(ConnParams conn) {
        return queryStrings(conn, "SHOW STORAGE GROUP", 1);
    }

    /**
     * 列出设备。
     *
     * @param conn 连接参数（null 用默认）
     * @return 设备名列表
     */
    public List<String> listDevices(ConnParams conn) {
        return queryStrings(conn, "SHOW DEVICES", 1);
    }

    /**
     * 列出时序（测点）。
     *
     * <p>P2-4 修复：对 device 参数添加白名单校验（仅允许字母数字下划线点），
     * 防止 SQL 注入。Controller 层已有校验，此处为 defense-in-depth。</p>
     *
     * @param conn   连接参数（null 用默认）
     * @param device 设备名（可选，null 则查全部）
     * @return 时序列表
     * @throws IllegalArgumentException 若 device 含非法字符
     */
    public List<Map<String, Object>> listTimeseries(ConnParams conn, String device) {
        String sql;
        if (device == null || device.isBlank()) {
            sql = "SHOW TIMESERIES";
        } else {
            // P2-4: 白名单校验防 SQL 注入（defense-in-depth）
            if (!DEVICE_NAME_PATTERN.matcher(device).matches()) {
                throw new IllegalArgumentException("非法的 device 参数: " + device);
            }
            sql = "SHOW TIMESERIES " + device;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = openConnection(conn);
             Statement stmt = c.createStatement()) {
            // P3: 设置查询超时
            stmt.setQueryTimeout(JDBC_QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    Map<String, Object> ts = new LinkedHashMap<>();
                    ts.put("name", rs.getString("Timeseries"));
                    ts.put("device", rs.getString("Device"));
                    ts.put("dataType", rs.getString("dataType"));
                    ts.put("encoding", rs.getString("encoding"));
                    ts.put("compression", rs.getString("compression"));
                    result.add(ts);
                }
                return result;
            }
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineUnavailableException("IoTDB 查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行 SQL 查询并返回结构化结果。
     *
     * @param conn 连接参数（null 用默认）
     * @param sql  SQL 文本
     * @return 含 columns/rows/rowCount/durationMs 的结果
     */
    public Map<String, Object> executeQuery(ConnParams conn, String sql) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection c = openConnection(conn);
             Statement stmt = c.createStatement()) {
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
                return result;
            }
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // P2-1: 异常消息泛化，不暴露 DB 结构（参照 DorisClient.executeQuery）
            // P3-1: 异常可能发生在结果集遍历过程中，result Map 可能已包含部分数据
            // （如 columns 但无 rows），直接 put 会导致返回结构混乱。
            // 先 clear 再 put 失败信息，保证返回结构干净一致。
            result.clear();
            result.put("status", "FAILED");
            result.put("error", "查询执行失败");
            result.put("durationMs", System.currentTimeMillis() - start);
            log.warn("IoTDB 查询失败（详情已隐藏）: {}", e.getMessage());
            return result;
        }
    }

    /**
     * 查询写入吞吐（简化：返回空列表）。
     *
     * @param conn 连接参数（null 用默认）
     * @return 吞吐采样点列表
     */
    public List<Map<String, Object>> getWriteThroughput(ConnParams conn) {
        return new ArrayList<>();
    }

    /**
     * 执行返回单列字符串的 SQL。
     *
     * @param conn     连接参数（null 用默认）
     * @param sql      SQL 文本
     * @param colIndex 列索引（从 1 开始）
     * @return 字符串列表
     */
    private List<String> queryStrings(ConnParams conn, String sql, int colIndex) {
        List<String> result = new ArrayList<>();
        try (Connection c = openConnection(conn);
             Statement stmt = c.createStatement()) {
            // P3: 设置查询超时
            stmt.setQueryTimeout(JDBC_QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    result.add(rs.getString(colIndex));
                }
                return result;
            }
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineUnavailableException("IoTDB 查询失败: " + e.getMessage(), e);
        }
    }

    /** 打开 JDBC 连接 */
    private Connection openConnection(ConnParams conn) throws Exception {
        ensureDriver();
        // P2-2: 实际应用 JDBC 连接超时（旧实现仅定义常量未设置，注释声称"通过 Properties 传递"但实际未设置）
        DriverManager.setLoginTimeout(JDBC_CONNECT_TIMEOUT_SECONDS);
        String url = conn != null ? conn.jdbcUrl : defaultJdbcUrl;
        String user = conn != null ? conn.username : defaultUsername;
        String pass = conn != null ? conn.password : defaultPassword;
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", user);
        props.setProperty("password", pass);
        return DriverManager.getConnection(url, props);
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
                                "IoTDB JDBC 驱动缺失: " + driverClass + "，请将 iotdb-jdbc 加入 classpath", e);
                    }
                }
            }
        }
    }

    /**
     * 构造连接参数。
     *
     * @param jdbcUrl  JDBC 地址
     * @param username 用户名
     * @param password 密码
     * @return 连接参数对象
     */
    public ConnParams connParams(String jdbcUrl, String username, String password) {
        return new ConnParams(jdbcUrl, username, password);
    }
}
