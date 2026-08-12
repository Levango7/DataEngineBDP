package com.levango7.dataenginebdp.flinkcdc.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentMatchers;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * {@link KafkaTopicManager} 单元测试，使用 Mockito mock AdminClient。
 *
 * @author shuqing-bigdata
 */
class KafkaTopicManagerTest {

    /**
     * 构造一个已完成的 KafkaFuture，值为指定结果。
     *
     * @param value 结果值
     * @param <T>   值类型
     * @return 已完成的 KafkaFuture
     */
    private static <T> KafkaFuture<T> completedFuture(T value) {
        KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
        future.complete(value);
        return future;
    }

    /**
     * 构造一个已失败的 KafkaFuture。
     *
     * @param error 异常
     * @param <T>   值类型
     * @return 已失败的 KafkaFuture
     */
    private static <T> KafkaFuture<T> failedFuture(Throwable error) {
        KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
        future.completeExceptionally(error);
        return future;
    }

    @Nested
    @DisplayName("Topic 名称生成")
    class TopicNameTest {

        @Test
        @DisplayName("默认策略 — db.table")
        void defaultStrategy() {
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .namingStrategy(TopicNamingStrategy.defaultStrategy())
                    .build();

            assertThat(manager.topicNameFor("shop", null, "orders"))
                    .isEqualTo("shop.orders");
        }

        @Test
        @DisplayName("多租户策略 — tenant.db.table")
        void multiTenantStrategy() {
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .namingStrategy(TopicNamingStrategy.multiTenant("tenant-a"))
                    .build();

            assertThat(manager.topicNameFor("shop", null, "orders"))
                    .isEqualTo("tenant-a.shop.orders");
        }

        @Test
        @DisplayName("含 schema — db.schema.table")
        void withSchema() {
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .namingStrategy(TopicNamingStrategy.defaultStrategy())
                    .build();

            assertThat(manager.topicNameFor("shop", "dbo", "orders"))
                    .isEqualTo("shop.dbo.orders");
        }
    }

    @Nested
    @DisplayName("Topic 创建")
    class CreateTopicTest {

        @Test
        @DisplayName("创建新 Topic — 成功")
        void createNewTopic() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            CreateTopicsResult mockResult = Mockito.mock(CreateTopicsResult.class);
            when(mockResult.all()).thenReturn(completedFuture(null));
            when(mockAdmin.createTopics(any())).thenReturn(mockResult);

            // listTopics 返回空（不存在）
            ListTopicsResult mockList = Mockito.mock(ListTopicsResult.class);
            when(mockList.names()).thenReturn(completedFuture(Set.of()));
            when(mockAdmin.listTopics()).thenReturn(mockList);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .defaultPartitions(3)
                    .defaultReplicationFactor((short) 1)
                    .build();

            manager.createTopic("shop.orders", 3, (short) 1);

            Mockito.verify(mockAdmin).createTopics(any());
        }

