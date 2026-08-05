package com.shuqing.bigdata.governance.collector.collector;

import com.shuqing.bigdata.governance.collector.model.CollectionResult;
import com.shuqing.bigdata.governance.collector.model.MetadataSource;
import com.shuqing.bigdata.governance.collector.model.TableMetadata;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link KafkaMetadataCollector} 单元测试。
 *
 * <p>使用 Mockito mock {@link AdminClient} 与 {@link KafkaFuture}，
 * 避免真实 Kafka 集群连接。通过子类化覆盖
 * {@link KafkaMetadataCollector#createAdminClient} 注入 mock。</p>
 */
class KafkaMetadataCollectorTest {

    private TestableKafkaMetadataCollector collector;

    @BeforeEach
    void setUp() {
        collector = new TestableKafkaMetadataCollector();
    }

    @Test
    @DisplayName("getType 应返回 KAFKA")
    void getType_shouldReturnKafka() {
        assertEquals(MetadataSource.TYPE_KAFKA, collector.getType());
    }

    @Test
    @DisplayName("collect 成功路径：应返回 Topic 元数据")
    void collect_successPath() throws Exception {
        MetadataSource source = new MetadataSource();
        source.setId(1L);
        source.setName("kafka-test");
        source.setType(MetadataSource.TYPE_KAFKA);
        source.setUrl("localhost:9092");

        AdminClient mockClient = mock(AdminClient.class);

        // listTopics
        ListTopicsResult topicsResult = mock(ListTopicsResult.class);
        KafkaFuture<Set<String>> topicsFuture = mock(KafkaFuture.class);
        when(topicsFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(Set.of("topic-1", "topic-2"));
        when(topicsResult.names()).thenReturn(topicsFuture);
        when(mockClient.listTopics(any())).thenReturn(topicsResult);

        // describeTopics
        DescribeTopicsResult descResult = mock(DescribeTopicsResult.class);
        KafkaFuture<Map<String, TopicDescription>> descFuture = mock(KafkaFuture.class);
        Map<String, TopicDescription> descMap = new HashMap<>();
        descMap.put("topic-1", makeDescription("topic-1", 3, 2));
        descMap.put("topic-2", makeDescription("topic-2", 1, 1));
        when(descFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(descMap);
        when(descResult.allTopicNames()).thenReturn(descFuture);
        when(mockClient.describeTopics(any(Collection.class))).thenReturn(descResult);

        // listConsumerGroups
        ListConsumerGroupsResult groupsResult = mock(ListConsumerGroupsResult.class);
        KafkaFuture<Collection<ConsumerGroupListing>> groupsFuture = mock(KafkaFuture.class);
        when(groupsFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(
                List.of(new ConsumerGroupListing("cg-1", false, Optional.empty())));
        when(groupsResult.all()).thenReturn(groupsFuture);
        when(mockClient.listConsumerGroups()).thenReturn(groupsResult);

        collector.setMockClient(mockClient);

        CollectionResult result = collector.collect(source);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getDatabaseCount());
        assertNotNull(result.getTables());
        assertEquals(2, result.getTables().size());

        // 验证表元数据
        TableMetadata tm = result.getTables().stream()
                .filter(t -> "topic-1".equals(t.getTableName()))
                .findFirst()
                .orElseThrow();
        assertEquals("kafka", tm.getDatabaseName());
        assertEquals("KAFKA_TOPIC", tm.getTableType());
        assertNotNull(tm.getProperties());
        assertEquals("3", tm.getProperties().get("partitions"));
        assertEquals("2", tm.getProperties().get("replicationFactor"));
        assertEquals("1", tm.getProperties().get("consumerGroupsCount"));
    }

    @Test
    @DisplayName("collect 失败路径：Kafka 异常应返回 success=false")
    void collect_failurePath() throws Exception {
        MetadataSource source = new MetadataSource();
        source.setId(2L);
        source.setName("kafka-down");
        source.setType(MetadataSource.TYPE_KAFKA);
        source.setUrl("unreachable:9092");

        AdminClient mockClient = mock(AdminClient.class);
        ListTopicsResult topicsResult = mock(ListTopicsResult.class);
        KafkaFuture<Set<String>> topicsFuture = mock(KafkaFuture.class);
        when(topicsFuture.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new java.util.concurrent.ExecutionException("broker unavailable",
                        new RuntimeException("broker unavailable")));
        when(topicsResult.names()).thenReturn(topicsFuture);
        when(mockClient.listTopics(any())).thenReturn(topicsResult);

        collector.setMockClient(mockClient);

        CollectionResult result = collector.collect(source);
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    @DisplayName("testConnection 连接成功应返回 true")
    void testConnection_success() throws Exception {
        MetadataSource source = new MetadataSource();
        source.setId(1L);
        source.setName("kafka-ok");
        source.setType(MetadataSource.TYPE_KAFKA);
        source.setUrl("localhost:9092");

        AdminClient mockClient = mock(AdminClient.class);
        ListTopicsResult topicsResult = mock(ListTopicsResult.class);
        KafkaFuture<Set<String>> topicsFuture = mock(KafkaFuture.class);
        when(topicsFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(Collections.emptySet());
        when(topicsResult.names()).thenReturn(topicsFuture);
        when(mockClient.listTopics()).thenReturn(topicsResult);

        collector.setMockClient(mockClient);

        assertTrue(collector.testConnection(source));
    }

    @Test
    @DisplayName("testConnection 连接失败应返回 false")
    void testConnection_failure() throws Exception {
        MetadataSource source = new MetadataSource();
        source.setId(1L);
        source.setName("kafka-bad");
        source.setType(MetadataSource.TYPE_KAFKA);
        source.setUrl("unreachable:9092");

        AdminClient mockClient = mock(AdminClient.class);
        ListTopicsResult topicsResult = mock(ListTopicsResult.class);
        KafkaFuture<Set<String>> topicsFuture = mock(KafkaFuture.class);
        when(topicsFuture.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new java.util.concurrent.ExecutionException("refused",
                        new RuntimeException("refused")));
        when(topicsResult.names()).thenReturn(topicsFuture);
        when(mockClient.listTopics()).thenReturn(topicsResult);

        collector.setMockClient(mockClient);

        assertFalse(collector.testConnection(source));
    }

    /**
     * 构造一个 TopicDescription。
     *
     * @param name             Topic 名
     * @param partitionCount   分区数
     * @param replicationFactor 副本数
     * @return TopicDescription
     */
    private TopicDescription makeDescription(String name, int partitionCount, int replicationFactor) {
        List<TopicPartitionInfo> partitions = new ArrayList<>();
        for (int p = 0; p < partitionCount; p++) {
            List<Node> replicas = new ArrayList<>();
            for (int r = 0; r < replicationFactor; r++) {
                replicas.add(new Node(r, "broker" + r, 9092));
            }
            partitions.add(new TopicPartitionInfo(p, replicas.get(0), replicas, Collections.emptyList()));
        }
        return new TopicDescription(name, false, partitions);
    }

    /**
     * 可测试子类：覆盖 createAdminClient 返回 mock。
     */
    static class TestableKafkaMetadataCollector extends KafkaMetadataCollector {
        private AdminClient mockClient;

        void setMockClient(AdminClient client) {
            this.mockClient = client;
        }

        @Override
        protected AdminClient createAdminClient(MetadataSource source) {
            return mockClient;
        }
    }
}
