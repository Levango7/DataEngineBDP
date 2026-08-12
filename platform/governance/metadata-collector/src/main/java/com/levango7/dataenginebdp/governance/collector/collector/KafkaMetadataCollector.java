package com.levango7.dataenginebdp.governance.collector.collector;

import com.levango7.dataenginebdp.governance.collector.model.CollectionResult;
import com.levango7.dataenginebdp.governance.collector.model.ColumnMetadata;
import com.levango7.dataenginebdp.governance.collector.model.MetadataSource;
import com.levango7.dataenginebdp.governance.collector.model.TableMetadata;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListConsumerGroupsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka 元数据采集器。
 *
 * <p>通过 {@link AdminClient} 连接 Kafka 集群，采集：
 * <ol>
 *   <li>Topic 列表</li>
 *   <li>分区数（{@link TopicDescription#partitions()}）</li>
 *   <li>副本数（每个分区的 {@code replicas()} 大小）</li>
 *   <li>消费者组列表（{@code listConsumerGroups}）</li>
 *   <li>消息速率（通过 JMX 或指标端点，本实现以分区数近似）</li>
 * </ol></p>
 *
 * <p>每个 Topic 映射为一张 {@link TableMetadata}：
 * <ul>
 *   <li>databaseName = "kafka"</li>
 *   <li>tableName = topic 名</li>
 *   <li>columns = [topic, partition, replicas, consumerGroups]</li>
 *   <li>properties 包含 partitions/replicationFactor/consumerGroupsCount</li>
 * </ul></p>
 *
 * <p>Bootstrap servers 格式：{@code host1:port1,host2:port2}，
 * 由 {@code source.url} 提供。</p>
 */
@Component
public class KafkaMetadataCollector implements MetadataCollector {

    private static final Logger log = LoggerFactory.getLogger(KafkaMetadataCollector.class);

    /** AdminClient 操作超时时间（秒） */
    private static final long TIMEOUT_SECONDS = 10;

    /** Kafka 默认 bootstrap 端口 */
    private static final int DEFAULT_PORT = 9092;

    @Override
    public String getType() {
        return MetadataSource.TYPE_KAFKA;
    }

    @Override
    public CollectionResult collect(MetadataSource source) {
        CollectionResult result = CollectionResult.success(source.getId(), source.getName(), getType());
        AdminClient adminClient = null;
        try {
            adminClient = createAdminClient(source);

            // 1. 列出所有 Topic
            ListTopicsOptions options = new ListTopicsOptions().listInternal(true);
            Collection<String> topicNames = adminClient.listTopics(options).names().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            result.setDatabaseCount(1); // Kafka 视为单一 "kafka" 数据库

            // 2. 获取每个 Topic 的描述信息
            Map<String, TopicDescription> descriptions = adminClient
                    .describeTopics(topicNames).allTopicNames().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 3. 列出消费者组
            List<String> consumerGroups = listConsumerGroups(adminClient);

            List<TableMetadata> tables = new ArrayList<>();
            for (Map.Entry<String, TopicDescription> entry : descriptions.entrySet()) {
                tables.add(toTableMetadata(entry.getKey(), entry.getValue(), consumerGroups));
            }
            result.setTables(tables);
            result.markFinished();
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return finishWithFailure(result, "interrupted while collecting Kafka metadata");
        } catch (TimeoutException e) {
            return finishWithFailure(result, "Kafka admin operation timeout: " + e.getMessage());
        } catch (ExecutionException | RuntimeException e) {
            log.error("Kafka collection failed for source {}: {}", source.getName(), e.getMessage(), e);
            return finishWithFailure(result, e.getMessage());
        } finally {
            closeQuietly(adminClient);
        }
    }

    @Override
    public boolean testConnection(MetadataSource source) {
        AdminClient adminClient = null;
        try {
            adminClient = createAdminClient(source);
            // 列出 Topic 验证连接
            adminClient.listTopics().names().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            log.warn("Kafka connection test failed for source {}: {}", source.getName(), e.getMessage());
            return false;
        } finally {
            closeQuietly(adminClient);
        }
    }

    /**
     * 创建 Kafka AdminClient。
     *
     * @param source 数据源
     * @return AdminClient
     */
    protected AdminClient createAdminClient(MetadataSource source) {
        Properties props = new Properties();
        props.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, resolveBootstrapServers(source));
        if (source.getUsername() != null && !source.getUsername().isEmpty()) {
            props.setProperty("sasl.mechanism", "PLAIN");
            props.setProperty("security.protocol", "SASL_PLAINTEXT");
            props.setProperty("sasl.jaas.config",
                    "org.apache.kafka.common.security.plain.PlainLoginModule required "
                            + "username=\"" + source.getUsername() + "\" "
                            + "password=\"" + (source.getPassword() == null ? "" : source.getPassword()) + "\";");
        }
        return AdminClient.create(props);
    }

    /**
     * 解析 bootstrap servers。
     *
     * @param source 数据源
     * @return host1:port1,host2:port2 形式
     */
    private String resolveBootstrapServers(MetadataSource source) {
        if (source.getUrl() == null || source.getUrl().isEmpty()) {
            return "localhost:" + DEFAULT_PORT;
        }
        return source.getUrl();
    }

    /**
     * 列出所有消费者组名。
     *
     * @param adminClient AdminClient
     * @return 消费者组 ID 列表
     */
    private List<String> listConsumerGroups(AdminClient adminClient)
            throws InterruptedException, ExecutionException, TimeoutException {
        ListConsumerGroupsResult result = adminClient.listConsumerGroups();
        Collection<ConsumerGroupListing> groups = result.all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        List<String> names = new ArrayList<>(groups.size());
        for (ConsumerGroupListing g : groups) {
            names.add(g.groupId());
        }
        return names;
    }

    /**
     * 将 Topic 描述转换为表元数据。
     *
     * @param topicName     Topic 名
     * @param description   Topic 描述
     * @param consumerGroups 消费者组列表
     * @return 表元数据
     */
    private TableMetadata toTableMetadata(String topicName, TopicDescription description,
                                          List<String> consumerGroups) {
        TableMetadata tm = new TableMetadata();
        tm.setDatabaseName("kafka");
        tm.setTableName(topicName);
        tm.setTableType("KAFKA_TOPIC");
        tm.setSourceType(getType());

        int partitionCount = description.partitions().size();
        // 副本数取第一个分区的副本数（同 Topic 各分区副本数通常一致）
        int replicationFactor = partitionCount > 0
                ? description.partitions().iterator().next().replicas().size()
                : 0;

        // 列：topic / partition / replicas / consumerGroups
        List<ColumnMetadata> columns = new ArrayList<>();
        columns.add(new ColumnMetadata("topic", "STRING", "Topic name", false, false, 1));
        columns.add(new ColumnMetadata("partition", "INT", "Partition id", false, false, 2));
        columns.add(new ColumnMetadata("replicas", "ARRAY<INT>", "Replica broker ids", false, false, 3));
        columns.add(new ColumnMetadata("offset", "BIGINT", "Current log-end offset", true, false, 4));
        columns.add(new ColumnMetadata("key", "BYTES", "Message key", true, false, 5));
        columns.add(new ColumnMetadata("value", "BYTES", "Message payload", false, false, 6));
        columns.add(new ColumnMetadata("timestamp", "BIGINT", "Message timestamp", false, false, 7));
        tm.setColumns(columns);

        // 属性
        Map<String, String> props = new HashMap<>();
        props.put("partitions", String.valueOf(partitionCount));
        props.put("replicationFactor", String.valueOf(replicationFactor));
        props.put("consumerGroupsCount", String.valueOf(consumerGroups.size()));
        props.put("isInternal", String.valueOf(description.isInternal()));
        tm.setProperties(props);

        tm.setRowCount((long) partitionCount); // 以分区数近似为行数
        return tm;
    }

    /**
     * 标记采集失败并完成结果。
     *
     * @param result       结果
     * @param errorMessage 错误信息
     * @return 失败结果
     */
    private CollectionResult finishWithFailure(CollectionResult result, String errorMessage) {
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        result.markFinished();
        return result;
    }

    /**
     * 安静关闭 AdminClient。
     *
     * @param adminClient AdminClient，可为 null
     */
    private void closeQuietly(AdminClient adminClient) {
        if (adminClient != null) {
            try {
                adminClient.close(java.time.Duration.ofSeconds(TIMEOUT_SECONDS));
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }
}