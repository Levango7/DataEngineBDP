package com.shuqing.bigdata.governance.collector.collector;

import com.shuqing.bigdata.governance.collector.model.MetadataSource;
import com.shuqing.bigdata.governance.collector.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Doris 元数据采集器。
 *
 * <p>通过 JDBC（MySQL 协议）连接 Doris FE，采集：
 * <ol>
 *   <li>数据库列表（{@code SHOW DATABASES}）</li>
 *   <li>表列表（{@code SHOW TABLES IN &lt;db&gt;}）</li>
 *   <li>列信息：名称/类型/注释（{@code DESCRIBE &lt;db&gt;.&lt;table&gt;}）</li>
 *   <li>表模型：OLAP/MySQL/NATIVE（{@code SHOW CREATE TABLE} 解析）</li>
 *   <li>分桶信息：分桶数与分桶列（{@code SHOW CREATE TABLE} 解析）</li>
 * </ol></p>
 *
 * <p>JDBC URL 格式：{@code jdbc:mysql://host:port/db}，
 * 使用 MySQL JDBC 驱动（{@code com.mysql.cj.jdbc.Driver}）。</p>
 */
@Component
public class DorisMetadataCollector extends AbstractJdbcMetadataCollector {

    private static final Logger log = LoggerFactory.getLogger(DorisMetadataCollector.class);

    /** MySQL JDBC 驱动类名（Doris 兼容 MySQL 协议） */
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";

    /** 默认 Doris FE MySQL 协议端口 */
    private static final int DEFAULT_PORT = 9030;

    /** Doris 默认数据库 */
    private final String defaultDatabase;

    /**
     * 构造 Doris 采集器。
     *
     * @param defaultDatabase 默认数据库，从配置 {@code app.collector.doris.default-database} 读取
     */
    public DorisMetadataCollector(
            @Value("${app.collector.doris.default-database:information_schema}") String defaultDatabase) {
        this.defaultDatabase = defaultDatabase;
    }

    @Override
    public String getType() {
        return MetadataSource.TYPE_DORIS;
    }

    @Override
    protected String getDriverClass() {
        return MYSQL_DRIVER;
    }

    @Override
    protected String buildJdbcUrl(MetadataSource source) {
        if (source.getUrl() != null && source.getUrl().startsWith("jdbc:mysql://")) {
            return source.getUrl();
        }
        String hostPort = source.getUrl() == null ? "localhost:" + DEFAULT_PORT : source.getUrl();
        return "jdbc:mysql://" + hostPort + "/" + defaultDatabase
                + "?useSSL=false&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
    }

    @Override
    protected void enrichTable(TableMetadata metadata, Connection conn,
                               String database, String table) throws SQLException {
        // 通过 SHOW CREATE TABLE 解析表模型与分桶信息
        String createSql = showCreateTable(conn, database, table);
        if (createSql == null) {
            return;
        }

        // 解析表模型：ENGINE = OLAP / MySQL
        String upper = createSql.toUpperCase();
        if (upper.contains("ENGINE = OLAP") || upper.contains("ENGINE=OLAP")) {
            metadata.setDorisTableModel("OLAP");
        } else if (upper.contains("ENGINE = MYSQL") || upper.contains("ENGINE=MYSQL")) {
            metadata.setDorisTableModel("MySQL");
        } else if (upper.contains("ENGINE = NATIVE") || upper.contains("ENGINE=NATIVE")) {
            metadata.setDorisTableModel("NATIVE");
        }

        // 解析分桶数：DISTRIBUTED BY HASH(...) BUCKETS N
        int bucketIdx = upper.indexOf("BUCKETS");
        if (bucketIdx > 0) {
            String tail = createSql.substring(bucketIdx + "BUCKETS".length()).trim();
            // 取后续连续数字
            int i = 0;
            while (i < tail.length() && Character.isWhitespace(tail.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < tail.length() && Character.isDigit(tail.charAt(i))) {
                i++;
            }
            if (i > start) {
                try {
                    metadata.setBucketCount(Integer.parseInt(tail.substring(start, i)));
                } catch (NumberFormatException ignored) {
                    // 忽略解析失败
                }
            }
        }

        // 解析分桶列：DISTRIBUTED BY HASH(col1, col2)
        int hashIdx = upper.indexOf("DISTRIBUTED BY HASH");
        if (hashIdx > 0) {
            int parenStart = createSql.indexOf('(', hashIdx);
            int parenEnd = createSql.indexOf(')', hashIdx);
            if (parenStart > 0 && parenEnd > parenStart) {
                String cols = createSql.substring(parenStart + 1, parenEnd);
                List<String> bucketCols = new ArrayList<>();
                for (String c : cols.split(",")) {
                    String trimmed = c.trim();
                    if (!trimmed.isEmpty()) {
                        bucketCols.add(trimmed);
                    }
                }
                metadata.setBucketColumns(bucketCols);
            }
        }
    }

    /**
     * 执行 {@code SHOW CREATE TABLE} 并返回建表 SQL。
     *
     * @param conn     JDBC 连接
     * @param database 数据库名
     * @param table    表名
     * @return 建表 SQL；查询失败返回 null
     */
    private String showCreateTable(Connection conn, String database, String table) {
        String sql = "SHOW CREATE TABLE " + database + "." + table;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                // 结果集第二列为完整建表 SQL
                return rs.getString(2);
            }
        } catch (SQLException e) {
            log.debug("SHOW CREATE TABLE failed for {}.{}: {}", database, table, e.getMessage());
        }
        return null;
    }
}