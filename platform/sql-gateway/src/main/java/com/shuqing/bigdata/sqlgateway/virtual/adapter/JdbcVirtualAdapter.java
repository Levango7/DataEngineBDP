package com.shuqing.bigdata.sqlgateway.virtual.adapter;

import com.shuqing.bigdata.sqlgateway.virtual.ColumnDefinition;
import com.shuqing.bigdata.sqlgateway.virtual.VirtualTableDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用 JDBC 虚拟表适配器。
 *
 * <p>支持任何提供 JDBC 驱动的数据源（PostgreSQL、SQL Server、DM、Kingbase 等）。
 * MySQL 与 Oracle 适配器继承本类，仅覆盖驱动与 schema 查询差异。</p>
 *
 * <p>连接管理采用"按需创建、用完即关"的轻量模式；
 * 生产环境推荐配合 HikariCP 连接池（由 {@code DataSourceManager} 提供）。</p>
 *
 * @author shuqing-bigdata
 */
public class JdbcVirtualAdapter implements VirtualAdapter {

    private static final Logger log = LoggerFactory.getLogger(JdbcVirtualAdapter.class);

    /**
     * 默认 JDBC 驱动类名（可被子类覆盖）。
     */
    protected String defaultDriver = "org.postgresql.Driver";

    @Override
    public List<ColumnDefinition> getSchema(VirtualTableDefinition definition) throws VirtualAdapterException {
        log.debug("获取 schema table={} source={}", definition.getTableName(), definition.getSourceObject());
        try (Connection conn = openConnection(definition)) {
            DatabaseMetaData metaData = conn.getMetaData();
            // sourceObject 格式：schema.table 或 table
            String[] parts = definition.getSourceObject().split("\\.", 2);
            String schema = parts.length > 1 ? parts[0] : null;
            String table = parts.length > 1 ? parts[1] : parts[0];

            List<ColumnDefinition> columns = new ArrayList<>();
            try (ResultSet rs = metaData.getColumns(null, schema, table, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    String type = mapSqlType(rs.getInt("DATA_TYPE"));
                    boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                    String remark = rs.getString("REMARKS");
                    columns.add(new ColumnDefinition(name, type, nullable, remark));
                }
            }
            if (columns.isEmpty()) {
                // 回退：使用虚拟表定义中的列定义
                log.warn("外部源未返回列信息，使用虚拟表预定义列 table={}", definition.getTableName());
                return definition.getColumns();
            }
            return columns;
        } catch (SQLException e) {
            throw new VirtualAdapterException("JDBC_SCHEMA_FAILED",
                    "获取 schema 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public QueryResult query(VirtualTableDefinition definition, String predicate, Integer limit)
            throws VirtualAdapterException {
        log.debug("执行查询 table={} predicate={} limit={}", definition.getTableName(), predicate, limit);
        try (Connection conn = openConnection(definition)) {
            StringBuilder sql = new StringBuilder("SELECT * FROM ");
            sql.append(definition.getSourceObject());
            if (predicate != null && !predicate.isBlank()) {
                sql.append(" WHERE ").append(predicate);
            }
            if (limit != null && limit > 0) {
                sql.append(" LIMIT ").append(limit);
            }
            log.debug("生成 SQL: {}", sql);

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString());
                 ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData rsMeta = rs.getMetaData();
                int colCount = rsMeta.getColumnCount();
                List<String> columns = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    columns.add(rsMeta.getColumnLabel(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return new QueryResult(columns, rows);
            }
        } catch (SQLException e) {
            throw new VirtualAdapterException("JDBC_QUERY_FAILED",
                    "查询失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean testConnection(VirtualTableDefinition definition) {
        try (Connection conn = openConnection(definition)) {
            return conn.isValid(5);
        } catch (SQLException e) {
            log.warn("连接测试失败 table={} err={}", definition.getTableName(), e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        // 轻量模式：每次查询创建独立连接，无需全局关闭
        log.debug("JDBC 适配器关闭（轻量模式，无全局资源）");
    }

    /**
     * 打开 JDBC 连接。
     *
     * @param definition 虚拟表定义
     * @return JDBC 连接
     * @throws SQLException 若连接失败
     */
    protected Connection openConnection(VirtualTableDefinition definition) throws SQLException {
        Map<String, Object> config = parseConfig(definition);
        String url = (String) config.get("url");
        String username = (String) config.get("username");
        String password = (String) config.get("password");
        String driver = (String) config.getOrDefault("driver", defaultDriver);
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC 驱动未找到: " + driver, e);
        }
        return DriverManager.getConnection(url, username, password);
    }

    /**
     * 解析连接配置 JSON。
     *
     * @param definition 虚拟表定义
     * @return 配置 Map
     */
    protected Map<String, Object> parseConfig(VirtualTableDefinition definition) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(definition.getConnectionConfig(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            throw new VirtualAdapterException("CONFIG_PARSE_FAILED",
                    "连接配置解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 JDBC 类型整数映射为 SQL 标准类型名。
     *
     * @param jdbcType JDBC 类型整数（{@link java.sql.Types}）
     * @return SQL 类型名
     */
    protected String mapSqlType(int jdbcType) {
        return switch (jdbcType) {
            case java.sql.Types.VARCHAR, java.sql.Types.CHAR, java.sql.Types.LONGVARCHAR -> "VARCHAR";
            case java.sql.Types.INTEGER, java.sql.Types.SMALLINT, java.sql.Types.TINYINT -> "INTEGER";
            case java.sql.Types.BIGINT -> "BIGINT";
            case java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> "DECIMAL";
            case java.sql.Types.FLOAT, java.sql.Types.REAL, java.sql.Types.DOUBLE -> "DOUBLE";
            case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> "BOOLEAN";
            case java.sql.Types.DATE -> "DATE";
            case java.sql.Types.TIME, java.sql.Types.TIMESTAMP -> "TIMESTAMP";
            case java.sql.Types.BLOB, java.sql.Types.BINARY -> "BLOB";
            case java.sql.Types.CLOB -> "CLOB";
            default -> "VARCHAR";
        };
    }
}