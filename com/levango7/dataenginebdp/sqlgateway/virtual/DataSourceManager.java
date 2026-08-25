package com.shuqing.bigdata.sqlgateway.virtual;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 虚拟表数据源连接池管理器。
 *
 * <p>为 JDBC 类数据源（MySQL/Oracle/JDBC）维护 HikariCP 连接池，
 * 避免每次查询都创建新连接。连接池按连接 URL 缓存，相同 URL 复用同一池。</p>
 *
 * <p>非 JDBC 数据源（Kafka/REST）不使用本管理器，由各自适配器内部管理客户端生命周期。</p>
 *
 * <p>连接池默认配置：</p>
 * <ul>
 *   <li>最大池大小：10；</li>
 *   <li>最小空闲连接：2；</li>
 *   <li>连接超时：30 秒；</li>
 *   <li>空闲超时：10 分钟；</li>
 *   <li>最大生命周期：30 分钟。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class DataSourceManager {

    private static final Logger log = LoggerFactory.getLogger(DataSourceManager.class);

    private final Map<String, HikariDataSource> poolCache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取或创建指定连接配置的 JDBC 连接池。
     *
     * @param connectionConfigJson 连接配置 JSON
     * @return HikariCP 数据源
     * @throws SQLException 若创建连接池失败
     */
    public DataSource getOrCreatePool(String connectionConfigJson) throws SQLException {
        Map<String, Object> config = parseConfig(connectionConfigJson);
        String url = (String) config.get("url");
        String username = (String) config.get("username");
        // 安全约定：password 从连接配置 JSON 读取（非硬编码），仅用于建立连接池，
        // 严禁记录到任何日志或异常消息中。
        String password = (String) config.get("password");
        String driver = (String) config.get("driver");
        // poolKey 仅由 url + username 组成，严禁包含 password，避免凭据泄露到日志。
        String poolKey = url + "|" + username;

        return poolCache.computeIfAbsent(poolKey, k -> {
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(url);
            hikariConfig.setUsername(username);
            hikariConfig.setPassword(password);
            if (driver != null && !driver.isBlank()) {
                hikariConfig.setDriverClassName(driver);
            }
            hikariConfig.setMaximumPoolSize(10);
            hikariConfig.setMinimumIdle(2);
            hikariConfig.setConnectionTimeout(30_000);
            hikariConfig.setIdleTimeout(600_000);
            hikariConfig.setMaxLifetime(1_800_000);
            hikariConfig.setPoolName("vt-pool-" + (poolKey.hashCode() & 0xFFFF));
            // 日志仅记录 poolKey(url|username) 与 url，不记录 password。
            log.info("创建 HikariCP 连接池 key={} url={}", poolKey, url);
            return new HikariDataSource(hikariConfig);
        });
    }

    /**
     * 从连接池借出一条连接。
     *
     * @param connectionConfigJson 连接配置 JSON
     * @return JDBC 连接
     * @throws SQLException 若获取连接失败
     */
    public Connection getConnection(String connectionConfigJson) throws SQLException {
        return getOrCreatePool(connectionConfigJson).getConnection();
    }

    /**
     * 关闭指定连接配置对应的连接池。
     *
     * @param connectionConfigJson 连接配置 JSON
     */
    public void closePool(String connectionConfigJson) {
        try {
            Map<String, Object> config = parseConfig(connectionConfigJson);
            String url = (String) config.get("url");
            String username = (String) config.get("username");
            String poolKey = url + "|" + username;
            HikariDataSource ds = poolCache.remove(poolKey);
            if (ds != null) {
                ds.close();
                log.info("关闭 HikariCP 连接池 key={}", poolKey);
            }
        } catch (Exception e) {
            log.warn("关闭连接池失败 err={}", e.getMessage());
        }
    }

    /**
     * 关闭全部连接池（应用关闭时调用）。
     */
    public void closeAll() {
        log.info("关闭全部连接池 count={}", poolCache.size());
        poolCache.forEach((key, ds) -> {
            try {
                ds.close();
            } catch (Exception e) {
                log.warn("关闭连接池失败 key={} err={}", key, e.getMessage());
            }
        });
        poolCache.clear();
    }

    /**
     * 获取连接池统计信息。
     *
     * @return 统计信息 Map
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        poolCache.forEach((key, ds) -> {
            Map<String, Object> poolStats = Map.of(
                    "active", ds.getHikariPoolMXBean().getActiveConnections(),
                    "idle", ds.getHikariPoolMXBean().getIdleConnections(),
                    "total", ds.getHikariPoolMXBean().getTotalConnections(),
                    "threadsAwaiting", ds.getHikariPoolMXBean().getThreadsAwaitingConnection()
            );
            stats.put(key, poolStats);
        });
        return stats;
    }

    private Map<String, Object> parseConfig(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("连接配置 JSON 解析失败: " + e.getMessage(), e);
        }
    }
}