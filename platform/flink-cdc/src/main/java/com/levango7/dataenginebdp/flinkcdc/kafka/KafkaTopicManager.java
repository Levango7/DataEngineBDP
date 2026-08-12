package com.levango7.dataenginebdp.flinkcdc.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka Topic 管理器，封装 Topic 的创建/删除/列出/描述等管理操作。
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>创建 Topic：可指定分区数、副本数、清理策略、保留时长等配置</li>
 *   <li>删除 Topic：支持批量删除</li>
 *   <li>列出 Topic：返回集群中所有 Topic 名称</li>
 *   <li>描述 Topic：返回分区数、副本数、Leader 等元信息</li>
 *   <li>多租户隔离：通过 {@link TopicNamingStrategy} 生成隔离的 Topic 名称</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * KafkaTopicManager manager = KafkaTopicManager.builder()
 *     .bootstrapServers("127.0.0.1:9092")
 *     .defaultPartitions(3)
 *     .defaultReplicationFactor(1)
 *     .namingStrategy(TopicNamingStrategy.multiTenant("tenant-a"))
 *     .build();
 *
 * String topic = manager.createTopicForTable("shop", null, "orders");
 * manager.close();
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public final class KafkaTopicManager implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopicManager.class);

    /** 默认操作超时时间（秒）。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final AdminClient adminClient;
    private final int defaultPartitions;
    private final short defaultReplicationFactor;
    private final TopicNamingStrategy namingStrategy;
    private final int timeoutSeconds;

    /** 默认 Topic 配置（cleanup.policy、retention.ms 等）。 */
    private final Map<String, String> defaultTopicConfigs;

    private KafkaTopicManager(AdminClient adminClient, int defaultPartitions,
                              short defaultReplicationFactor,
                              TopicNamingStrategy namingStrategy,
                              int timeoutSeconds,
                              Map<String, String> defaultTopicConfigs) {
        this.adminClient = adminClient;
        this.defaultPartitions = defaultPartitions;
        this.defaultReplicationFactor = defaultReplicationFactor;
        this.namingStrategy = namingStrategy;
        this.timeoutSeconds = timeoutSeconds;
        this.defaultTopicConfigs = defaultTopicConfigs;
    }

    /**
     * 计算源表对应的 Topic 名称（不创建）。
     *
     * @param db     数据库名
     * @param schema schema 名；可为 {@code null}
     * @param table  表名
     * @return Topic 名称
     */
    public String topicNameFor(String db, String schema, String table) {
        return namingStrategy.topicName(db, schema, table);
    }

    /**
     * 为源表创建 Topic（若不存在）。
     *
     * @param db     数据库名
     * @param schema schema 名；可为 {@code null}
     * @param table  表名
     * @return Topic 名称
     */
    public String createTopicFor(String db, String schema, String table) {
        String topic = topicNameFor(db, schema, table);
        createTopic(topic, defaultPartitions, defaultReplicationFactor);
        return topic;
    }

    /**
     * 创建单个 Topic（若已存在则跳过）。
     *
     * @param topic           Topic 名称
     * @param partitions      分区数
     * @param replicationFactor 副本数
     */
    public void createTopic(String topic, int partitions, short replicationFactor) {
        TopicNamingStrategy.validate(topic);
        if (partitions <= 0) {
            throw new IllegalArgumentException("分区数必须为正: " + partitions);
        }
        if (replicationFactor <= 0) {
            throw new IllegalArgumentException("副本数必须为正: " + replicationFactor);
        }

        if (exists(topic)) {
            log.debug("Topic 已存在，跳过创建: {}", topic);
            return;
        }

        NewTopic newTopic = new NewTopic(topic, partitions, replicationFactor);
        if (defaultTopicConfigs != null && !defaultTopicConfigs.isEmpty()) {
            newTopic.configs(defaultTopicConfigs);
        }

        CreateTopicsResult result = adminClient.createTopics(Collections.singleton(newTopic));
        try {
            result.all().get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("创建 Topic: {} (partitions={}, replication={})", topic, partitions, replicationFactor);
        } catch (TimeoutException e) {
            throw new RuntimeException("创建 Topic 超时: " + topic, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause != null && cause.getClass().getSimpleName().contains("TopicExists")) {
                log.debug("Topic 已被并发创建: {}", topic);
            } else {
                throw new RuntimeException("创建 Topic 失败: " + topic, cause != null ? cause : e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("创建 Topic 被中断: " + topic, e);
        }
    }

    /**
     * 批量创建 Topic。
     *
     * @param topics Topic 名称集合
     */
    public void createTopics(Set<String> topics) {
        Objects.requireNonNull(topics, "topics 不能为 null");
        Set<NewTopic> newTopics = new HashSet<>();
        for (String topic : topics) {
            TopicNamingStrategy.validate(topic);
            NewTopic newTopic = new NewTopic(topic, defaultPartitions, defaultReplicationFactor);
            if (defaultTopicConfigs != null && !defaultTopicConfigs.isEmpty()) {
                newTopic.configs(defaultTopicConfigs);
            }
            newTopics.add(newTopic);
        }
        if (newTopics.isEmpty()) {
            return;
        }
        CreateTopicsResult result = adminClient.createTopics(newTopics);
        try {
            result.all().get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("批量创建 {} 个 Topic", newTopics.size());
        } catch (TimeoutException e) {
            throw new RuntimeException("批量创建 Topic 超时", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("批量创建 Topic 失败", e.getCause() != null ? e.getCause() : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("批量创建 Topic 被中断", e);
        }
    }

    /**
     * 删除单个 Topic。
     *
     * @param topic Topic 名称
     */
    public void deleteTopic(String topic) {
        TopicNamingStrategy.validate(topic);
        DeleteTopicsResult result = adminClient.deleteTopics(Collections.singleton(topic));
        try {
            result.all().get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("删除 Topic: {}", topic);
        } catch (TimeoutException e) {
            throw new RuntimeException("删除 Topic 超时: " + topic, e);
        } catch (ExecutionException e) {
            throw new RuntimeException("删除 Topic 失败: " + topic, e.getCause() != null ? e.getCause() : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("删除 Topic 被中断: " + topic, e);
        }
    }

    /**
     * 批量删除 Topic。
     *
     * @param topics Topic 名称集合
     */
    public void deleteTopics(Set<String> topics) {
        Objects.requireNonNull(topics, "topics 不能为 null");
        if (topics.isEmpty()) {
            return;
        }
        DeleteTopicsResult result = adminClient.deleteTopics(topics);
        try {
            result.all().get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("批量删除 {} 个 Topic", topics.size());
        } catch (TimeoutException e) {
            throw new RuntimeException("批量删除 Topic 超时", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("批量删除 Topic 失败", e.getCause() != null ? e.getCause() : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("批量删除 Topic 被中断", e);
        }
    }

    /**
     * 列出集群中所有 Topic 名称。
     *
     * @return Topic 名称集合
     */
    public Set<String> listTopics() {
        ListTopicsResult result = adminClient.listTopics();
        try {
            return result.names().get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("列出 Topic 超时", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("列出 Topic 失败", e.getCause() != null ? e.getCause() : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("列出 Topic 被中断", e);
        }
    }

    /**
     * 判断 Topic 是否存在。
     *
     * @param topic Topic 名称
     * @return 是否存在
     */
    public boolean exists(String topic) {
        TopicNamingStrategy.validate(topic);
        return listTopics().contains(topic);
    }

    /**
     * 描述 Topic（返回分区数、副本数等元信息）。
     *
     * @param topic Topic 名称
     * @return TopicDescription
     */
    public TopicDescription describeTopic(String topic) {
        TopicNamingStrategy.validate(topic);
        Map<String, KafkaFuture<TopicDescription>> futures =
                adminClient.describeTopics(Collections.singleton(topic)).topicNameValues();
        try {
            return futures.get(topic).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("描述 Topic 超时: " + topic, e);
        } catch (ExecutionException e) {
            throw new RuntimeException("描述 Topic 失败: " + topic, e.getCause() != null ? e.getCause() : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("描述 Topic 被中断: " + topic, e);
        }
    }

    /**
     * 描述多个 Topic。
     *
     * @param topics Topic 名称集合
     * @return Topic 名称 → TopicDescription 映射
     */
    public Map<String, TopicDescription> describeTopics(Collection<String> topics) {
        Objects.requireNonNull(topics, "topics 不能为 null");
        if (topics.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, KafkaFuture<TopicDescription>> futures =
                adminClient.describeTopics(topics).topicNameValues();
        Map<String, TopicDescription> result = new HashMap<>();
        for (Map.Entry<String, KafkaFuture<TopicDescription>> entry : futures.entrySet()) {
            try {
                result.put(entry.getKey(), entry.getValue().get(timeoutSeconds, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                throw new RuntimeException("描述 Topic 超时: " + entry.getKey(), e);
            } catch (ExecutionException e) {
                throw new RuntimeException("描述 Topic 失败: " + entry.getKey(),
                        e.getCause() != null ? e.getCause() : e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("描述 Topic 被中断: " + entry.getKey(), e);
            }
        }
        return result;
    }

    /**
     * 获取命名策略。
     *
     * @return 命名策略
     */
    public TopicNamingStrategy getNamingStrategy() {
        return namingStrategy;
    }

    /**
     * 获取默认分区数。
     *
     * @return 默认分区数
     */
    public int getDefaultPartitions() {
        return defaultPartitions;
    }

    /**
     * 获取默认副本数。
     *
     * @return 默认副本数
     */
    public short getDefaultReplicationFactor() {
        return defaultReplicationFactor;
    }

    @Override
    public void close() {
        if (adminClient != null) {
            adminClient.close(java.time.Duration.ofSeconds(timeoutSeconds));
            log.debug("KafkaTopicManager 已关闭");
        }
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
     * KafkaTopicManager 构造器。
     */
    public static final class Builder {
        private String bootstrapServers = "localhost:9092";
        private int defaultPartitions = 1;
        private short defaultReplicationFactor = 1;
        private TopicNamingStrategy namingStrategy = TopicNamingStrategy.defaultStrategy();
        private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        private final Map<String, String> defaultTopicConfigs = new HashMap<>();
        private AdminClient adminClient;

        /** Kafka bootstrap servers。 */
        public Builder bootstrapServers(String bootstrapServers) {
            this.bootstrapServers = Objects.requireNonNull(bootstrapServers);
            return this;
        }

        /** 默认分区数。 */
        public Builder defaultPartitions(int partitions) {
            if (partitions <= 0) {
                throw new IllegalArgumentException("分区数必须为正: " + partitions);
            }
            this.defaultPartitions = partitions;
            return this;
        }

        /** 默认副本数。 */
        public Builder defaultReplicationFactor(short replicationFactor) {
            if (replicationFactor <= 0) {
                throw new IllegalArgumentException("副本数必须为正: " + replicationFactor);
            }
            this.defaultReplicationFactor = replicationFactor;
            return this;
        }

        /** 命名策略。 */
        public Builder namingStrategy(TopicNamingStrategy strategy) {
            this.namingStrategy = Objects.requireNonNull(strategy);
            return this;
        }

        /** 操作超时时间（秒）。 */
        public Builder timeoutSeconds(int seconds) {
            if (seconds <= 0) {
                throw new IllegalArgumentException("超时时间必须为正: " + seconds);
            }
            this.timeoutSeconds = seconds;
            return this;
        }

        /** 添加默认 Topic 配置项（如 cleanup.policy、retention.ms）。 */
        public Builder topicConfig(String key, String value) {
            this.defaultTopicConfigs.put(key, value);
            return this;
        }

        /** 设置默认 Topic 配置（覆盖）。 */
        public Builder topicConfigs(Map<String, String> configs) {
            this.defaultTopicConfigs.clear();
            if (configs != null) {
                this.defaultTopicConfigs.putAll(configs);
            }
            return this;
        }

        /** 注入外部 AdminClient（用于测试）。 */
        Builder adminClient(AdminClient adminClient) {
            this.adminClient = adminClient;
            return this;
        }

        /** 构建 KafkaTopicManager。 */
        public KafkaTopicManager build() {
            AdminClient client = this.adminClient;
            if (client == null) {
                Properties props = new Properties();
                props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
                client = AdminClient.create(props);
            }
            return new KafkaTopicManager(client, defaultPartitions, defaultReplicationFactor,
                    namingStrategy, timeoutSeconds, new HashMap<>(defaultTopicConfigs));
        }
    }
}