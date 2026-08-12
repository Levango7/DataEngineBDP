package com.levango7.dataenginebdp.flinkcdc.source;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Oracle CDC 数据源配置，扩展自 {@link SourceConfig}，添加 Oracle 特有配置。
 *
 * <p>Oracle 通过 <b>LogMiner</b> 读取 Redo Log（含 Archived Redo Log）变更，
 * 需要指定 service name / schema 列表 / 表列表等参数。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * OracleSourceConfig config = OracleSourceConfig.builder()
 *     .name("ora-orders")
 *     .host("127.0.0.1").port(1521)
 *     .username("cdc").password("cdc-pass")
 *     .database("ORCLPDB1")            // service name
 *     .schemaList("SHOP").tableList("SHOP.ORDERS")
 *     .startupMode(SourceConfig.StartupMode.INITIAL)
 *     .build();
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public class OracleSourceConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * LogMiner 选项枚举，控制 LogMiner 查询策略。
     */
    public enum LogMinerOption {
        /** 直接从 Redo Log Buffer 读取（低延迟，但可能丢数据）。 */
        ONLINE_LOG("online-log"),
        /** 从 Archived Redo Log 读取（高可靠，但延迟较高）。 */
        ARCHIVED_LOG("archived-log"),
        /** 同时读取 Online 与 Archived（默认，平衡延迟与可靠性）。 */
        BOTH("both");

        private final String code;

        LogMinerOption(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /**
         * 根据字符串编码解析为枚举值（大小写不敏感）。
         *
         * @param code 选项编码
         * @return 对应枚举值
         * @throws IllegalArgumentException 若编码不被识别
         */
        public static LogMinerOption fromCode(String code) {
            Objects.requireNonNull(code, "logminer option 不能为 null");
            for (LogMinerOption opt : values()) {
                if (opt.code.equalsIgnoreCase(code)) {
                    return opt;
                }
            }
            throw new IllegalArgumentException("未知的 logminer option: " + code);
        }
    }

    /** 基础配置（连接信息、启动模式等通用字段）。 */
    private SourceConfig base;

    /** Oracle service name（也可使用 SID，推荐 service name）。 */
    private String serviceName;

    /** 需要捕获的 schema 列表（Oracle schema 等同于 user，全部大写）。 */
    private Set<String> schemaList = new HashSet<>();

    /** 需要捕获的表列表（格式 SCHEMA.TABLE，全部大写）。 */
    private Set<String> tableList = new HashSet<>();

    /** LogMiner 选项。 */
    private LogMinerOption logMinerOption = LogMinerOption.BOTH;

    /** 是否使用 XStream API（false 表示使用 LogMiner，推荐 LogMiner）。 */
    private boolean useXStream = false;

    /** Oracle LogMiner 查询的 SCN 起始位置（null 表示从最新开始）。 */
    private Long startScn;

    /** 默认构造器，供 YAML 反序列化。 */
    public OracleSourceConfig() {
        this.base = new SourceConfig();
        this.base.setType(SourceConfig.SourceType.ORACLE);
        this.base.setPort(1521);
    }

    /**
     * 全参构造器。
     *
     * @param base           基础配置
     * @param serviceName    service name
     * @param schemaList     schema 列表
     * @param tableList      表列表
     * @param logMinerOption LogMiner 选项
     * @param useXStream     是否使用 XStream
     * @param startScn       起始 SCN
     */
    public OracleSourceConfig(SourceConfig base, String serviceName, Set<String> schemaList,
                              Set<String> tableList, LogMinerOption logMinerOption,
                              boolean useXStream, Long startScn) {
        this.base = base;
        this.serviceName = serviceName;
        this.schemaList = schemaList == null ? new HashSet<>() : new HashSet<>(schemaList);
        this.tableList = tableList == null ? new HashSet<>() : new HashSet<>(tableList);
        this.logMinerOption = logMinerOption;
        this.useXStream = useXStream;
        this.startScn = startScn;
    }

    public SourceConfig getBase() {
        return base;
    }

    public void setBase(SourceConfig base) {
        this.base = base;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public Set<String> getSchemaList() {
        return schemaList;
    }

    public void setSchemaList(Set<String> schemaList) {
        this.schemaList = schemaList == null ? new HashSet<>() : new HashSet<>(schemaList);
    }

    public Set<String> getTableList() {
        return tableList;
    }

    public void setTableList(Set<String> tableList) {
        this.tableList = tableList == null ? new HashSet<>() : new HashSet<>(tableList);
    }

    public LogMinerOption getLogMinerOption() {
        return logMinerOption;
    }

    public void setLogMinerOption(LogMinerOption logMinerOption) {
        this.logMinerOption = logMinerOption;
    }

    public boolean isUseXStream() {
        return useXStream;
    }

    public void setUseXStream(boolean useXStream) {
        this.useXStream = useXStream;
    }

    public Long getStartScn() {
        return startScn;
    }

    public void setStartScn(Long startScn) {
        this.startScn = startScn;
    }

    /**
     * 获取 schema 列表（List 形式，供 Flink CDC API 使用）。
     *
     * @return schema 列表
     */
    public List<String> schemaListAsList() {
        return schemaList.stream().sorted().toList();
    }

    /**
     * 获取表列表（List 形式，供 Flink CDC API 使用）。
     *
     * @return 表列表
     */
    public List<String> tableListAsList() {
        return tableList.stream().sorted().toList();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OracleSourceConfig that)) {
            return false;
        }
        return useXStream == that.useXStream
                && Objects.equals(base, that.base)
                && Objects.equals(serviceName, that.serviceName)
                && Objects.equals(schemaList, that.schemaList)
                && Objects.equals(tableList, that.tableList)
                && logMinerOption == that.logMinerOption
                && Objects.equals(startScn, that.startScn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(base, serviceName, schemaList, tableList, logMinerOption, useXStream, startScn);
    }

    @Override
    public String toString() {
        return "OracleSourceConfig{base=" + base
                + ", serviceName='" + serviceName + '\''
                + ", schemaList=" + schemaList
                + ", tableList=" + tableList
                + ", logMinerOption=" + logMinerOption
                + ", useXStream=" + useXStream
                + ", startScn=" + startScn + '}';
    }

    /**
     * 链式 Builder。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 链式 Builder。
     */
    public static final class Builder {
        private final OracleSourceConfig config = new OracleSourceConfig();

        public Builder base(SourceConfig base) {
            config.base = base;
            return this;
        }

        public Builder name(String name) {
            config.base.setName(name);
            return this;
        }

        public Builder host(String host) {
            config.base.setHost(host);
            return this;
        }

        public Builder port(int port) {
            config.base.setPort(port);
            return this;
        }

        public Builder username(String username) {
            config.base.setUsername(username);
            return this;
        }

        public Builder password(String password) {
            config.base.setPassword(password);
            return this;
        }

        public Builder database(String database) {
            config.base.setDatabase(database);
            config.serviceName = database;
            return this;
        }

        public Builder serviceName(String serviceName) {
            config.serviceName = serviceName;
            config.base.setDatabase(serviceName);
            return this;
        }

        public Builder startupMode(SourceConfig.StartupMode mode) {
            config.base.setStartupMode(mode);
            return this;
        }

        public Builder schemaList(String... schemas) {
            config.schemaList = new HashSet<>(Arrays.asList(schemas));
            return this;
        }

        public Builder tableList(String... tables) {
            config.tableList = new HashSet<>(Arrays.asList(tables));
            return this;
        }

        public Builder logMinerOption(LogMinerOption option) {
            config.logMinerOption = option;
            return this;
        }

        public Builder useXStream(boolean flag) {
            config.useXStream = flag;
            return this;
        }

        public Builder startScn(Long scn) {
            config.startScn = scn;
            return this;
        }

        public OracleSourceConfig build() {
            config.base.setType(SourceConfig.SourceType.ORACLE);
            if (config.base.getPort() == 3306) {
                config.base.setPort(1521);
            }
            if (config.serviceName == null && config.base.getDatabase() != null) {
                config.serviceName = config.base.getDatabase();
            }
            return config;
        }
    }
}