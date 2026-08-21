package com.shuqing.bigdata.flinkcdc;

import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import com.shuqing.bigdata.flinkcdc.sink.SinkConfig;
import com.shuqing.bigdata.flinkcdc.source.MySqlSourceConnector;
import com.shuqing.bigdata.flinkcdc.source.SourceConfig;
import com.ververica.cdc.connectors.mysql.source.MySqlSource;
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
     * 附加 Iceberg Sink（占位实现，后续扩展）。
     *
     * @param stream 数据流
     * @param config Iceberg Sink 配置
     */
    private void attachIcebergSink(org.apache.flink.streaming.api.datastream.DataStream<ChangeRecord> stream,
                                   SinkConfig config) {
        log.warn("Iceberg Sink 尚未实现，配置 '{}' 将被忽略（流不接入 Sink）", config.getName());
    }

    /**
     * 附加 Doris Sink（占位实现，后续扩展）。
     *
     * @param stream 数据流
     * @param config Doris Sink 配置
     */
    private void attachDorisSink(org.apache.flink.streaming.api.datastream.DataStream<ChangeRecord> stream,
                                 SinkConfig config) {
        log.warn("Doris Sink 尚未实现，配置 '{}' 将被忽略（流不接入 Sink）", config.getName());
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