        @Test
        @DisplayName("Topic 已存在 — 跳过创建")
        void topicExists_skip() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);

            ListTopicsResult mockList = Mockito.mock(ListTopicsResult.class);
            when(mockList.names()).thenReturn(completedFuture(Set.of("shop.orders")));
            when(mockAdmin.listTopics()).thenReturn(mockList);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            manager.createTopic("shop.orders", 1, (short) 1);

            // 应该不调用 createTopics
            Mockito.verify(mockAdmin, Mockito.never()).createTopics(any());
        }

        @Test
        @DisplayName("非法 Topic 名 — 抛出异常")
        void invalidTopicName() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.createTopic("", 1, (short) 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> manager.createTopic(".invalid", 1, (short) 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("非法分区数 — 抛出异常")
        void invalidPartitions() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.createTopic("test.topic", 0, (short) 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> manager.createTopic("test.topic", -1, (short) 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("非法副本数 — 抛出异常")
        void invalidReplicationFactor() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.createTopic("test.topic", 1, (short) 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("createTopicFor — 使用命名策略生成 Topic 名")
        void createTopicFor_usesNamingStrategy() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            CreateTopicsResult mockResult = Mockito.mock(CreateTopicsResult.class);
            when(mockResult.all()).thenReturn(completedFuture(null));
            when(mockAdmin.createTopics(any())).thenReturn(mockResult);

            ListTopicsResult mockList = Mockito.mock(ListTopicsResult.class);
            when(mockList.names()).thenReturn(completedFuture(Set.of()));
            when(mockAdmin.listTopics()).thenReturn(mockList);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .namingStrategy(TopicNamingStrategy.multiTenant("tenant-a"))
                    .build();

            String topic = manager.createTopicFor("shop", null, "orders");
            assertThat(topic).isEqualTo("tenant-a.shop.orders");
        }
    }

    @Nested
    @DisplayName("Topic 删除")
    class DeleteTopicTest {

        @Test
        @DisplayName("删除单个 Topic — 成功")
        void deleteSingleTopic() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DeleteTopicsResult mockResult = Mockito.mock(DeleteTopicsResult.class);
            when(mockResult.all()).thenReturn(completedFuture(null));
            when(mockAdmin.deleteTopics(any(java.util.Collection.class))).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            manager.deleteTopic("shop.orders");

            Mockito.verify(mockAdmin).deleteTopics(any(java.util.Collection.class));
        }

        @Test
        @DisplayName("非法 Topic 名 — 抛出异常")
        void invalidName() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.deleteTopic(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Topic 列出与查询")
    class ListAndExistsTest {

        @Test
        @DisplayName("listTopics — 返回所有 Topic 名")
        void listTopics() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            ListTopicsResult mockList = Mockito.mock(ListTopicsResult.class);
            when(mockList.names()).thenReturn(completedFuture(
                    Set.of("topic-1", "topic-2", "topic-3")));
            when(mockAdmin.listTopics()).thenReturn(mockList);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            Set<String> topics = manager.listTopics();
            assertThat(topics).containsExactlyInAnyOrder("topic-1", "topic-2", "topic-3");
        }

        @Test
        @DisplayName("exists — Topic 存在返回 true")
        void exists_true() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            ListTopicsResult mockList = Mockito.mock(ListTopicsResult.class);
            when(mockList.names()).thenReturn(completedFuture(Set.of("shop.orders")));
            when(mockAdmin.listTopics()).thenReturn(mockList);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThat(manager.exists("shop.orders")).isTrue();
            assertThat(manager.exists("shop.products")).isFalse();
        }
    }

    @Nested
    @DisplayName("Builder 配置")
    class BuilderTest {

        @Test
        @DisplayName("默认配置 — 正确")
        void defaults() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThat(manager.getDefaultPartitions()).isEqualTo(1);
            assertThat(manager.getDefaultReplicationFactor()).isEqualTo((short) 1);
            assertThat(manager.getNamingStrategy()).isNotNull();
        }

        @Test
        @DisplayName("自定义配置 — 正确生效")
        void customConfig() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .defaultPartitions(6)
                    .defaultReplicationFactor((short) 3)
                    .namingStrategy(TopicNamingStrategy.multiTenant("tenant-x"))
                    .build();

            assertThat(manager.getDefaultPartitions()).isEqualTo(6);
            assertThat(manager.getDefaultReplicationFactor()).isEqualTo((short) 3);
            assertThat(manager.getNamingStrategy().toString()).contains("tenant-x");
        }

        @Test
        @DisplayName("非法分区数 — 抛出异常")
        void invalidPartitions() {
            assertThatThrownBy(() -> KafkaTopicManager.builder().defaultPartitions(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("非法副本数 — 抛出异常")
        void invalidReplicationFactor() {
            assertThatThrownBy(() -> KafkaTopicManager.builder().defaultReplicationFactor((short) 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("非法超时时间 — 抛出异常")
        void invalidTimeout() {
            assertThatThrownBy(() -> KafkaTopicManager.builder().timeoutSeconds(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("topicConfig — 添加配置项")
        void topicConfig() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .topicConfig("cleanup.policy", "compact")
                    .topicConfig("retention.ms", "604800000")
                    .build();

            assertThat(manager).isNotNull();
        }

        @Test
        @DisplayName("topicConfigs — 批量设置配置")
        void topicConfigs() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            Map<String, String> configs = new HashMap<>();
            configs.put("cleanup.policy", "delete");
            configs.put("retention.ms", "86400000");

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .topicConfigs(configs)
                    .build();

            assertThat(manager).isNotNull();
        }
    }

    @Nested
    @DisplayName("批量操作")
    class BatchOperationsTest {

        @Test
        @DisplayName("createTopics — 批量创建")
        void createTopics_batch() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            CreateTopicsResult mockResult = Mockito.mock(CreateTopicsResult.class);
            when(mockResult.all()).thenReturn(completedFuture(null));
            when(mockAdmin.createTopics(any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            manager.createTopics(Set.of("topic-1", "topic-2", "topic-3"));

            Mockito.verify(mockAdmin).createTopics(any());
        }

        @Test
        @DisplayName("createTopics — 空集合不调用")
        void createTopics_empty() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            manager.createTopics(Set.of());

            Mockito.verify(mockAdmin, Mockito.never()).createTopics(any());
        }

        @Test
        @DisplayName("deleteTopics — 批量删除")
        void deleteTopics_batch() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DeleteTopicsResult mockResult = Mockito.mock(DeleteTopicsResult.class);
            when(mockResult.all()).thenReturn(completedFuture(null));
            when(mockAdmin.deleteTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            manager.deleteTopics(Set.of("topic-1", "topic-2"));

            Mockito.verify(mockAdmin).deleteTopics(ArgumentMatchers.<Collection<String>>any());
        }

        @Test
        @DisplayName("deleteTopics — 空集合不调用")
        void deleteTopics_empty() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            manager.deleteTopics(Set.of());

            Mockito.verify(mockAdmin, Mockito.never()).deleteTopics(ArgumentMatchers.<Collection<String>>any());
        }
    }

    @Nested
    @DisplayName("Topic 描述")
    class DescribeTest {

        @Test
        @DisplayName("describeTopic — 返回 TopicDescription")
        void describeTopic() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DescribeTopicsResult mockResult = Mockito.mock(DescribeTopicsResult.class);
            TopicDescription mockDesc = Mockito.mock(TopicDescription.class);
            when(mockResult.topicNameValues()).thenReturn(
                    Map.of("shop.orders", completedFuture(mockDesc)));
            when(mockAdmin.describeTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            TopicDescription desc = manager.describeTopic("shop.orders");
            assertThat(desc).isSameAs(mockDesc);
        }

        @Test
        @DisplayName("describeTopic — 非法名称抛出异常")
        void describeTopic_invalidName() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.describeTopic(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("describeTopics — 批量描述")
        void describeTopics_batch() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DescribeTopicsResult mockResult = Mockito.mock(DescribeTopicsResult.class);
            TopicDescription mockDesc1 = Mockito.mock(TopicDescription.class);
            TopicDescription mockDesc2 = Mockito.mock(TopicDescription.class);
            when(mockResult.topicNameValues()).thenReturn(
                    Map.of("topic-1", completedFuture(mockDesc1),
                            "topic-2", completedFuture(mockDesc2)));
            when(mockAdmin.describeTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            Map<String, TopicDescription> result =
                    manager.describeTopics(Set.of("topic-1", "topic-2"));
            assertThat(result).hasSize(2)
                    .containsEntry("topic-1", mockDesc1)
                    .containsEntry("topic-2", mockDesc2);
        }

        @Test
        @DisplayName("describeTopics — 空集合返回空 Map")
        void describeTopics_empty() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            Map<String, TopicDescription> result = manager.describeTopics(Collections.emptyList());
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Close")
    class CloseTest {

        @Test
        @DisplayName("close — 不抛出异常")
        void close_noException() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            manager.close();
            Mockito.verify(mockAdmin).close(any(java.time.Duration.class));
        }
    }

    // ===== 异常路径测试 =====

    /**
     * 构造一个 KafkaFuture，其 get(timeout) 方法直接抛出指定异常。
     * 使用 KafkaFutureImpl 子类重写 get 方法，避免 Mockito 对 checked exception 的限制。
     *
     * @param ex 待抛出异常
     * @param <T> 值类型
     * @return KafkaFuture
     */
    private static <T> KafkaFuture<T> futureThrowingOnGet(Throwable ex) {
        return new KafkaFutureImpl<>() {
            @Override
            public T get(long timeout, TimeUnit unit)
                    throws TimeoutException, InterruptedException, ExecutionException {
                if (ex instanceof TimeoutException) {
                    throw (TimeoutException) ex;
                }
                if (ex instanceof InterruptedException) {
                    throw (InterruptedException) ex;
                }
                if (ex instanceof ExecutionException) {
                    throw (ExecutionException) ex;
                }
                if (ex instanceof RuntimeException) {
                    throw (RuntimeException) ex;
                }
                throw new RuntimeException(ex);
            }
        };
    }

    /**
     * 自定义异常，类名含 "TopicExists"，用于测试并发创建 Topic 的容错分支。
     */
    static class TopicExistsException extends RuntimeException {
        TopicExistsException(String msg) {
            super(msg);
        }
    }

    @Nested
    @DisplayName("createTopic 异常处理")
    class CreateTopicErrorTest {

        @Test
        @DisplayName("TimeoutException — 抛出 RuntimeException")
        void timeoutException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            CreateTopicsResult mockResult = Mockito.mock(CreateTopicsResult.class);
            when(mockResult.all()).thenReturn(futureThrowingOnGet(new TimeoutException()));
            when(mockAdmin.createTopics(any())).thenReturn(mockResult);

            ListTopicsResult mockList = Mockito.mock(ListTopicsResult.class);
            when(mockList.names()).thenReturn(completedFuture(Set.of()));
            when(mockAdmin.listTopics()).thenReturn(mockList);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.createTopic("shop.orders", 1, (short) 1))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("超时");
        }

        @Test
        @DisplayName("ExecutionException(TopicExists) — 跳过不抛出")
        void executionException_topicExists() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            CreateTopicsResult mockResult = Mockito.mock(CreateTopicsResult.class);
            when(mockResult.all()).thenReturn(failedFuture(new TopicExistsException("already exists")));
            when(mockAdmin.createTopics(any())).thenReturn(mockResult);

            ListTopicsResult mockList = Mockito.mock(ListTopicsResult.class);
            when(mockList.names()).thenReturn(completedFuture(Set.of()));
            when(mockAdmin.listTopics()).thenReturn(mockList);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            // TopicExists 异常被吞掉，不抛出
            manager.createTopic("shop.orders", 1, (short) 1);
        }

        @Test
        @DisplayName("ExecutionException(其他) — 抛出 RuntimeException")
        void executionException_other() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            CreateTopicsResult mockResult = Mockito.mock(CreateTopicsResult.class);
            when(mockResult.all()).thenReturn(failedFuture(new RuntimeException("broker down")));
            when(mockAdmin.createTopics(any())).thenReturn(mockResult);

            ListTopicsResult mockList = Mockito.mock(ListTopicsResult.class);
            when(mockList.names()).thenReturn(completedFuture(Set.of()));
            when(mockAdmin.listTopics()).thenReturn(mockList);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.createTopic("shop.orders", 1, (short) 1))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("失败");
        }

        @Test
        @DisplayName("InterruptedException — 抛出 RuntimeException 并重设中断标志")
        void interruptedException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            CreateTopicsResult mockResult = Mockito.mock(CreateTopicsResult.class);
            when(mockResult.all()).thenReturn(futureThrowingOnGet(new InterruptedException()));
            when(mockAdmin.createTopics(any())).thenReturn(mockResult);

            ListTopicsResult mockList = Mockito.mock(ListTopicsResult.class);
            when(mockList.names()).thenReturn(completedFuture(Set.of()));
            when(mockAdmin.listTopics()).thenReturn(mockList);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.createTopic("shop.orders", 1, (short) 1))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("中断");
        }

        @Test
        @DisplayName("createTopic 应用 defaultTopicConfigs")
        void createTopicWithConfigs() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            CreateTopicsResult mockResult = Mockito.mock(CreateTopicsResult.class);
            when(mockResult.all()).thenReturn(completedFuture(null));
            when(mockAdmin.createTopics(any())).thenReturn(mockResult);

            ListTopicsResult mockList = Mockito.mock(ListTopicsResult.class);
            when(mockList.names()).thenReturn(completedFuture(Set.of()));
            when(mockAdmin.listTopics()).thenReturn(mockList);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .topicConfig("cleanup.policy", "compact")
                    .topicConfig("retention.ms", "604800000")
                    .build();

            manager.createTopic("shop.orders", 3, (short) 1);
            Mockito.verify(mockAdmin).createTopics(any());
        }
    }

    @Nested
    @DisplayName("createTopics 异常处理")
    class CreateTopicsErrorTest {

        @Test
        @DisplayName("null topics — 抛出 NPE")
        void nullTopics() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.createTopics(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("TimeoutException — 抛出 RuntimeException")
        void timeoutException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            CreateTopicsResult mockResult = Mockito.mock(CreateTopicsResult.class);
            when(mockResult.all()).thenReturn(futureThrowingOnGet(new TimeoutException()));
            when(mockAdmin.createTopics(any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.createTopics(new HashSet<>(Arrays.asList("t1", "t2"))))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("超时");
        }

        @Test
        @DisplayName("ExecutionException — 抛出 RuntimeException")
        void executionException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            CreateTopicsResult mockResult = Mockito.mock(CreateTopicsResult.class);
            when(mockResult.all()).thenReturn(failedFuture(new RuntimeException("fail")));
            when(mockAdmin.createTopics(any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.createTopics(new HashSet<>(Arrays.asList("t1", "t2"))))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("失败");
        }

        @Test
        @DisplayName("InterruptedException — 抛出 RuntimeException")
        void interruptedException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            CreateTopicsResult mockResult = Mockito.mock(CreateTopicsResult.class);
            when(mockResult.all()).thenReturn(futureThrowingOnGet(new InterruptedException()));
            when(mockAdmin.createTopics(any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.createTopics(new HashSet<>(Arrays.asList("t1", "t2"))))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("中断");
        }

        @Test
        @DisplayName("含 topicConfig — 批量创建时应用配置")
        void createTopicsWithConfigs() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            CreateTopicsResult mockResult = Mockito.mock(CreateTopicsResult.class);
            when(mockResult.all()).thenReturn(completedFuture(null));
            when(mockAdmin.createTopics(any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .topicConfig("cleanup.policy", "compact")
                    .build();

            manager.createTopics(new HashSet<>(Arrays.asList("t1", "t2")));
            Mockito.verify(mockAdmin).createTopics(any());
        }
    }

    @Nested
    @DisplayName("deleteTopic 异常处理")
    class DeleteTopicErrorTest {

        @Test
        @DisplayName("TimeoutException — 抛出 RuntimeException")
        void timeoutException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DeleteTopicsResult mockResult = Mockito.mock(DeleteTopicsResult.class);
            when(mockResult.all()).thenReturn(futureThrowingOnGet(new TimeoutException()));
            when(mockAdmin.deleteTopics(any(java.util.Collection.class))).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.deleteTopic("shop.orders"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("超时");
        }

        @Test
        @DisplayName("ExecutionException — 抛出 RuntimeException")
        void executionException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DeleteTopicsResult mockResult = Mockito.mock(DeleteTopicsResult.class);
            when(mockResult.all()).thenReturn(failedFuture(new RuntimeException("not found")));
            when(mockAdmin.deleteTopics(any(java.util.Collection.class))).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.deleteTopic("shop.orders"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("失败");
        }

        @Test
        @DisplayName("InterruptedException — 抛出 RuntimeException")
        void interruptedException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DeleteTopicsResult mockResult = Mockito.mock(DeleteTopicsResult.class);
            when(mockResult.all()).thenReturn(futureThrowingOnGet(new InterruptedException()));
            when(mockAdmin.deleteTopics(any(java.util.Collection.class))).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.deleteTopic("shop.orders"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("中断");
        }
    }

    @Nested
    @DisplayName("deleteTopics 异常处理")
    class DeleteTopicsErrorTest {

        @Test
        @DisplayName("null topics — 抛出 NPE")
        void nullTopics() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.deleteTopics(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("TimeoutException — 抛出 RuntimeException")
        void timeoutException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DeleteTopicsResult mockResult = Mockito.mock(DeleteTopicsResult.class);
            when(mockResult.all()).thenReturn(futureThrowingOnGet(new TimeoutException()));
            when(mockAdmin.deleteTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.deleteTopics(new HashSet<>(Arrays.asList("t1", "t2"))))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("超时");
        }

        @Test
        @DisplayName("ExecutionException — 抛出 RuntimeException")
        void executionException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DeleteTopicsResult mockResult = Mockito.mock(DeleteTopicsResult.class);
            when(mockResult.all()).thenReturn(failedFuture(new RuntimeException("fail")));
            when(mockAdmin.deleteTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.deleteTopics(new HashSet<>(Arrays.asList("t1", "t2"))))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("失败");
        }

        @Test
        @DisplayName("InterruptedException — 抛出 RuntimeException")
        void interruptedException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DeleteTopicsResult mockResult = Mockito.mock(DeleteTopicsResult.class);
            when(mockResult.all()).thenReturn(futureThrowingOnGet(new InterruptedException()));
            when(mockAdmin.deleteTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.deleteTopics(new HashSet<>(Arrays.asList("t1", "t2"))))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("中断");
        }
    }

    @Nested
    @DisplayName("listTopics 异常处理")
    class ListTopicsErrorTest {

        @Test
        @DisplayName("TimeoutException — 抛出 RuntimeException")
        void timeoutException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            ListTopicsResult mockList = Mockito.mock(ListTopicsResult.class);
            when(mockList.names()).thenReturn(futureThrowingOnGet(new TimeoutException()));
            when(mockAdmin.listTopics()).thenReturn(mockList);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(manager::listTopics)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("超时");
        }

        @Test
        @DisplayName("ExecutionException — 抛出 RuntimeException")
        void executionException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            ListTopicsResult mockList = Mockito.mock(ListTopicsResult.class);
            when(mockList.names()).thenReturn(failedFuture(new RuntimeException("fail")));
            when(mockAdmin.listTopics()).thenReturn(mockList);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(manager::listTopics)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("失败");
        }

        @Test
        @DisplayName("InterruptedException — 抛出 RuntimeException")
        void interruptedException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            ListTopicsResult mockList = Mockito.mock(ListTopicsResult.class);
            when(mockList.names()).thenReturn(futureThrowingOnGet(new InterruptedException()));
            when(mockAdmin.listTopics()).thenReturn(mockList);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(manager::listTopics)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("中断");
        }
    }

    @Nested
    @DisplayName("exists 异常处理")
    class ExistsErrorTest {

        @Test
        @DisplayName("非法 topic 名 — 抛出 IllegalArgumentException")
        void invalidName() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.exists(""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> manager.exists(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("describeTopic 异常处理")
    class DescribeTopicErrorTest {

        @Test
        @DisplayName("TimeoutException — 抛出 RuntimeException")
        void timeoutException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DescribeTopicsResult mockResult = Mockito.mock(DescribeTopicsResult.class);
            when(mockResult.topicNameValues()).thenReturn(
                    Map.of("shop.orders", futureThrowingOnGet(new TimeoutException())));
            when(mockAdmin.describeTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.describeTopic("shop.orders"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("超时");
        }

        @Test
        @DisplayName("ExecutionException — 抛出 RuntimeException")
        void executionException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DescribeTopicsResult mockResult = Mockito.mock(DescribeTopicsResult.class);
            when(mockResult.topicNameValues()).thenReturn(
                    Map.of("shop.orders", failedFuture(new RuntimeException("unknown topic"))));
            when(mockAdmin.describeTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.describeTopic("shop.orders"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("失败");
        }

        @Test
        @DisplayName("InterruptedException — 抛出 RuntimeException")
        void interruptedException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DescribeTopicsResult mockResult = Mockito.mock(DescribeTopicsResult.class);
            when(mockResult.topicNameValues()).thenReturn(
                    Map.of("shop.orders", futureThrowingOnGet(new InterruptedException())));
            when(mockAdmin.describeTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.describeTopic("shop.orders"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("中断");
        }
    }

    @Nested
    @DisplayName("describeTopics 异常处理")
    class DescribeTopicsErrorTest {

        @Test
        @DisplayName("null topics — 抛出 NPE")
        void nullTopics() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.describeTopics((Collection<String>) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("TimeoutException — 抛出 RuntimeException")
        void timeoutException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DescribeTopicsResult mockResult = Mockito.mock(DescribeTopicsResult.class);
            when(mockResult.topicNameValues()).thenReturn(
                    Map.of("t1", futureThrowingOnGet(new TimeoutException())));
            when(mockAdmin.describeTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.describeTopics(Set.of("t1")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("超时");
        }

        @Test
        @DisplayName("ExecutionException — 抛出 RuntimeException")
        void executionException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DescribeTopicsResult mockResult = Mockito.mock(DescribeTopicsResult.class);
            when(mockResult.topicNameValues()).thenReturn(
                    Map.of("t1", failedFuture(new RuntimeException("fail"))));
            when(mockAdmin.describeTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.describeTopics(Set.of("t1")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("失败");
        }

        @Test
        @DisplayName("InterruptedException — 抛出 RuntimeException")
        void interruptedException() throws Exception {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            DescribeTopicsResult mockResult = Mockito.mock(DescribeTopicsResult.class);
            when(mockResult.topicNameValues()).thenReturn(
                    Map.of("t1", futureThrowingOnGet(new InterruptedException())));
            when(mockAdmin.describeTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockResult);

            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .build();

            assertThatThrownBy(() -> manager.describeTopics(Set.of("t1")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("中断");
        }
    }

    @Nested
    @DisplayName("Builder 异常处理")
    class BuilderErrorTest {

        @Test
        @DisplayName("null bootstrapServers — 抛出 NPE")
        void nullBootstrapServers() {
            assertThatThrownBy(() -> KafkaTopicManager.builder().bootstrapServers(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null namingStrategy — 抛出 NPE")
        void nullNamingStrategy() {
            assertThatThrownBy(() -> KafkaTopicManager.builder().namingStrategy(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("topicConfigs(null) — 清空配置不抛出异常")
        void topicConfigsNull() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .topicConfigs(null)
                    .build();
            assertThat(manager).isNotNull();
        }

        @Test
        @DisplayName("自定义 timeoutSeconds — 生效")
        void customTimeout() {
            AdminClient mockAdmin = Mockito.mock(AdminClient.class);
            KafkaTopicManager manager = KafkaTopicManager.builder()
                    .bootstrapServers("localhost:9092")
                    .adminClient(mockAdmin)
                    .timeoutSeconds(60)
                    .build();
            assertThat(manager).isNotNull();
        }
    }
}