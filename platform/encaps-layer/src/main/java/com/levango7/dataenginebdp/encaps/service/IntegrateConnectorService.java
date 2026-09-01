package com.levango7.dataenginebdp.encaps.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据集成连接器服务：返回 SeaTunnel 支持的 Source/Sink 连接器列表。
 *
 * <p>连接器元数据内置（与 SeaTunnel 2.3.x 内置插件对齐）。
 * 真实状态探测（2026-09-01 增强）：此前 status 全部写死 "connected"——
 * 前端展示的连接器"已连通"状态与实际集群无关。现支持按连接器探测地址
 * （环境变量 {@code CONNECTOR_PROBE_<NAME>}，如 {@code CONNECTOR_PROBE_MYSQL}）
 * 做 TCP 连通检查；开启探测后按结果返回 connected / unreachable，
 * 未配置探测地址的连接器保持元数据默认状态（pending_config 等）。</p>
 *
 * <p>探测结果缓存 60s（避免列表页每次点击都打探测）；探测超时 1s。</p>
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

    /** 探测结果缓存：connector name -> (状态, 探测时间戳)。 */
    private final Map<String, ProbeResult> probeCache = new ConcurrentHashMap<>();

    /** 探测总开关（默认关闭：未配置探测地址的部署保持元数据静态状态）。 */
    @Value("${app.connectors.probe-enabled:false}")
    private boolean probeEnabled;

    /** 探测缓存有效期（秒）。 */
    private static final long PROBE_CACHE_SECONDS = 60;

    /** 探测超时（毫秒）。 */
    private static final int PROBE_TIMEOUT_MS = 1000;

    /** 探测结果（状态 + 时间戳）。 */
    private record ProbeResult(String status, Instant at) {
    }

    /**
     * 列出全部连接器（Source + Sink 合并；probe-enabled 时状态为真实探测结果）。
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
        log.info("列出连接器: 共 {} 个（source={}, sink={}, probe={}）",
                all.size(), SOURCES.size(), SINKS.size(), probeEnabled ? "on" : "off");
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
        m.put("status", resolveStatus(c));
        m.put("probeConfigured", probeAddress(c) != null);
        return m;
    }

    /** 状态裁决：probe 开启且有配置地址 → 真实探测（带缓存）；否则元数据默认。 */
    private String resolveStatus(Map<String, String> c) {
        String probeAddr = probeAddress(c);
        if (!probeEnabled || probeAddr == null) {
            return c.get("status");
        }
        String name = c.get("name");
        ProbeResult cached = probeCache.get(name);
        if (cached != null && Duration.between(cached.at(), Instant.now()).getSeconds() < PROBE_CACHE_SECONDS) {
            return cached.status();
        }
        String result = probeTcp(probeAddr) ? "connected" : "unreachable";
        probeCache.put(name, new ProbeResult(result, Instant.now()));
        return result;
    }

    /** 从环境变量读探测地址：CONNECTOR_PROBE_<NAME>（如 CONNECTOR_PROBE_MYSQL=mysql:3306）。
     * protected：便于测试子类覆写注入（J17 无法注入 System.getenv）。 */
    protected String probeAddress(Map<String, String> c) {
        String envKey = "CONNECTOR_PROBE_" + c.get("name").toUpperCase().replace("-", "_");
        String addr = System.getenv(envKey);
        return (addr == null || addr.isBlank()) ? null : addr.trim();
    }

    /** TCP 连通检查（1s 超时）。 */
    private boolean probeTcp(String addr) {
        String host = addr;
        int port = 0;
        int colon = addr.lastIndexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(addr.substring(colon + 1));
                host = addr.substring(0, colon);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if (port <= 0) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            log.debug("连接器探测失败: {} -> {}", addr, e.getMessage());
            return false;
        }
    }
}
