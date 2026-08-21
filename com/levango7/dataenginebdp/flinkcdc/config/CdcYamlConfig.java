package com.shuqing.bigdata.flinkcdc.config;

import com.shuqing.bigdata.flinkcdc.sink.SinkConfig;
import com.shuqing.bigdata.flinkcdc.source.SourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CDC YAML 声明式配置加载器。
 *
 * <p>从 YAML 文件加载作业级配置（名称/并行度）以及多个 Source 和 Sink 配置，
 * 支持"一份配置描述整条 CDC 管道"的声明式风格。</p>
 *
 * <p>YAML 结构示例：</p>
 * <pre>{@code
 * job:
 *   name: mysql-to-kafka
 *   parallelism: 2
 *
 * sources:
 *   - name: mysql-orders
 *     type: MYSQL
 *     host: 127.0.0.1
 *     port: 3306
 *     username: cdc
 *     password: cdc-pass
 *     database: shop
 *     table: shop.orders
 *     serverId: 5400
 *     startupMode: initial
 *
 * sinks:
 *   - name: kafka-orders
 *     type: KAFKA
 *     host: 127.0.0.1
 *     port: 9092
 *     topic: cdc-orders
 *     writeMode: upsert
 *     format: debezium-json
 *     primaryKey: id
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public class CdcYamlConfig {

    private static final Logger log = LoggerFactory.getLogger(CdcYamlConfig.class);

    /** 作业名称。 */
    private String jobName = "flink-cdc-job";
    /** 作业并行度。 */
    private int parallelism = 1;
    /** 数据源配置列表。 */
    private List<SourceConfig> sources = new ArrayList<>();
    /** 数据目标配置列表。 */
    private List<SinkConfig> sinks = new ArrayList<>();

    /** 默认构造器。 */
    public CdcYamlConfig() {
    }

    /**
     * 从 YAML 文件路径加载配置。
     *
     * @param path YAML 文件路径
     * @return 解析后的 CdcYamlConfig
     * @throws IOException 读取文件失败
     * @throws IllegalArgumentException YAML 格式错误
     */
    public static CdcYamlConfig load(Path path) throws IOException {
        Objects.requireNonNull(path, "配置文件路径不能为 null");
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        }
    }

    /**
     * 从 YAML 文件路径（字符串）加载配置。
     *
     * @param path YAML 文件路径
     * @return 解析后的 CdcYamlConfig
     * @throws IOException 读取文件失败
     */
    public static CdcYamlConfig load(String path) throws IOException {
        return load(Path.of(path));
    }

    /**
     * 从输入流加载 YAML 配置。
     *
     * @param inputStream YAML 输入流
     * @return 解析后的 CdcYamlConfig
     * @throws IllegalArgumentException YAML 格式错误
     */
    @SuppressWarnings("unchecked")
    public static CdcYamlConfig load(InputStream inputStream) {
        Objects.requireNonNull(inputStream, "输入流不能为 null");
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(inputStream);
        if (root == null) {
            throw new IllegalArgumentException("YAML 内容为空");
        }
        return parse(root);
    }

    /**
     * 从类路径资源加载 YAML 配置。
     *
     * @param resourcePath 类路径资源名
     * @return 解析后的 CdcYamlConfig
     * @throws IOException 读取资源失败
     */
    public static CdcYamlConfig loadFromClasspath(String resourcePath) throws IOException {
        Objects.requireNonNull(resourcePath, "资源路径不能为 null");
        try (InputStream in = CdcYamlConfig.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("类路径资源不存在: " + resourcePath);
            }
            return load(in);
        }
    }

    /**
     * 从已解析的 Map 树构造配置（核心解析逻辑，便于单元测试）。
     *
     * @param root YAML 根 Map
     * @return CdcYamlConfig
     */
    @SuppressWarnings("unchecked")
    public static CdcYamlConfig parse(Map<String, Object> root) {
        Objects.requireNonNull(root, "YAML root 不能为 null");
        CdcYamlConfig config = new CdcYamlConfig();

        // 解析 job 段
        Object jobObj = root.get("job");
        if (jobObj instanceof Map<?, ?> jobMap) {
            config.jobName = str(jobMap.get("name"), "flink-cdc-job");
            config.parallelism = intVal(jobMap.get("parallelism"), 1);
        }

        // 解析 sources 段
        Object sourcesObj = root.get("sources");
        if (sourcesObj instanceof List<?> sourcesList) {
            for (Object item : sourcesList) {
                if (item instanceof Map<?, ?> sourceMap) {
                    config.sources.add(parseSource((Map<String, Object>) sourceMap));
                }
            }
        }

        // 解析 sinks 段
        Object sinksObj = root.get("sinks");
        if (sinksObj instanceof List<?> sinksList) {
            for (Object item : sinksList) {
                if (item instanceof Map<?, ?> sinkMap) {
                    config.sinks.add(parseSink((Map<String, Object>) sinkMap));
                }
            }
        }

        log.info("加载 CDC 配置: job={}, parallelism={}, sources={}, sinks={}",
                config.jobName, config.parallelism, config.sources.size(), config.sinks.size());
        return config;
    }

    /**
     * 解析单个 Source 配置。
     *
     * @param map Source YAML Map
     * @return SourceConfig
     */
    static SourceConfig parseSource(Map<String, Object> map) {
        SourceConfig config = new SourceConfig();
        config.setName(str(map.get("name"), null));
        config.setType(SourceConfig.SourceType.valueOf(
                str(map.get("type"), "MYSQL").toUpperCase()));
        config.setHost(str(map.get("host"), "localhost"));
        config.setPort(intVal(map.get("port"), 3306));
        config.setUsername(str(map.get("username"), null));
        config.setPassword(str(map.get("password"), null));
        config.setDatabase(str(map.get("database"), null));
        config.setTable(str(map.get("table"), null));
        config.setServerId(longVal(map.get("serverId"), 5400L));
        config.setStartupMode(SourceConfig.StartupMode.fromCode(
                str(map.get("startupMode"), "initial")));
        config.setStartupTimestampMillis(longValOrNull(map.get("startupTimestampMillis")));
        config.setBinlogFilename(str(map.get("binlogFilename"), null));
        config.setBinlogPosition(longValOrNull(map.get("binlogPosition")));
        config.setIncludeSchemaChanges(boolVal(map.get("includeSchemaChanges"), false));
        config.setParallelism(intVal(map.get("parallelism"), 1));
        config.setSplitColumn(str(map.get("splitColumn"), null));

        Object tableFiltersObj = map.get("tableFilters");
        if (tableFiltersObj instanceof List<?> filters) {
            java.util.Set<String> filterSet = new java.util.HashSet<>();
            for (Object f : filters) {
                if (f != null) {
                    filterSet.add(String.valueOf(f));
                }
            }
            config.setTableFilters(filterSet);
        }
        return config;
    }

    /**
     * 解析单个 Sink 配置。
     *
     * @param map Sink YAML Map
     * @return SinkConfig
     */
    static SinkConfig parseSink(Map<String, Object> map) {
        SinkConfig config = new SinkConfig();
        config.setName(str(map.get("name"), null));
        config.setType(SinkConfig.SinkType.valueOf(
                str(map.get("type"), "KAFKA").toUpperCase()));
        config.setHost(str(map.get("host"), "localhost"));
        config.setPort(intVal(map.get("port"), 9092));
        config.setTopic(str(map.get("topic"), null));
        config.setUsername(str(map.get("username"), null));
        config.setPassword(str(map.get("password"), null));
        config.setWriteMode(SinkConfig.WriteMode.fromCode(
                str(map.get("writeMode"), "upsert")));
        config.setPrimaryKey(str(map.get("primaryKey"), null));
        config.setFormat(str(map.get("format"), "debezium-json"));
        config.setParallelism(intVal(map.get("parallelism"), 1));

        Object propsObj = map.get("properties");
        if (propsObj instanceof Map<?, ?> propsMap) {
            Map<String, String> props = new java.util.HashMap<>();
            for (Map.Entry<?, ?> entry : propsMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    props.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
            config.setProperties(props);
        }
        return config;
    }

    // ===== 类型转换辅助 =====

    private static String str(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static int intVal(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static long longVal(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static Long longValOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static boolean boolVal(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    // ===== getter / setter =====

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    public List<SourceConfig> getSources() {
        return Collections.unmodifiableList(sources);
    }

    public void setSources(List<SourceConfig> sources) {
        this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
    }

    public List<SinkConfig> getSinks() {
        return Collections.unmodifiableList(sinks);
    }

    public void setSinks(List<SinkConfig> sinks) {
        this.sinks = sinks == null ? new ArrayList<>() : new ArrayList<>(sinks);
    }

    @Override
    public String toString() {
        return "CdcYamlConfig{jobName='" + jobName + "', parallelism=" + parallelism
                + ", sources=" + sources.size() + ", sinks=" + sinks.size() + '}';
    }
}