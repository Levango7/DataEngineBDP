package com.levango7.dataenginebdp.encaps.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 数据集成连接器服务：返回 SeaTunnel 支持的 Source/Sink 连接器列表。
 *
 * <p>连接器元数据内置（与 SeaTunnel 2.3.x 内置插件对齐），
 * 真实状态可后续扩展为从 SeaTunnel REST API 拉取。</p>
 */
@Slf4j
@Service
public class IntegrateConnectorService {

    /** 内置 Source 连接器（SeaTunnel Source 插件）。 */
    private static final List<Map<String, String>> SOURCES = List.of(
            Map.of("name", "MySQL", "logo", "MySQL", "type", "rdbms", "category", "source",
                    "plugin", "Jdbc", "status", "connected"),
            Map.of("name", "PostgreSQL", "logo", "PG", "type", "rdbms", "category", "source",
                    "plugin", "Jdbc", "status", "connected"),
            Map.of("name", "Oracle", "logo", "Oracle", "type", "rdbms", "category", "source",
                    "plugin", "Jdbc", "status", "connected"),
            Map.of("name", "Kafka", "logo", "Kafka", "type", "stream", "category", "source",
                    "plugin", "Kafka", "status", "connected"),
            Map.of("name", "Pulsar", "logo", "Pulsar", "type", "stream", "category", "source",
                    "plugin", "Pulsar", "status", "pending_config"),
            Map.of("name", "HDFS", "logo", "HDFS", "type", "fs", "category", "source",
                    "plugin", "HdfsFile", "status", "connected"),
            Map.of("name", "Hive", "logo", "Hive", "type", "warehouse", "category", "source",
                    "plugin", "Hive", "status", "connected")
    );

    /** 内置 Sink 连接器（SeaTunnel Sink 插件）。 */
    private static final List<Map<String, String>> SINKS = List.of(
            Map.of("name", "Iceberg", "logo", "Iceberg", "type", "lakehouse", "category", "sink",
                    "plugin", "Iceberg", "status", "connected"),
            Map.of("name", "Hudi", "logo", "Hudi", "type", "lakehouse", "category", "sink",
                    "plugin", "Hudi", "status", "pending_config"),
            Map.of("name", "Doris", "logo", "Doris", "type", "olap", "category", "sink",
                    "plugin", "Doris", "status", "connected"),
            Map.of("name", "StarRocks", "logo", "StarRocks", "type", "olap", "category", "sink",
                    "plugin", "StarRocks", "status", "connected"),
            Map.of("name", "Kafka", "logo", "Kafka", "type", "stream", "category", "sink",
                    "plugin", "Kafka", "status", "connected"),
            Map.of("name", "HDFS", "logo", "HDFS", "type", "fs", "category", "sink",
                    "plugin", "HdfsFile", "status", "connected"),
            Map.of("name", "Hive", "logo", "Hive", "type", "warehouse", "category", "sink",
                    "plugin", "Hive", "status", "connected")
    );

    /**
     * 列出全部连接器（Source + Sink 合并）。
     *
     * @return 连接器视图列表
     */
    public List<Map<String, Object>> listConnectors() {
        java.util.List<Map<String, Object>> all = new java.util.ArrayList<>();
        for (Map<String, String> c : SOURCES) {
            all.add(toView(c));
        }
        for (Map<String, String> c : SINKS) {
            all.add(toView(c));
        }
        log.info("列出连接器: 共 {} 个（source={}, sink={}）", all.size(), SOURCES.size(), SINKS.size());
        return all;
    }

    /**
     * 仅列出 Source 连接器。
     */
    public List<Map<String, Object>> listSources() {
        return SOURCES.stream().map(this::toView).toList();
    }

    /**
     * 仅列出 Sink 连接器。
     */
    public List<Map<String, Object>> listSinks() {
        return SINKS.stream().map(this::toView).toList();
    }

    private Map<String, Object> toView(Map<String, String> c) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", c.get("name"));
        m.put("logo", c.get("logo"));
        m.put("type", c.get("type"));
        m.put("category", c.get("category"));
        m.put("plugin", c.get("plugin"));
        m.put("status", c.get("status"));
        return m;
    }
}