package com.shuqing.bigdata.flinkcdc.source;

import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import com.ververica.cdc.connectors.mysql.source.MySqlSource;
import com.ververica.cdc.connectors.mysql.source.MySqlSourceBuilder;
import com.ververica.cdc.connectors.mysql.table.StartupOptions;
import com.ververica.cdc.debezium.DebeziumDeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.util.Collector;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * MySQL Binlog Source 连接器，基于 Flink CDC 3.0 的 {@link MySqlSource}。
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>全量初始化：首次启动时通过无锁快照算法读取表全量数据</li>
 *   <li>增量 Binlog：全量完成后无缝衔接到 Binlog 最新位点继续读取</li>
 *   <li>变更类型：完整捕获 INSERT / UPDATE / DELETE（及快照读 SNAPSHOT）</li>
 *   <li>过滤配置：通过 server-id / database / table 精确控制读取范围</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * SourceConfig config = new SourceConfig.Builder()
 *     .name("mysql-orders").type(SourceType.MYSQL)
 *     .host("127.0.0.1").port(3306)
 *     .username("cdc").password("cdc-pass")
 *     .database("shop").table("shop.orders")
 *     .serverId(5400).startupMode(StartupMode.INITIAL)
 *     .build();
 * MySqlSource<ChangeRecord> source = MySqlSourceConnector.createSource(config);
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public final class MySqlSourceConnector {

    private static final Logger log = LoggerFactory.getLogger(MySqlSourceConnector.class);

    /** Debezium value 结构中的 before 字段名。 */
    public static final String FIELD_BEFORE = "before";
    /** Debezium value 结构中的 after 字段名。 */
    public static final String FIELD_AFTER = "after";
    /** Debezium value 结构中的 op 字段名。 */
    public static final String FIELD_OP = "op";
    /** Debezium value 结构中的 source 字段名。 */
    public static final String FIELD_SOURCE = "source";
    /** Debezium value 结构中的 ts_ms 字段名。 */
    public static final String FIELD_TS_MS = "ts_ms";

    private MySqlSourceConnector() {
        // 工具类，禁止实例化
    }

    /**
     * 根据 {@link SourceConfig} 构造 Flink CDC 3.0 的 {@link MySqlSource}。
     *
 * <p>根据 {@link SourceConfig#getStartupMode()} 选择启动选项：</p>
     * <ul>
     *   <li>{@code INITIAL} → 全量快照 + 增量 Binlog</li>
     *   <li>{@code LATEST_OFFSET} → 仅增量，从最新位点开始</li>
     *   <li>{@code TIMESTAMP} → 仅增量，从指定时间戳开始</li>
     *   <li>{@code SPECIFIC_OFFSET} → 仅增量，从指定 Binlog 位点开始</li>
     * </ul>
     *
     * @param config 数据源配置
     * @return 已配置的 MySqlSource，输出类型为 {@link ChangeRecord}
     * @throws NullPointerException  若 config 为 null
     * @throws IllegalStateException  若缺少必要连接信息
     */
    public static MySqlSource<ChangeRecord> createSource(SourceConfig config) {
        Objects.requireNonNull(config, "SourceConfig 不能为 null");
        validate(config);

        log.info("构建 MySqlSource: {} @ {}:{}/{}", config.getName(), config.getHost(),
                config.getPort(), config.getDatabase());

        MySqlSourceBuilder<ChangeRecord> builder = MySqlSource.<ChangeRecord>builder()
                .hostname(config.getHost())
                .port(config.getPort())
                .username(config.getUsername())
                .password(config.getPassword())
                .databaseList(config.getDatabase())
                .tableList(config.getTable())
                .serverId(String.valueOf(config.getServerId()))
                .deserializer(new ChangeRecordDeserializer());

        builder.startupOptions(resolveStartupOptions(config));

        return builder.build();
    }

    /**
     * 校验配置完整性。
     *
     * @param config 数据源配置
     * @throws IllegalStateException 若缺少必要字段
     */
    public static void validate(SourceConfig config) {
        if (config.getHost() == null || config.getHost().isBlank()) {
            throw new IllegalStateException("SourceConfig.host 不能为空");
        }
        if (config.getUsername() == null || config.getUsername().isBlank()) {
            throw new IllegalStateException("SourceConfig.username 不能为空");
        }
        if (config.getDatabase() == null || config.getDatabase().isBlank()) {
            throw new IllegalStateException("SourceConfig.database 不能为空");
        }
        if (config.getTable() == null || config.getTable().isBlank()) {
            throw new IllegalStateException("SourceConfig.table 不能为空");
        }
        if (config.getServerId() <= 0) {
            throw new IllegalStateException("SourceConfig.serverId 必须为正数");
        }
        if (config.getStartupMode() == SourceConfig.StartupMode.TIMESTAMP
                && config.getStartupTimestampMillis() == null) {
            throw new IllegalStateException("TIMESTAMP 启动模式需指定 startupTimestampMillis");
        }
        if (config.getStartupMode() == SourceConfig.StartupMode.SPECIFIC_OFFSET) {
            if (config.getBinlogFilename() == null || config.getBinlogFilename().isBlank()) {
                throw new IllegalStateException("SPECIFIC_OFFSET 启动模式需指定 binlogFilename");
            }
            if (config.getBinlogPosition() == null) {
                throw new IllegalStateException("SPECIFIC_OFFSET 启动模式需指定 binlogPosition");
            }
        }
    }

    /**
     * 根据启动模式解析对应的 {@link StartupOptions}。
     *
     * <p>独立为方法便于测试启动模式映射逻辑。</p>
     *
     * @param config 数据源配置
     * @return Flink CDC StartupOptions
     */
    public static StartupOptions resolveStartupOptions(SourceConfig config) {
        return switch (config.getStartupMode()) {
            case INITIAL -> StartupOptions.initial();
            case LATEST_OFFSET -> StartupOptions.latest();
            case TIMESTAMP -> StartupOptions.timestamp(config.getStartupTimestampMillis());
            case SPECIFIC_OFFSET -> StartupOptions.specificOffset(
                    config.getBinlogFilename(), config.getBinlogPosition());
        };
    }

    /**
     * 将提取出的原始字段组装为 {@link ChangeRecord}（纯函数，便于单元测试）。
     *
     * @param before 变更前快照（INSERT 时为 null）
     * @param after  变更后快照（DELETE 时为 null）
     * @param opCode Debezium op 编码 (c/u/d/r)
     * @param source 来源元数据
     * @param tsMs   变更时间戳（毫秒）
     * @return 组装好的 ChangeRecord
     */
    public static ChangeRecord toChangeRecord(Map<String, Object> before,
                                              Map<String, Object> after,
                                              String opCode,
                                              Map<String, Object> source,
                                              Long tsMs) {
        return new ChangeRecord(before, after, opCode, source, tsMs);
    }

    /**
     * Debezium {@link SourceRecord} → {@link ChangeRecord} 反序列化器。
     *
     * <p>从 Debezium 事件 value (Struct) 中提取 before/after/op/source/ts_ms，
     * 转换为平台统一的 {@link ChangeRecord} 模型。before/after 中的列值
     * 按原样保留（Object），由下游 Sink 根据目标类型再做转换。</p>
     */
    public static final class ChangeRecordDeserializer
            implements DebeziumDeserializationSchema<ChangeRecord> {

        private static final long serialVersionUID = 1L;

        @Override
        public void deserialize(SourceRecord record, Collector<ChangeRecord> out) {
            Object valueObj = record.value();
            if (!(valueObj instanceof Struct value)) {
                // tombstone 记录（DELETE 后 Debezium 会发送 value=null 的墓碑消息），跳过
                return;
            }

            Map<String, Object> before = extractStruct(value, FIELD_BEFORE);
            Map<String, Object> after = extractStruct(value, FIELD_AFTER);
            String opCode = value.getString(FIELD_OP);
            Map<String, Object> source = extractStruct(value, FIELD_SOURCE);
            Long tsMs = value.getInt64(FIELD_TS_MS);

            out.collect(toChangeRecord(before, after, opCode, source, tsMs));
        }

        @Override
        public TypeInformation<ChangeRecord> getProducedType() {
            return TypeInformation.of(ChangeRecord.class);
        }

        /**
         * 从 Struct 中提取字段为 Map（列名 → 列值）。
         *
         * @param value     Debezium value Struct
         * @param fieldName 字段名 (before/after/source)
         * @return Map；若字段为 null 返回 {@code null}
         */
        private static Map<String, Object> extractStruct(Struct value, String fieldName) {
            Struct sub = value.getStruct(fieldName);
            if (sub == null) {
                return null;
            }
            Schema schema = sub.schema();
            Map<String, Object> map = new HashMap<>();
            for (Field field : schema.fields()) {
                map.put(field.name(), sub.get(field));
            }
            return map;
        }
    }
}
