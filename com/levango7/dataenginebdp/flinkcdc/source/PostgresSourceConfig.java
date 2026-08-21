package com.shuqing.bigdata.flinkcdc.source;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * PostgreSQL CDC 数据源配置，扩展自 {@link SourceConfig}，添加 PostgreSQL 特有配置。
 *
 * <p>PostgreSQL 通过 <b>Logical Replication Slot</b> 读取 WAL（Write-Ahead Log）变更，
 * 需要指定 slot 名称与逻辑解码插件（pgoutput / decoderbufs / wal2json 等）。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * PostgresSourceConfig config = PostgresSourceConfig.builder()
 *     .name("pg-orders")
 *     .host("127.0.0.1").port(5432)
 *     .username("cdc").password("cdc-pass")
 *     .database("shop").schemaList("public").tableList("public.orders")
 *     .slotName("flink_slot")
 *     .decodingPluginName(DecodingPlugin.PGOUTPUT)
 *     .startupMode(SourceConfig.StartupMode.INITIAL)
 *     .build();
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public class PostgresSourceConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * PostgreSQL 逻辑解码插件枚举。
     *
     * <p>PostgreSQL 10+ 内置 {@code pgoutput}（推荐），其他可选 decoderbufs / wal2json。</p>
     */
    public enum DecodingPlugin {
        /** PostgreSQL 10+ 内置插件（推荐，原生 logical replication 协议）。 */
        PGOUTPUT("pgoutput"),
        /** Debezium decoderbufs 插件（需安装 logical_decoding）。 */
        DECODERBUFS("decoderbufs"),
        /** wal2json 插件（输出 JSON 格式变更）。 */
        WAL2JSON("wal2json"),
        /** wal2json_r2 插件（wal2json 增强版）。 */
        WAL2JSON_R2("wal2json_r2");

        private final String code;

        DecodingPlugin(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /**
         * 根据字符串编码解析为枚举值（大小写不敏感）。
         *
         * @param code 插件名编码
         * @return 对应枚举值
         * @throws IllegalArgumentException 若编码不被识别
         */
        public static DecodingPlugin fromCode(String code) {
            Objects.requireNonNull(code, "decoding plugin 不能为 null");
            for (DecodingPlugin plugin : values()) {
                if (plugin.code.equalsIgnoreCase(code)) {
                    return plugin;
                }
            }
            throw new IllegalArgumentException("未知的 decoding plugin: " + code);
        }
    }

    /** 基础配置（连接信息、启动模式等通用字段）。 */
    private SourceConfig base;

    /** Logical Replication Slot 名称（需先在 PG 中创建）。 */
    private String slotName = "flink_slot";

    /** 逻辑解码插件名。 */
    private DecodingPlugin decodingPlugin = DecodingPlugin.PGOUTPUT;

    /** 需要捕获的 schema 列表（支持正则表达式）。 */
    private Set<String> schemaList = new HashSet<>(Collections.singletonList("public"));

    /** 需要捕获的表列表（格式 schema.table，支持正则表达式）。 */
    private Set<String> tableList = new HashSet<>();

    /** Slot 持久化时是否在 DROP 后保留（用于故障恢复）。 */
    private boolean slotDropOnFinish = false;

    /** 默认构造器，供 YAML 反序列化。 */
    public PostgresSourceConfig() {
        this.base = new SourceConfig();
        this.base.setType(SourceConfig.SourceType.POSTGRESQL);
        this.base.setPort(5432);
    }

    /**
     * 全参构造器。
     *
     * @param base            基础配置
     * @param slotName        slot 名称
     * @param decodingPlugin  解码插件
     * @param schemaList      schema 列表
     * @param tableList       表列表
     * @param slotDropOnFinish 完成后是否删除 slot
     */
    public PostgresSourceConfig(SourceConfig base, String slotName, DecodingPlugin decodingPlugin,
                                Set<String> schemaList, Set<String> tableList,
                                boolean slotDropOnFinish) {
        this.base = base;
        this.slotName = slotName;
        this.decodingPlugin = decodingPlugin;
        this.schemaList = schemaList == null ? new HashSet<>() : new HashSet<>(schemaList);
        this.tableList = tableList == null ? new HashSet<>() : new HashSet<>(tableList);
        this.slotDropOnFinish = slotDropOnFinish;
    }

    /**
     * 获取基础配置。
     *
     * @return SourceConfig
     */
    public SourceConfig getBase() {
        return base;
    }

    public void setBase(SourceConfig base) {
        this.base = base;
    }

    public String getSlotName() {
        return slotName;
    }

    public void setSlotName(String slotName) {
        this.slotName = slotName;
    }

    public DecodingPlugin getDecodingPlugin() {
        return decodingPlugin;
    }

    public void setDecodingPlugin(DecodingPlugin decodingPlugin) {
        this.decodingPlugin = decodingPlugin;
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

    public boolean isSlotDropOnFinish() {
        return slotDropOnFinish;
    }

    public void setSlotDropOnFinish(boolean slotDropOnFinish) {
        this.slotDropOnFinish = slotDropOnFinish;
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
        if (!(o instanceof PostgresSourceConfig that)) {
            return false;
        }
        return slotDropOnFinish == that.slotDropOnFinish
                && Objects.equals(base, that.base)
                && Objects.equals(slotName, that.slotName)
                && decodingPlugin == that.decodingPlugin
                && Objects.equals(schemaList, that.schemaList)
                && Objects.equals(tableList, that.tableList);
    }

    @Override
    public int hashCode() {
        return Objects.hash(base, slotName, decodingPlugin, schemaList, tableList, slotDropOnFinish);
    }

    @Override
    public String toString() {
        return "PostgresSourceConfig{base=" + base
                + ", slotName='" + slotName + '\''
                + ", decodingPlugin=" + decodingPlugin
                + ", schemaList=" + schemaList
                + ", tableList=" + tableList
                + ", slotDropOnFinish=" + slotDropOnFinish + '}';
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
        private final PostgresSourceConfig config = new PostgresSourceConfig();

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
            return this;
        }

        public Builder startupMode(SourceConfig.StartupMode mode) {
            config.base.setStartupMode(mode);
            return this;
        }

        public Builder slotName(String slotName) {
            config.slotName = slotName;
            return this;
        }

        public Builder decodingPlugin(DecodingPlugin plugin) {
            config.decodingPlugin = plugin;
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

        public Builder slotDropOnFinish(boolean flag) {
            config.slotDropOnFinish = flag;
            return this;
        }

        public PostgresSourceConfig build() {
            config.base.setType(SourceConfig.SourceType.POSTGRESQL);
            if (config.base.getPort() == 3306) {
                config.base.setPort(5432);
            }
            return config;
        }
    }
}