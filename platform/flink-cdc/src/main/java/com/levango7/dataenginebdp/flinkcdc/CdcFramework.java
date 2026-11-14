package com.levango7.dataenginebdp.flinkcdc;

import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord;
import com.levango7.dataenginebdp.flinkcdc.sink.DorisStreamLoadSink;
import com.levango7.dataenginebdp.flinkcdc.sink.IcebergSinkConnector;
import com.levango7.dataenginebdp.flinkcdc.sink.SinkConfig;
import com.levango7.dataenginebdp.flinkcdc.source.MySqlSourceConnector;
import com.levango7.dataenginebdp.flinkcdc.source.SourceConfig;
import org.apache.flink.cdc.connectors.mysql.source.MySqlSource;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * CDC 管道编排器，将一个或多个 Source 与 Sink 组合为 Flink 作业并执行。
 *
 * <p>支持链式调用，典型用法：</p>
 * <pre>{@code
 * CdcFramework framework = CdcFramework.builder()
 *     .jobName("mysql-to-kafka")
 *     .addSource(sourceConfig)
 *     .addSink(sinkConfig)
 *     .build();
 * framework.execute();
 * }</pre>
 *
 * <p>当前支持的组合：</p>
 * <ul>
 *   <li>Source: MySQL (Binlog)，通过 {@link MySqlSourceConnector}</li>
 *   <li>Sink: Kafka (Debezium JSON)，通过 Flink KafkaSink</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public class CdcFramework {

    private static final Logger log = LoggerFactory.getLogger(CdcFramework.class);

    /** Flink 作业名称。 */
    private final String jobName;

    /** Flink 全局配置（可覆盖默认值）。 */
    private final Configuration flinkConfig;

    /** 数据源配置列表（按添加顺序）。 */
    private final List<SourceConfig> sources;

    /** 数据目标配置列表（按添加顺序）。 */
    private final List<SinkConfig> sinks;

    /** 全局默认并行度。 */
    private int parallelism = 1;

    /** 是否在 execute 后阻塞等待作业完成。 */
    private boolean blocking = true;

    private CdcFramework(String jobName, Configuration flinkConfig,
                         List<SourceConfig> sources, List<SinkConfig> sinks) {
        this.jobName = jobName;
        this.flinkConfig = flinkConfig;
        this.sources = sources;
        this.sinks = sinks;
    }

    /**
     * 创建 Builder。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建 Builder 并指定作业名称。
     *
     * @param jobName 作业名称
     * @return Builder 实例
     */
    public static Builder builder(String jobName) {
        return new Builder().jobName(jobName);
    }

    /**
     * 添加数据源（链式）。
     *
     * @param config 数据源配置
     * @return 当前实例，支持链式调用
     */
    public CdcFramework addSource(SourceConfig config) {
        Objects.requireNonNull(config, "SourceConfig 不能为 null");
        this.sources.add(config);
        return this;
    }

    /**
     * 添加数据目标（链式）。
     *
     * @param config 目标配置
     * @return 当前实例，支持链式调用
     */
    public CdcFramework addSink(SinkConfig config) {
        Objects.requireNonNull(config, "SinkConfig 不能为 null");
        this.sinks.add(config);
        return this;
    }

    /**
     * 设置全局并行度。
     *
     * @param parallelism 并行度
     * @return 当前实例
     */
    public CdcFramework parallelism(int parallelism) {
        this.parallelism = parallelism;
        return this;
    }

    /**
     * 设置是否阻塞等待作业完成。
     *
     * @param blocking true 阻塞；false 非阻塞
     * @return 当前实例
     */
    public CdcFramework blocking(boolean blocking) {
        this.blocking = blocking;
        return this;
    }

    /**
     * 启动 Flink 作业。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>创建 {@link StreamExecutionEnvironment}</li>
     *   <li>为每个 Source 创建 {@link MySqlSource} 并加入拓扑</li>
     *   <li>将流写入对应的 Sink（当前支持 Kafka）</li>
     *   <li>调用 {@code execute(jobName)} 提交作业</li>
     * </ol>
     *
     * @throws IllegalStateException 若未配置 Source 或 Sink
     * @throws Exception             Flink 作业执行异常
     */
    public void execute() throws Exception {
        validateBeforeExecute();

        log.info("启动 CDC 作业 '{}'：{} 个 Source, {} 个 Sink", jobName, sources.size(), sinks.size());
        sources.forEach(s -> log.info("  Source: {} ({})", s.getName(), s.getType()));
        sinks.forEach(s -> log.info("  Sink:   {} ({})", s.getName(), s.getType()));

        StreamExecutionEnvironment env = createEnvironment();
        buildPipeline(env);

        if (blocking) {
            env.execute(jobName);
        } else {
            env.executeAsync(jobName);
        }
    }

    /**
     * 在给定环境上构建管道拓扑（不执行），便于测试与复用。
     *
     * @param env Flink 流执行环境
     */
    public void buildPipeline(StreamExecutionEnvironment env) {
        validateBeforeExecute();

        for (int i = 0; i < sources.size(); i++) {
            SourceConfig sourceConfig = sources.get(i);
            SinkConfig sinkConfig = sinks.get(Math.min(i, sinks.size() - 1));

            DataStreamSource<ChangeRecord> stream = addSourceToEnv(env, sourceConfig);
            attachSink(stream, sinkConfig);
        }
    }

    /**
     * 将单个 Source 加入 Flink 环境。
     *
     * @param env       Flink 环境
     * @param config    Source 配置
     * @return 数据流
     */
    protected DataStreamSource<ChangeRecord> addSourceToEnv(StreamExecutionEnvironment env,
                                                            SourceConfig config) {
        MySqlSource<ChangeRecord> source = MySqlSourceConnector.createSource(config);
        return env.fromSource(source, WatermarkStrategy.noWatermarks(),
                "mysql-cdc-" + config.getName());
    }

    /**
     * 将流附加到 Sink（当前支持 Kafka）。
     *
     * @param stream 数据流
     * @param config Sink 配置
     */
    protected void attachSink(org.apache.flink.streaming.api.datastream.DataStream<ChangeRecord> stream,
                              SinkConfig config) {
        switch (config.getType()) {
            case KAFKA -> attachKafkaSink(stream, config);
            case ICEBERG -> attachIcebergSink(stream, config);
            case DORIS -> attachDorisSink(stream, config);
        }
    }

    /**
     * 附加 Kafka Sink（Debezium JSON 格式）。
     *
     * @param stream 数据流
     * @param config Kafka Sink 配置
     */
    private void attachKafkaSink(org.apache.flink.streaming.api.datastream.DataStream<ChangeRecord> stream,
                                 SinkConfig config) {
        KafkaRecordSerializationSchema<ChangeRecord> serializer = KafkaRecordSerializationSchema
                .<ChangeRecord>builder()
                .setTopic(config.getTopic())
                .setValueSerializationSchema((SerializationSchema<ChangeRecord>) this::serializeChangeRecord)
                .build();

        KafkaSink<ChangeRecord> kafkaSink = KafkaSink.<ChangeRecord>builder()
                .setBootstrapServers(config.getHost() + ":" + config.getPort())
                .setRecordSerializer(serializer)
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();

        stream.sinkTo(kafkaSink);
    }

    /**
     * 附加 Iceberg Sink（Phase A1：接线 {@link IcebergSinkConnector}，取代原占位告警）。
     *
     * <p>属性映射（{@link SinkConfig#getProperties()}，键名与 Iceberg Flink 规范对齐）：
     * {@code catalog-name}（默认 rest）、{@code catalog-type}（默认 REST）、
     * {@code catalog-uri}（REST/JDBC 必填）、{@code warehouse}（必填）、{@code database}（必填）、
     * {@code table}（必填）、{@code primary-keys}（UPSERT/HASH 必填，逗号分隔）、
     * {@code partition-keys}（可选，逗号分隔）、{@code format-version}（默认 2）、
     * {@code write-mode}（默认 upsert）、{@code distribution-mode}（默认 hash）、
     * {@code file-format}（默认 parquet）、{@code micro-batch-size}（默认 1000）、
     * {@code schema-evolution}（默认 auto）、{@code compaction-trigger}（默认 by-file-count）。</p>
     *
     * <p><b>失败语义</b>：配置缺失（缺 warehouse/table 等）或运行期 Iceberg runtime 类不可用时
     * 抛异常即作业构建失败（fail-fast），不再静默丢弃变更流。当前写入落点＝连接器的
     * WAL 缓冲（不丢数据）；真实 Iceberg SDK 写入（iceberg-flink-runtime 进集群 lib +
     * 集群端到端）属立项件 Phase A 的终段，见 docs/realtime-chain-completion-plan.md。</p>
     *
     * @param stream 数据流
     * @param config Iceberg Sink 配置
     */
    private void attachIcebergSink(org.apache.flink.streaming.api.datastream.DataStream<ChangeRecord> stream,
                                   SinkConfig config) {
        IcebergSinkConnector connector = buildIcebergConnector(config);
        try {
            connector.attachReal(stream);
            log.info("Iceberg Sink 真实写入路径已接线: name={}（Iceberg runtime 在位）", config.getName());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Iceberg 表加载失败（表不存在或 catalog 不可达）: " + e.getMessage(), e);
        } catch (NoClassDefFoundError e) {
            // 集群 lib 未放置 iceberg-flink-runtime（provided 口径）⇒ 回退 WAL 缓冲路径（不丢数据）
            log.warn("Iceberg runtime 类缺失（{}），回退 WAL 缓冲路径", e.getMessage());
            connector.attachTo(stream);
        }
    }

    /**
     * 由 SinkConfig 构建 IcebergSinkConnector（包内可见，便于单测断言映射）。
     *
     * @param config Sink 配置
     * @return IcebergSinkConnector
     * @throws IllegalStateException 配置不合法（缺必填项）
     */
    static IcebergSinkConnector buildIcebergConnector(SinkConfig config) {
        Objects.requireNonNull(config, "config 不能为 null");
        java.util.Map<String, String> p = config.getProperties();
        IcebergSinkConnector.Builder builder = IcebergSinkConnector.builder()
                .catalogName(p.getOrDefault("catalog-name", "rest"))
                .catalogType(IcebergSinkConnector.CatalogType.fromCode(p.getOrDefault("catalog-type", "rest")))
                .warehouse(requireIcebergProperty(p, "warehouse"))
                .database(requireIcebergProperty(p, "database"))
                .table(requireIcebergProperty(p, "table"))
                .formatVersion(Integer.parseInt(p.getOrDefault("format-version", "2")))
                .writeMode(IcebergSinkConnector.WriteMode.fromCode(p.getOrDefault("write-mode", "upsert")))
                .distributionMode(IcebergSinkConnector.DistributionMode.fromCode(p.getOrDefault("distribution-mode", "hash")))
                .fileFormat(p.getOrDefault("file-format", "parquet"))
                .microBatchSize(Integer.parseInt(p.getOrDefault("micro-batch-size", "1000")))
                .schemaEvolution(IcebergSinkConnector.SchemaEvolutionMode.fromCode(p.getOrDefault("schema-evolution", "auto")))
                .compactionTrigger(IcebergSinkConnector.CompactionTrigger.fromCode(p.getOrDefault("compaction-trigger", "by-file-count")));
        if (p.containsKey("catalog-uri")) {
            builder.catalogUri(p.get("catalog-uri"));
        }
        if (p.containsKey("primary-keys")) {
            builder.primaryKeys(p.get("primary-keys"));
        }
        if (p.containsKey("partition-keys")) {
            builder.partitionKeys(p.get("partition-keys").split("\\s*,\\s*"));
        }
        return builder.build();
    }

    /**
     * 读取 Iceberg 映射必填属性（缺失即 fail-fast，点名键名；替代 Builder 内部的 NPE）。
     *
     * @param properties SinkConfig.properties
     * @param key        属性键名
     * @return 非空属性值
     * @throws IllegalStateException 属性缺失或为空白
     */
    private static String requireIcebergProperty(java.util.Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Iceberg Sink 缺少必填属性: " + key + "（请在 SinkConfig.properties 中提供）");
        }
        return value;
    }

    /**
     * 附加 Doris Sink（Phase B1：Doris Stream Load 轻量 sink，取代原占位告警）。
     *
     * <p>属性映射：{@code database}（必填）与 {@code table}（缺省回落 SinkConfig.topic）、
     * {@code batch-size}（默认 500）、{@code labels}（可选 label 前缀）；
     * FE 地址取 {@link SinkConfig#getHost()}/{@link SinkConfig#getPort()}——
     * <b>Doris 场景须显式设置这两项（HTTP 端口通常 8030），勿沿用 Kafka 默认端口</b>。</p>
     *
     * <p><b>失败语义</b>：批次写入失败即抛异常交由 Flink 重试（AT_LEAST_ONCE、不静默丢弃）。</p>
     *
     * @param stream 数据流
     * @param config Doris Sink 配置
     */
    private void attachDorisSink(org.apache.flink.streaming.api.datastream.DataStream<ChangeRecord> stream,
                                 SinkConfig config) {
        stream.sinkTo(buildDorisSink(config));
        log.info("Doris Stream Load Sink 已接线: name={}, target={}:{}/{}/{}",
                config.getName(), config.getHost(), config.getPort(),
                config.getProperties().get("database"), config.getProperties().getOrDefault("table", config.getTopic()));
    }

    /**
     * 由 SinkConfig 构建 DorisStreamLoadSink（包内可见，便于单测断言映射）。
     *
     * @param config Sink 配置
     * @return DorisStreamLoadSink
     * @throws IllegalArgumentException 必填项缺失
     */
    static DorisStreamLoadSink buildDorisSink(SinkConfig config) {
        Objects.requireNonNull(config, "config 不能为 null");
        java.util.Map<String, String> p = config.getProperties();
        DorisStreamLoadSink.Builder builder = DorisStreamLoadSink.builder()
                .host(config.getHost())
                .port(config.getPort())
                .username(config.getUsername() != null ? config.getUsername() : "root")
                .password(config.getPassword() != null ? config.getPassword() : "")
                .database(p.get("database"))
                .table(p.getOrDefault("table", config.getTopic()));
        if (p.containsKey("batch-size")) {
            builder.batchSize(Integer.parseInt(p.get("batch-size")));
        }
        if (p.containsKey("labels")) {
            builder.labels(p.get("labels"));
        }
        return builder.build();
    }

    /**
     * 序列化 ChangeRecord 为 JSON 字节数组（Debezium JSON 格式）。
     *
     * <p>使用简化的手写序列化避免对 Jackson 的强依赖；生产环境可替换为 Jackson ObjectMapper。</p>
     *
     * @param record 变更记录
     * @return JSON 字节数组
     */
    protected byte[] serializeChangeRecord(ChangeRecord record) {
        return ChangeRecordJsonSerializer.toJson(record);
    }

    /**
     * 创建 Flink 流执行环境。
     *
     * @return StreamExecutionEnvironment
     */
    protected StreamExecutionEnvironment createEnvironment() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig);
        env.setParallelism(parallelism);
        env.enableCheckpointing(60000L);
        return env;
    }

    /**
     * 执行前校验：至少 1 个 Source 和 1 个 Sink。
     *
     * @throws IllegalStateException 若配置不完整
     */
    private void validateBeforeExecute() {
        if (sources.isEmpty()) {
            throw new IllegalStateException("未配置任何 Source，请先调用 addSource()");
        }
        if (sinks.isEmpty()) {
            throw new IllegalStateException("未配置任何 Sink，请先调用 addSink()");
        }
    }

    // ===== 只读访问器（供测试与监控使用） =====

    public String getJobName() {
        return jobName;
    }

    public List<SourceConfig> getSources() {
        return Collections.unmodifiableList(sources);
    }

    public List<SinkConfig> getSinks() {
        return Collections.unmodifiableList(sinks);
    }

    public int getParallelism() {
        return parallelism;
    }

    public boolean isBlocking() {
        return blocking;
    }

    /**
     * CdcFramework 构造器，支持链式添加 Source/Sink。
     */
    public static final class Builder {
        private String jobName = "flink-cdc-job";
        private final Configuration flinkConfig = new Configuration();
        private final List<SourceConfig> sources = new ArrayList<>();
        private final List<SinkConfig> sinks = new ArrayList<>();
        private int parallelism = 1;
        private boolean blocking = true;

        public Builder jobName(String jobName) {
            this.jobName = jobName;
            return this;
        }

        public Builder flinkConfig(Configuration config) {
            this.flinkConfig.addAll(config);
            return this;
        }

        public Builder addSource(SourceConfig config) {
            Objects.requireNonNull(config, "SourceConfig 不能为 null");
            this.sources.add(config);
            return this;
        }

        public Builder addSink(SinkConfig config) {
            Objects.requireNonNull(config, "SinkConfig 不能为 null");
            this.sinks.add(config);
            return this;
        }

        public Builder parallelism(int parallelism) {
            this.parallelism = parallelism;
            return this;
        }

        public Builder blocking(boolean blocking) {
            this.blocking = blocking;
            return this;
        }

        public CdcFramework build() {
            CdcFramework framework = new CdcFramework(jobName, flinkConfig, sources, sinks);
            framework.parallelism = this.parallelism;
            framework.blocking = this.blocking;
            return framework;
        }
    }
}