package com.shuqing.bigdata.flinkcdc.source;

import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import com.ververica.cdc.connectors.mysql.source.MySqlSource;
import com.ververica.cdc.connectors.oracle.source.OracleSourceBuilder;
import com.ververica.cdc.connectors.postgres.source.PostgresSourceBuilder;
import org.apache.flink.api.connector.source.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Source 连接器统一工厂，根据 {@link SourceConfig.SourceType} 返回对应的 Flink CDC Source。
 *
 * <p>统一封装 MySQL / PostgreSQL / Oracle 三种连接器的创建逻辑，对外提供单一入口，
 * 便于在 {@code CdcFramework} 中按配置类型动态选择连接器，避免 if-else 散落各处。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * SourceConfig config = ...;  // 由 YAML 加载
 * Source<ChangeRecord, ?, ?> source = SourceConnectorFactory.createSource(config);
 * env.fromSource(source, WatermarkStrategy.noWatermarks(), "cdc-" + config.getName());
 * }</pre>
 *
 * <p>对于 PostgreSQL / Oracle，由于它们有特有配置（slotName / serviceName / schemaList 等），
 * 需通过 {@link #createPostgresSource(PostgresSourceConfig)} / {@link #createOracleSource(OracleSourceConfig)}
 * 显式调用，或使用 {@link #createSource(SourceConfig)} 时通过 base 中的扩展属性传递。</p>
 *
 * @author shuqing-bigdata
 */
public final class SourceConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(SourceConnectorFactory.class);

    private SourceConnectorFactory() {
        // 工具类，禁止实例化
    }

    /**
     * 根据基础 {@link SourceConfig} 的 {@link SourceConfig.SourceType} 创建对应的 Source。
     *
     * <p>对于 MySQL 类型直接委托 {@link MySqlSourceConnector#createSource(SourceConfig)}；
     * 对于 PostgreSQL / Oracle 类型，需使用对应的专用配置类（含特有字段），
     * 此处仅基于基础配置构造默认专用配置后委托。</p>
     *
     * @param config 基础配置
     * @return Flink Source（输出 ChangeRecord）
     * @throws NullPointerException     若 config 为 null
     * @throws IllegalArgumentException 若 sourceType 不被支持
     * @throws IllegalStateException    若配置不完整
     */
    public static Source<ChangeRecord, ?, ?> createSource(SourceConfig config) {
        Objects.requireNonNull(config, "SourceConfig 不能为 null");
        SourceConfig.SourceType type = config.getType();
        if (type == null) {
            throw new IllegalArgumentException("SourceConfig.type 不能为 null");
        }

        log.info("SourceConnectorFactory: 创建 {} 类型 Source，name={}", type, config.getName());

        return switch (type) {
            case MYSQL -> MySqlSourceConnector.createSource(config);
            case POSTGRESQL -> createPostgresSource(toPostgresConfig(config));
            case ORACLE -> createOracleSource(toOracleConfig(config));
        };
    }

    /**
     * 创建 PostgreSQL Source（使用专用配置）。
     *
     * @param config PostgreSQL 专用配置
     * @return PostgresIncrementalSource
     */
    public static PostgresSourceBuilder.PostgresIncrementalSource<ChangeRecord> createPostgresSource(
            PostgresSourceConfig config) {
        return PostgresSourceConnector.createSource(config);
    }

    /**
     * 创建 Oracle Source（使用专用配置）。
     *
     * @param config Oracle 专用配置
     * @return OracleIncrementalSource
     */
    public static OracleSourceBuilder.OracleIncrementalSource<ChangeRecord> createOracleSource(
            OracleSourceConfig config) {
        return OracleSourceConnector.createSource(config);
    }

    /**
     * 创建 MySQL Source（直接委托给 MySqlSourceConnector）。
     *
     * @param config 基础配置
     * @return MySqlSource
     */
    public static MySqlSource<ChangeRecord> createMysqlSource(SourceConfig config) {
        return MySqlSourceConnector.createSource(config);
    }

    /**
     * 将基础 {@link SourceConfig} 转换为 {@link PostgresSourceConfig}（携带默认 PG 特有配置）。
     *
     * <p>当用户仅提供基础配置时，使用默认 slotName=flink_slot、plugin=pgoutput、
     * schema=public、table 解析自 base.table。</p>
     *
     * @param base 基础配置
     * @return PostgreSQL 专用配置
     */
    public static PostgresSourceConfig toPostgresConfig(SourceConfig base) {
        Objects.requireNonNull(base, "SourceConfig 不能为 null");
        PostgresSourceConfig.Builder builder = PostgresSourceConfig.builder()
                .base(base)
                .slotName("flink_slot")
                .decodingPlugin(PostgresSourceConfig.DecodingPlugin.PGOUTPUT)
                .schemaList("public");

        if (base.getTable() != null && !base.getTable().isBlank()) {
            builder.tableList(base.getTable());
        }
        return builder.build();
    }

    /**
     * 将基础 {@link SourceConfig} 转换为 {@link OracleSourceConfig}（携带默认 Oracle 特有配置）。
     *
     * <p>当用户仅提供基础配置时，使用默认 logMinerOption=both、useXStream=false，
     * schema/table 解析自 base.table，serviceName 取自 base.database。</p>
     *
     * @param base 基础配置
     * @return Oracle 专用配置
     */
    public static OracleSourceConfig toOracleConfig(SourceConfig base) {
        Objects.requireNonNull(base, "SourceConfig 不能为 null");
        OracleSourceConfig.Builder builder = OracleSourceConfig.builder()
                .base(base)
                .logMinerOption(OracleSourceConfig.LogMinerOption.BOTH)
                .useXStream(false);

        if (base.getDatabase() != null && !base.getDatabase().isBlank()) {
            builder.serviceName(base.getDatabase());
        }
        if (base.getTable() != null && !base.getTable().isBlank()) {
            String[] parts = base.parseTable();
            if (parts[0] != null) {
                builder.schemaList(parts[0].toUpperCase());
            }
            builder.tableList(base.getTable().toUpperCase());
        }
        return builder.build();
    }

    /**
     * 根据类型字符串解析为 {@link SourceConfig.SourceType} 枚举。
     *
     * @param type 类型字符串（mysql / postgresql / oracle，大小写不敏感）
     * @return SourceType 枚举
     * @throws IllegalArgumentException 若类型不被支持
     */
    public static SourceConfig.SourceType parseSourceType(String type) {
        Objects.requireNonNull(type, "source type 不能为 null");
        String normalized = type.trim().toUpperCase();
        return switch (normalized) {
            case "MYSQL", "MY_SQL" -> SourceConfig.SourceType.MYSQL;
            case "POSTGRES", "POSTGRESQL", "PG" -> SourceConfig.SourceType.POSTGRESQL;
            case "ORACLE", "ORA" -> SourceConfig.SourceType.ORACLE;
            default -> throw new IllegalArgumentException("不支持的 source type: " + type);
        };
    }
}
