package com.levango7.dataenginebdp.encaps.service.engine;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListConsumerGroupsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Kafka 管理服务。
 *
 * <p>基于 kafka-clients 的 {@link AdminClient} 和 {@link KafkaConsumer} 实现：
 * <ul>
 *   <li>Broker 列表（describeCluster）</li>
 *   <li>Topic 列表/创建/删除（listTopics/createTopics/deleteTopics）</li>
 *   <li>消费组列表（listConsumerGroups）</li>
 *   <li>消息采样（KafkaConsumer poll）</li>
 * </ul>
 * 集群连接失败时抛 {@link EngineUnavailableException}，由 Controller 转 503。</p>
 */
@Slf4j
@Service
public class KafkaAdminService {

    /** AdminClient 操作超时（秒） */
    private static final long OP_TIMEOUT_SECONDS = 10;

    /**
     * 列出 Broker 节点。
     *
     * @param bootstrapServers Kafka bootstrap servers
     * @return Broker 列表
     */
    public List<Map<String, Object>> listBrokers(String bootstrapServers) {
        try (AdminClient admin = createAdmin(bootstrapServers)) {
            var describe = admin.describeCluster();
            var nodes = describe.nodes().get(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String version = describe.clusterId().get(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<Map<String, Object>> result = new ArrayList<>();
            for (var node : nodes) {
                Map<String, Object> broker = new LinkedHashMap<>();
                broker.put("id", node.id());
                broker.put("host", node.host());
                broker.put("port", node.port());
                broker.put("version", version);
                broker.put("status", "alive");
                result.add(broker);
            }
            return result;
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineUnavailableException("Kafka 集群不可用: " + e.getMessage(), e);
        }
    }

    /**
     * 列出 Topic。
     *
     * @param bootstrapServers Kafka bootstrap servers
     * @return Topic 列表
     */
    public List<Map<String, Object>> listTopics(String bootstrapServers) {
        try (AdminClient admin = createAdmin(bootstrapServers)) {
            Collection<TopicListing> listings = admin.listTopics().listings()
                    .get(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<Map<String, Object>> result = new ArrayList<>();
            for (TopicListing t : listings) {
                Map<String, Object> topic = new LinkedHashMap<>();
                topic.put("name", t.name());
                topic.put("internal", t.isInternal());
                // 查询分区数和副本数
                try {
                    var desc = admin.describeTopics(Collections.singleton(t.name()))
                            .allTopicNames().get(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    var td = desc.get(t.name());
                    topic.put("partitions", td.partitions().size());
                    topic.put("replicas",
                            td.partitions().isEmpty() ? 0
                                    : td.partitions().get(0).replicas().size());
                } catch (Exception ignored) {
                    topic.put("partitions", 0);
                    topic.put("replicas", 0);
                }
                result.add(topic);
            }
            return result;
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineUnavailableException("Kafka 集群不可用: " + e.getMessage(), e);
        }
    }

    /**
     * 创建 Topic。
     *
     * @param bootstrapServers Kafka bootstrap servers
     * @param name             Topic 名
     * @param partitions       分区数
     * @param replicationFactor 副本因子
     * @return 创建结果
     */
    public Map<String, Object> createTopic(String bootstrapServers, String name,
                                           int partitions, int replicationFactor) {
        try (AdminClient admin = createAdmin(bootstrapServers)) {
            NewTopic newTopic = new NewTopic(name, partitions, (short) replicationFactor);
            admin.createTopics(Collections.singleton(newTopic))
                    .all().get(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("partitions", partitions);
            result.put("replicationFactor", replicationFactor);
            result.put("status", "CREATED");
            return result;
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineUnavailableException("创建 Topic 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除 Topic。
     *
     * @param bootstrapServers Kafka bootstrap servers
     * @param name             Topic 名
     */
    public void deleteTopic(String bootstrapServers, String name) {
        try (AdminClient admin = createAdmin(bootstrapServers)) {
            admin.deleteTopics(Collections.singleton(name))
                    .all().get(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineUnavailableException("删除 Topic 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 列出消费组。
     *
     * @param bootstrapServers Kafka bootstrap servers
     * @return 消费组列表
     */
    public List<Map<String, Object>> listConsumerGroups(String bootstrapServers) {
        try (AdminClient admin = createAdmin(bootstrapServers)) {
            ListConsumerGroupsResult lcg = admin.listConsumerGroups();
            Collection<ConsumerGroupListing> groups = lcg.all()
                    .get(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<Map<String, Object>> result = new ArrayList<>();
            for (ConsumerGroupListing g : groups) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("groupId", g.groupId());
                item.put("state", g.isSimpleConsumerGroup() ? "SIMPLE" : "COMPLEX");
                item.put("lag", 0); // lag 需要额外查询，简化为 0
                item.put("status", "STABLE");
                result.add(item);
            }
            return result;
        } catch (EngineUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineUnavailableException("Kafka 集群不可用: " + e.getMessage(), e);
        }
    }

    /**
     * 采样 Topic 消息（从最早开始拉取最多 max 条）。
     *
     * @param bootstrapServers Kafka bootstrap servers
     * @param topic            Topic 名
     * @param max              最大采样数
     * @return 消息列表
     */
    public List<Map<String, Object>> sampleMessages(String bootstrapServers, String topic, int max) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "engine-sample-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        List<Map<String, Object>> result = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            // 查询分区并 seek 到最早
            var parts = consumer.partitionsFor(topic);
            if (parts == null || parts.isEmpty()) {
                return result;
            }
            List<TopicPartition> tps = new ArrayList<>();
            for (var p : parts) {
                tps.add(new TopicPartition(topic, p.partition()));
            }
            consumer.assign(tps);
            consumer.seekToBeginning(tps);

            int collected = 0;
            long deadline = System.currentTimeMillis() + 5000; // 最多采样 5 秒
            while (collected < max && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("partition", r.partition());
                    msg.put("offset", r.offset());
                    msg.put("timestamp", r.timestamp());
                    msg.put("key", r.key());
                    msg.put("value", r.value());
                    result.add(msg);
                    if (++collected >= max) {
                        break;
                    }
                }
            }
            return result;
        } catch (Exception e) {
            throw new EngineUnavailableException("Kafka 消息采样失败: " + e.getMessage(), e);
        }
    }

    /** 创建 AdminClient */
    private AdminClient createAdmin(String bootstrapServers) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");
        return AdminClient.create(props);
    }
}