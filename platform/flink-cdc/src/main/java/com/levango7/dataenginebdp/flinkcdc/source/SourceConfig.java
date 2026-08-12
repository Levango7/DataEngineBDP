package com.levango7.dataenginebdp.flinkcdc.source;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * CDC 数据源配置，描述如何连接源数据库并读取变更。
 *
 * <p>支持 MySQL / PostgreSQL / Oracle 三种数据源类型，含连接信息、
 * server-id（MySQL Binlog 复制位点）、启动模式以及库表过滤。</p>
 *
 * <p>可通过 {@link Builder} 链式构造，或由 {@code CdcYamlConfig} 从 YAML 反序列化。</p>
 *
 * @author shuqing-bigdata
 */
public class SourceConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 数据源类型枚举。
     */
    public enum SourceType {
        /** MySQL，通过 Binlog 读取变更。 */
        MYSQL,
        /** PostgreSQL，通过 logical replication slot 读取变更。 */
        POSTGRESQL,
        /** Oracle，通过 LogMiner 读取变更。 */
        ORACLE
    }

    /**
     * 启动模式枚举，决定全量初始化与增量 Binlog 的衔接策略。
     */
    public enum StartupMode {
        /** 先做全量快照，再衔接到 Binlog 最新位点继续增量读取（默认）。 */
        INITIAL("initial"),
        /** 跳过全量，直接从 Binlog 最新位点开始（仅增量）。 */
        LATEST_OFFSET("latest-offset"),
        /** 从指定时间戳对应的 Binlog 位点开始（仅增量）。 */
        TIMESTAMP("timestamp"),
        /** 从指定 Binlog 文件名+位点开始（仅增量）。 */
        SPECIFIC_OFFSET("specific-offset");

        private final String code;

        StartupMode(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /**
         * 根据字符串编码解析为枚举值（大小写不敏感，支持 kebab-case）。
         *
         * @param code 启动模式编码
         * @return 对应枚举值
         * @throws IllegalArgumentException 若编码不被识别
         */
        public static StartupMode fromCode(String code) {
            Objects.requireNonNull(code, "startup mode 不能为 null");
            String normalized = code.toLowerCase().replace("_", "-");
            for (StartupMode mode : values()) {
                if (mode.code.equals(normalized)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("未知的 startup mode: " + code);
        }
    }

    /** 数据源名称（多源场景下唯一标识）。 */
    private String name;

    /** 数据源类型。 */
    private SourceType type = SourceType.MYSQL;

    /** 主机名。 */
    private String host = "localhost";

    /** 端口。 */
    private int port = 3306;

    /** 用户名（需具备 REPLICATION SLAVE / REPLICATION CLIENT 权限）。 */
    private String username;

    /** 密码。 */
    private String password;

    /** 数据库名（可使用正则表达式）。 */
    private String database;

    /** 表名（可使用正则表达式，格式：db.table）。 */
    private String table;

    /** MySQL server-id（或区间起始，多个并行读取时需唯一）。 */
    private long serverId = 5400L;

    /** 启动模式。 */
    private StartupMode startupMode = StartupMode.INITIAL;

    /** TIMESTAMP 模式下的起始时间戳（毫秒）。 */
    private Long startupTimestampMillis;

    /** SPECIFIC_OFFSET 模式下的 Binlog 文件名。 */
    private String binlogFilename;

    /** SPECIFIC_OFFSET 模式下的 Binlog 位点。 */
    private Long binlogPosition;

    /** 是否包含 schema 变更（DDL）。 */
    private boolean includeSchemaChanges = false;

    /** 并行度（增量阶段分片数）。 */
    private int parallelism = 1;

    /** 分片列（全量快照分片使用，逗号分隔）。 */
    private String splitColumn;

    /** 需要捕获的表名集合（覆盖 {@link #table}，更精确的过滤）。 */
    private Set<String> tableFilters = Collections.emptySet();

    /** 默认构造器，供 YAML 反序列化。 */
    public SourceConfig() {
    }

    /**
     * 获取连接 URL（JDBC 格式）。
     *
     * @return JDBC URL
     */
    public String jdbcUrl() {
        return switch (type) {
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + (database == null ? "" : database);
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + (database == null ? "" : database);
            case ORACLE -> "jdbc:oracle:thin:@" + host + ":" + port + ":" + (database == null ? "" : database);
        };
    }

    /**
     * 解析 {@link #table} 为 (database, table) 元组。
     *
     * @return 长度 2 的数组 [db, table]；若 table 为 null 返回 [null, null]
     */
    public String[] parseTable() {
        if (table == null || table.isBlank()) {
            return new String[]{null, null};
        }
        int dot = table.indexOf('.');
        if (dot < 0) {
            return new String[]{database, table};
        }
        return new String[]{table.substring(0, dot), table.substring(dot + 1)};
    }

    /**
     * 获取需要捕获的表列表（合并 {@link #table} 与 {@link #tableFilters}）。
     *
     * @return 表名列表（格式 db.table）
     */
    public List<String> resolvedTables() {
        Set<String> result = new HashSet<>(tableFilters);
        if (table != null && !table.isBlank()) {
            result.add(table);
        }
        return result.stream().sorted().toList();
    }

    // ===== getter / setter =====

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SourceType getType() {
        return type;
    }

    public void setType(SourceType type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public long getServerId() {
        return serverId;
    }

    public void setServerId(long serverId) {
        this.serverId = serverId;
    }

    public StartupMode getStartupMode() {
        return startupMode;
    }

    public void setStartupMode(StartupMode startupMode) {
        this.startupMode = startupMode;
    }

    public Long getStartupTimestampMillis() {
        return startupTimestampMillis;
    }

    public void setStartupTimestampMillis(Long startupTimestampMillis) {
        this.startupTimestampMillis = startupTimestampMillis;
    }

    public String getBinlogFilename() {
        return binlogFilename;
    }

    public void setBinlogFilename(String binlogFilename) {
        this.binlogFilename = binlogFilename;
    }

    public Long getBinlogPosition() {
        return binlogPosition;
    }

    public void setBinlogPosition(Long binlogPosition) {
        this.binlogPosition = binlogPosition;
    }

    public boolean isIncludeSchemaChanges() {
        return includeSchemaChanges;
    }

    public void setIncludeSchemaChanges(boolean includeSchemaChanges) {
        this.includeSchemaChanges = includeSchemaChanges;
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    public String getSplitColumn() {
        return splitColumn;
    }

    public void setSplitColumn(String splitColumn) {
        this.splitColumn = splitColumn;
    }

    public Set<String> getTableFilters() {
        return tableFilters;
    }

    public void setTableFilters(Set<String> tableFilters) {
        this.tableFilters = tableFilters == null ? Collections.emptySet() : tableFilters;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SourceConfig that)) {
            return false;
        }
        return port == that.port
                && serverId == that.serverId
                && includeSchemaChanges == that.includeSchemaChanges
                && parallelism == that.parallelism
                && Objects.equals(name, that.name)
                && type == that.type
                && Objects.equals(host, that.host)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password)
                && Objects.equals(database, that.database)
                && Objects.equals(table, that.table)
                && startupMode == that.startupMode
                && Objects.equals(startupTimestampMillis, that.startupTimestampMillis)
                && Objects.equals(binlogFilename, that.binlogFilename)
                && Objects.equals(binlogPosition, that.binlogPosition)
                && Objects.equals(splitColumn, that.splitColumn)
                && Objects.equals(tableFilters, that.tableFilters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, host, port, username, password, database, table,
                serverId, startupMode, startupTimestampMillis, binlogFilename, binlogPosition,
                includeSchemaChanges, parallelism, splitColumn, tableFilters);
    }

    @Override
    public String toString() {
        return "SourceConfig{name='" + name + "', type=" + type
                + ", host='" + host + "', port=" + port
                + ", database='" + database + "', table='" + table + "'"
                + ", serverId=" + serverId + ", startupMode=" + startupMode
                + ", parallelism=" + parallelism + '}';
    }

    /**
     * 链式 Builder。
     */
    public static final class Builder {
        private final SourceConfig config = new SourceConfig();

        public Builder name(String name) {
            config.name = name;
            return this;
        }

        public Builder type(SourceType type) {
            config.type = type;
            return this;
        }

        public Builder host(String host) {
            config.host = host;
            return this;
        }

        public Builder port(int port) {
            config.port = port;
            return this;
        }

        public Builder username(String username) {
            config.username = username;
            return this;
        }

        public Builder password(String password) {
            config.password = password;
            return this;
        }

        public Builder database(String database) {
            config.database = database;
            return this;
        }

        public Builder table(String table) {
            config.table = table;
            return this;
        }

        public Builder serverId(long serverId) {
            config.serverId = serverId;
            return this;
        }

        public Builder startupMode(StartupMode mode) {
            config.startupMode = mode;
            return this;
        }

        public Builder startupTimestampMillis(Long ts) {
            config.startupTimestampMillis = ts;
            return this;
        }

        public Builder parallelism(int parallelism) {
            config.parallelism = parallelism;
            return this;
        }

        public Builder tableFilters(String... tables) {
            config.tableFilters = new HashSet<>(Arrays.asList(tables));
            return this;
        }

        public Builder includeSchemaChanges(boolean flag) {
            config.includeSchemaChanges = flag;
            return this;
        }

        public SourceConfig build() {
            return config;
        }
    }
}