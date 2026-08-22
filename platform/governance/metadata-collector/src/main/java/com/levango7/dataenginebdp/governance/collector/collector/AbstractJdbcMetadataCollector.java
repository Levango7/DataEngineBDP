package com.levango7.dataenginebdp.governance.collector.collector;

import com.levango7.dataenginebdp.governance.collector.model.CollectionResult;
import com.levango7.dataenginebdp.governance.collector.model.ColumnMetadata;
import com.levango7.dataenginebdp.governance.collector.model.MetadataSource;
import com.levango7.dataenginebdp.governance.collector.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * JDBC 元数据采集器抽象基类。
 *
 * <p>Hive/Doris 均通过 JDBC 连接采集，本类封装通用的连接管理、数据库/表/列查询流程，
 * 子类只需提供 {@link #getDriverClass()}、{@link #buildJdbcUrl(MetadataSource)} 与
 * 各 SQL 模板即可。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>连接参数以 {@link Properties} 形式传递，支持 Kerberos/SSL 等扩展</li>
 *   <li>所有 JDBC 资源在 finally 块中关闭，避免泄漏</li>
 *   <li>异常被捕获并转换为 {@code CollectionResult#failure}，不向上抛出</li>
 * </ul></p>
 */
public abstract class AbstractJdbcMetadataCollector implements MetadataCollector {

    private static final Logger log = LoggerFactory.getLogger(AbstractJdbcMetadataCollector.class);

    /**
     * 子类提供 JDBC 驱动类名。
     *
     * @return 驱动全限定类名
     */
    protected abstract String getDriverClass();

    /**
     * 子类根据数据源配置构造 JDBC URL。
     *
     * @param source 数据源
     * @return JDBC URL
     */
    protected abstract String buildJdbcUrl(MetadataSource source);

    /**
     * 子类返回列出所有数据库的 SQL，结果集第一列为库名。
     *
     * @return SQL
     */
    protected String getDatabasesSql() {
        return "SHOW DATABASES";
    }

    /** 标识符白名单正则：允许字母、数字、下划线、点、减号，防止 SQL 注入。 */
    private static final java.util.regex.Pattern VALID_IDENTIFIER = java.util.regex.Pattern.compile("^[a-zA-Z0-9_.-]+$");

    /**
     * 校验标识符（库名/表名）是否合法，非法时抛出 {@link IllegalArgumentException}。
     *
     * @param name 待校验的标识符
     * @param type 标识符类型描述（用于异常消息）
     */
    private static void validateIdentifier(String name, String type) {
        if (name == null || !VALID_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid " + type + " name: " + name);
        }
    }

    /**
     * 子类返回列出指定库下所有表的 SQL，结果集第一列为表名。
     *
     * @param database 数据库名
     * @return SQL
     */
    protected String getTablesSql(String database) {
        validateIdentifier(database, "database");
        return "SHOW TABLES IN " + database;
    }

    /**
     * 子类返回查询表描述信息的 SQL，结果集应包含
     * {@code col_name/data_type/comment} 三列。
     *
     * @param database 数据库名
     * @param table    表名
     * @return SQL
     */
    protected String getDescribeSql(String database, String table) {
        validateIdentifier(database, "database");
        validateIdentifier(table, "table");
        return "DESCRIBE " + database + "." + table;
    }

    /**
     * 子类返回查询表参数（属性）的 SQL，结果集应包含
     * {@code param_key/param_value} 两列。
     *
     * @param database 数据库名
     * @param table    表名
     * @return SQL
     */
    protected String getTableParamsSql(String database, String table) {
        validateIdentifier(database, "database");
        validateIdentifier(table, "table");
        return "SHOW TBLPROPERTIES " + database + "." + table;
    }

    /**
     * 子类对单张表做额外属性填充（如表类型、统计信息、Doris 表模型/分桶等）。
     *
     * <p>默认实现空操作，子类按需覆盖。</p>
     *
     * @param metadata 表元数据
     * @param conn     JDBC 连接
     * @param database 数据库名
     * @param table    表名
     * @throws SQLException 查询失败
     */
    protected void enrichTable(TableMetadata metadata, Connection conn,
                               String database, String table) throws SQLException {
        // 默认空实现
    }

    @Override
    public CollectionResult collect(MetadataSource source) {
        CollectionResult result = CollectionResult.success(source.getId(), source.getName(), getType());
        Connection conn = null;
        try {
            conn = openConnection(source);
            List<String> databases = listDatabases(conn);
            result.setDatabaseCount(databases.size());

            List<TableMetadata> tables = new ArrayList<>();
            for (String db : databases) {
                List<String> tablesInDb;
                try {
                    tablesInDb = listTables(conn, db);
                } catch (RuntimeException e) {
                    log.warn("Failed to list tables in database {}: {}", db, e.getMessage());
                    continue;
                }
                for (String table : tablesInDb) {
                    try {
                        TableMetadata tm = collectTable(conn, db, table);
                        tm.setSourceType(getType());
                        tables.add(tm);
                    } catch (SQLException e) {
                        log.warn("Failed to collect table {}.{}: {}", db, table, e.getMessage());
                    } catch (RuntimeException e) {
                        log.warn("Failed to collect table {}.{}: {}", db, table, e.getMessage());
                    }
                }
            }
            result.setTables(tables);
            result.markFinished();
            return result;
        } catch (SQLException e) {
            log.error("JDBC collection failed for source {}: {}", source.getName(), e.getMessage(), e);
            result = CollectionResult.failure(source.getId(), source.getName(), getType(), e.getMessage());
            result.markFinished();
            return result;
        } finally {
            closeQuietly(conn);
        }
    }

    @Override
    public boolean testConnection(MetadataSource source) {
        Connection conn = null;
        try {
            conn = openConnection(source);
            // 执行简单查询验证连接可用
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(getDatabasesSql())) {
                return true; // 即使空库也视为连接成功（rs.next() 仅验证可执行性）
            }
        } catch (SQLException e) {
            log.warn("Connection test failed for source {}: {}", source.getName(), e.getMessage());
            return false;
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * 打开 JDBC 连接。
     *
     * @param source 数据源
     * @return JDBC 连接
     * @throws SQLException 连接失败
     */
    protected Connection openConnection(MetadataSource source) throws SQLException {
        try {
            Class.forName(getDriverClass());
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver not found: " + getDriverClass(), e);
        }
        Properties props = new Properties();
        if (source.getUsername() != null) {
            props.setProperty("user", source.getUsername());
        }
        if (source.getPassword() != null) {
            props.setProperty("password", source.getPassword());
        }
        return DriverManager.getConnection(buildJdbcUrl(source), props);
    }

    /**
     * 列出所有数据库。
     *
     * @param conn JDBC 连接
     * @return 库名列表
     * @throws SQLException 查询失败
     */
    protected List<String> listDatabases(Connection conn) throws SQLException {
        List<String> dbs = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(getDatabasesSql())) {
            while (rs.next()) {
                dbs.add(rs.getString(1));
            }
        }
        return dbs;
    }

    /**
     * 列出指定库下所有表。
     *
     * @param conn     JDBC 连接
     * @param database 数据库名
     * @return 表名列表
     * @throws SQLException 查询失败
     */
    protected List<String> listTables(Connection conn, String database) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(getTablesSql(database))) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warn("Failed to list tables in {}: {}", database, e.getMessage());
        }
        return tables;
    }

    /**
     * 采集单张表的完整元数据。
     *
     * @param conn     JDBC 连接
     * @param database 数据库名
     * @param table    表名
     * @return 表元数据
     * @throws SQLException 查询失败
     */
    protected TableMetadata collectTable(Connection conn, String database, String table) throws SQLException {
        TableMetadata tm = new TableMetadata();
        tm.setDatabaseName(database);
        tm.setTableName(table);
        tm.setColumns(new ArrayList<>());
        tm.setPartitionKeys(new ArrayList<>());
        tm.setProperties(new LinkedHashMap<>());

        // DESCRIBE 结果：col_name/data_type/comment
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(getDescribeSql(database, table))) {
            int ordinal = 1;
            boolean inPartitionSection = false;
            while (rs.next()) {
                String colName = rs.getString(1);
                String dataType = rs.getString(2);
                String comment = rs.getString(3);

                if (colName == null || colName.isEmpty()) {
                    continue;
                }
                // Hive DESCRIBE 输出包含 "# Partition Information" 分隔行
                if (colName.startsWith("#")) {
                    if (colName.contains("Partition")) {
                        inPartitionSection = true;
                    }
                    continue;
                }

                ColumnMetadata col = new ColumnMetadata();
                col.setName(colName);
                col.setType(dataType);
                col.setComment(comment);
                col.setOrdinalPosition(ordinal++);
                col.setPartitionColumn(inPartitionSection);
                if (inPartitionSection) {
                    tm.getPartitionKeys().add(colName);
                }
                tm.getColumns().add(col);
            }
        }

        // 表属性
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(getTableParamsSql(database, table))) {
            while (rs.next()) {
                String key = rs.getString(1);
                String value = rs.getString(2);
                if (key != null) {
                    tm.getProperties().put(key, value);
                }
            }
        } catch (SQLException e) {
            // SHOW TBLPROPERTIES 在某些引擎下不可用，忽略
            log.debug("Failed to fetch table params for {}.{}: {}", database, table, e.getMessage());
        }

        // 解析统计信息
        parseStatistics(tm);

        // 子类扩展
        enrichTable(tm, conn, database, table);

        return tm;
    }

    /**
     * 从表属性中解析统计信息（numRows/totalSize/numFiles）。
     *
     * @param tm 表元数据
     */
    protected void parseStatistics(TableMetadata tm) {
        Map<String, String> props = tm.getProperties();
        if (props == null || props.isEmpty()) {
            return;
        }
        String numRows = props.get("numRows");
        if (numRows != null) {
            try {
                tm.setRowCount(Long.parseLong(numRows));
            } catch (NumberFormatException ignored) {
                // 忽略非数字值
            }
        }
        String totalSize = props.get("totalSize");
        if (totalSize != null) {
            try {
                tm.setTotalSize(Long.parseLong(totalSize));
            } catch (NumberFormatException ignored) {
                // 忽略非数字值
            }
        }
        String numFiles = props.get("numFiles");
        if (numFiles != null) {
            try {
                tm.setFileCount(Integer.parseInt(numFiles));
            } catch (NumberFormatException ignored) {
                // 忽略非数字值
            }
        }
    }

    /**
     * 安静关闭 JDBC 连接。
     *
     * @param conn 连接，可为 null
     */
    protected void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // 忽略关闭异常
            }
        }
    }
}