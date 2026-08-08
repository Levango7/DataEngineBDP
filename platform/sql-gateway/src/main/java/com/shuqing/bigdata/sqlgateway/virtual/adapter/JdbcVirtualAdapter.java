package com.shuqing.bigdata.sqlgateway.virtual.adapter;

import com.shuqing.bigdata.sqlgateway.virtual.ColumnDefinition;
import com.shuqing.bigdata.sqlgateway.virtual.DataSourceManager;
import com.shuqing.bigdata.sqlgateway.virtual.VirtualTableDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
 * <p><b>连接管理：</b>通过 {@link DataSourceManager} 维护的 HikariCP 连接池获取连接，
 * 避免每次查询创建新连接的开销。连接池按连接 URL 缓存复用。</p>
 *
 * <p><b>SQL 注入防护：</b>查询谓词（predicate）通过 {@link PredicateParser} 解析为
 * 结构化条件，值通过 {@link PreparedStatement} 参数绑定传递，杜绝字符串拼接 SQL。
 * 表名与列名经白名单校验，仅允许 {@code [a-zA-Z_][a-zA-Z0-9_.]*} 字符。</p>
 *
 * @author shuqing-bigdata
 */
@Component
public class JdbcVirtualAdapter implements VirtualAdapter {

    private static final Logger log = LoggerFactory.getLogger(JdbcVirtualAdapter.class);

    /**
     * HikariCP 连接池管理器，由 Spring 注入。
     *
     * <p>使用 {@code @Autowired(required = false)} 允许子类在无 Spring 容器环境
     * （如单元测试）下以无参构造器实例化，此时回退到 DriverManager。</p>
     */
    protected DataSourceManager dataSourceManager;

    /**
     * 默认 JDBC 驱动类名（可被子类覆盖）。
     */
    protected String defaultDriver = "org.postgresql.Driver";

    /**
     * Spring 构造器注入构造器。
     *
     * @param dataSourceManager HikariCP 连接池管理器
     */
    @Autowired
    public JdbcVirtualAdapter(DataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    /**
     * 无参构造器（供子类在无 Spring 环境下使用，连接将回退到 DriverManager）。
     */
    protected JdbcVirtualAdapter() {
    }

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
            // 1. 校验表名（白名单），防止表名注入
            String sourceObject = definition.getSourceObject();
            validateSourceObject(sourceObject);

            // 2. 解析谓词为参数化 SQL 片段（防 SQL 注入）
            PredicateParser.ParsedPredicate parsed = PredicateParser.parse(predicate);

            // 3. 构建参数化 SQL：表名经白名单校验后拼接，谓词与 limit 用占位符
            StringBuilder sql = new StringBuilder("SELECT * FROM ").append(sourceObject);
            List<Object> params = new ArrayList<>(parsed.parameters());
            if (parsed.hasPredicate()) {
                sql.append(" WHERE ").append(parsed.sqlFragment());
            }
            boolean hasLimit = limit != null && limit > 0;
            if (hasLimit) {
                sql.append(" LIMIT ?");
                params.add(limit);
            }
            log.debug("生成参数化 SQL: {} 参数: {}", sql, params);

            // 4. PreparedStatement 绑定参数并执行
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                bindParameters(stmt, params);
                try (ResultSet rs = stmt.executeQuery()) {
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
        // 连接池由 DataSourceManager 统一管理生命周期，适配器无需单独关闭
        log.debug("JDBC 适配器关闭（连接池由 DataSourceManager 管理）");
    }

    /**
     * 打开 JDBC 连接（优先使用 HikariCP 连接池）。
     *
     * <p>当 {@link #dataSourceManager} 可用时，从连接池借出连接；
     * 否则回退到 {@link java.sql.DriverManager}（仅用于无 Spring 环境的测试场景）。</p>
     *
     * @param definition 虚拟表定义
     * @return JDBC 连接
     * @throws SQLException 若连接失败
     */
    protected Connection openConnection(VirtualTableDefinition definition) throws SQLException {
        if (dataSourceManager != null) {
            return dataSourceManager.getConnection(definition.getConnectionConfig());
        }
        // 回退路径：无连接池（仅测试场景）
        log.debug("DataSourceManager 未注入，回退到 DriverManager table={}",
                definition.getTableName());
        return openConnectionDirect(definition);
    }

    /**
     * 直接通过 DriverManager 创建连接（回退路径，仅用于无 Spring 环境的测试）。
     */
    private Connection openConnectionDirect(VirtualTableDefinition definition) throws SQLException {
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
        return java.sql.DriverManager.getConnection(url, username, password);
    }

    /**
     * 校验 sourceObject（表名）合法性，防止表名注入。
     *
     * <p>允许格式：{@code table} 或 {@code schema.table}，
     * 每段仅允许 {@code [a-zA-Z_][a-zA-Z0-9_]*}。</p>
     *
     * @param sourceObject 外部源对象名
     * @throws VirtualAdapterException 若包含非法字符
     */
    private void validateSourceObject(String sourceObject) {
        if (sourceObject == null || sourceObject.isEmpty()) {
            throw new VirtualAdapterException("SOURCE_OBJECT_INVALID",
                    "sourceObject 为空", null);
        }
        String[] segments = sourceObject.split("\\.");
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new VirtualAdapterException("SOURCE_OBJECT_INVALID",
                        "sourceObject 包含空段: " + sourceObject, null);
            }
            PredicateParser.validateIdentifier(segment);
        }
    }

    /**
     * 绑定参数到 PreparedStatement。
     *
     * @param stmt   PreparedStatement
     * @param params 参数列表（按占位符顺序）
     * @throws SQLException 若绑定失败
     */
    private void bindParameters(PreparedStatement stmt, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            stmt.setObject(i + 1, params.get(i));
        }
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
