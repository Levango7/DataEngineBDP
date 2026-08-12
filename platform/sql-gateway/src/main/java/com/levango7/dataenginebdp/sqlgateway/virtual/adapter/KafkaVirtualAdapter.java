package com.levango7.dataenginebdp.sqlgateway.virtual.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.levango7.dataenginebdp.sqlgateway.virtual.ColumnDefinition;
import com.levango7.dataenginebdp.sqlgateway.virtual.VirtualTableDefinition;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Kafka 虚拟表查询适配器。
 *
 * <p>将 Kafka topic 视为虚拟表：每条消息映射为一行，消息 value（JSON）字段映射为列。
 * 连接配置 JSON 格式：</p>
 * <pre>{@code
 * {
 *   "bootstrapServers": "host:9092",
 *   "groupId": "vt-consumer-group",
 *   "topic": "orders",
 *   "valueFormat": "JSON",
 *   "keyColumn": "messageKey"
 * }
 * }</pre>
 *
 * <p>查询语义：</p>
 * <ul>
 *   <li>{@code getSchema}：返回虚拟表预定义列（Kafka 无原生 schema）；</li>
 *   <li>{@code query}：从 topic 拉取消息，按 JSON 字段拆分为列；</li>
 *   <li>{@code predicate}：当前不支持谓词下推，全量拉取后由网关层过滤。</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
@Component
public class KafkaVirtualAdapter implements VirtualAdapter {

    private static final Logger log = LoggerFactory.getLogger(KafkaVirtualAdapter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<ColumnDefinition> getSchema(VirtualTableDefinition definition) throws VirtualAdapterException {
        // Kafka 无原生 schema，直接返回虚拟表预定义列
        log.debug("获取 schema（Kafka 返回预定义列）table={}", definition.getTableName());
        if (definition.getColumns() == null || definition.getColumns().isEmpty()) {
            return List.of(
                    new ColumnDefinition("messageKey", "VARCHAR", true, "Kafka 消息 key"),
                    new ColumnDefinition("messageValue", "VARCHAR", true, "Kafka 消息 value"),
                    new ColumnDefinition("topic", "VARCHAR", false, "Topic 名"),
                    new ColumnDefinition("partition", "INTEGER", false, "分区号"),
                    new ColumnDefinition("offset", "BIGINT", false, "偏移量"),
                    new ColumnDefinition("timestamp", "TIMESTAMP", true, "消息时间戳")
            );
        }
        return definition.getColumns();
    }

    @Override
    public QueryResult query(VirtualTableDefinition definition, String predicate, Integer limit)
            throws VirtualAdapterException {
        log.debug("Kafka 查询 table={} topic={} limit={}",
                definition.getTableName(), definition.getSourceObject(), limit);
        Map<String, Object> config = parseConfig(definition);
        String bootstrapServers = (String) config.get("bootstrapServers");
        String groupId = (String) config.getOrDefault("groupId", "virtual-table-" + definition.getTableName());
        String topic = definition.getSourceObject();
        int maxRows = limit != null && limit > 0 ? limit : 1000;

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxRows);

        List<String> columns = new ArrayList<>();
        columns.add("messageKey");
        columns.add("messageValue");
        columns.add("topic");
        columns.add("partition");
        columns.add("offset");
        columns.add("timestamp");

        List<List<Object>> rows = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            int polled = 0;
            int emptyPolls = 0;
            while (polled < maxRows && emptyPolls < 3) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> record : records) {
                    List<Object> row = new ArrayList<>(6);
                    row.add(record.key());
                    row.add(record.value());
                    row.add(record.topic());
                    row.add(record.partition());
                    row.add(record.offset());
                    row.add(new java.sql.Timestamp(record.timestamp()));
                    rows.add(row);
                    polled++;
                    if (polled >= maxRows) {
                        break;
                    }
                }
            }
            consumer.commitSync();
        } catch (Exception e) {
            throw new VirtualAdapterException("KAFKA_QUERY_FAILED",
                    "Kafka 查询失败: " + e.getMessage(), e);
        }
        log.debug("Kafka 查询完成 table={} rows={}", definition.getTableName(), rows.size());
        return new QueryResult(columns, rows);
    }

    @Override
    public boolean testConnection(VirtualTableDefinition definition) {
        try {
            Map<String, Object> config = parseConfig(definition);
            String bootstrapServers = (String) config.get("bootstrapServers");
            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "vt-test-" + System.currentTimeMillis());
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                consumer.partitionsFor(definition.getSourceObject(), Duration.ofSeconds(5));
                return true;
            }
        } catch (Exception e) {
            log.warn("Kafka 连接测试失败 table={} err={}", definition.getTableName(), e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        log.debug("Kafka 适配器关闭（每次查询创建独立 consumer）");
    }

    private Map<String, Object> parseConfig(VirtualTableDefinition definition) {
        try {
            return objectMapper.readValue(definition.getConnectionConfig(),
                    new TypeReference<>() {});
        } catch (Exception e) {
            throw new VirtualAdapterException("CONFIG_PARSE_FAILED",
                    "连接配置解析失败: " + e.getMessage(), e);
        }
    }
}