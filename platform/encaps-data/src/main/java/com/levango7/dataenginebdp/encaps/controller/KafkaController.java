package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.DataSourceEntity;
import com.levango7.dataenginebdp.encaps.repository.DataSourceRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.engine.EngineUnavailableException;
import com.levango7.dataenginebdp.encaps.service.engine.KafkaAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * Kafka 引擎端点（ROADMAP 前后端接线：前端 /kafka）。
 *
 * <p>提供 Kafka Broker、Topic、消费组查询与 Topic 管理、消息采样。
 * 统一前缀：{@code /api/v1/kafka}</p>
 *
 * <p>clusterId 对应数据源表中 type=kafka 的记录 ID，从中读取 bootstrapServers（host:port）。</p>
 *
 * <ul>
 *   <li>GET    /{clusterId}/brothers                       — Broker 列表</li>
 *   <li>GET    /{clusterId}/topics                        — Topic 列表</li>
 *   <li>POST   /{clusterId}/topics                        — 创建 Topic</li>
 *   <li>DELETE /{clusterId}/topics/{name}                 — 删除 Topic</li>
 *   <li>GET    /{clusterId}/consumer-groups               — 消费组列表</li>
 *   <li>GET    /{clusterId}/topics/{topic}/messages       — 查询消息（任务要求）</li>
 *   <li>POST   /{clusterId}/topics/{topic}/sample         — 消息采样（前端用）</li>
 * </ul>
 */
@Slf4j
@RestController
@Tag(name = "封装数据-Kafka引擎", description = "Kafka Broker/Topic/消费组管理")
@RequiredArgsConstructor
@RequestMapping("/api/v1/kafka")
public class KafkaController {

    private final KafkaAdminService kafkaAdminService;
    private final DataSourceRepository dataSourceRepository;

    /** Broker 列表。 */
    @GetMapping("/{clusterId}/brokers")
    public ResponseEntity<?> listBrokers(@PathVariable String clusterId) {
        log.info("列出 Kafka Broker: cluster={}, tenant={}", clusterId, TenantContext.getTenantId());
        try {
            String bootstrap = resolveBootstrap(clusterId);
            return ResponseEntity.ok(kafkaAdminService.listBrokers(bootstrap));
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** Topic 列表。 */
    @GetMapping("/{clusterId}/topics")
    public ResponseEntity<?> listTopics(@PathVariable String clusterId) {
        log.info("列出 Kafka Topic: cluster={}, tenant={}", clusterId, TenantContext.getTenantId());
        try {
            String bootstrap = resolveBootstrap(clusterId);
            return ResponseEntity.ok(kafkaAdminService.listTopics(bootstrap));
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 创建 Topic 请求体。 */
    public record CreateTopicRequest(
            String name,
            Integer partitions,
            Integer replicationFactor) {
    }

    /** 创建 Topic。 */
    @PostMapping("/{clusterId}/topics")
    public ResponseEntity<?> createTopic(@PathVariable String clusterId,
                                         @RequestBody CreateTopicRequest req) {
        log.info("创建 Kafka Topic: cluster={}, name={}, tenant={}",
                clusterId, req.name(), TenantContext.getTenantId());
        try {
            String bootstrap = resolveBootstrap(clusterId);
            int partitions = req.partitions() != null ? req.partitions() : 1;
            int rf = req.replicationFactor() != null ? req.replicationFactor() : 1;
            return ResponseEntity.ok(kafkaAdminService.createTopic(bootstrap, req.name(), partitions, rf));
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 删除 Topic。 */
    @DeleteMapping("/{clusterId}/topics/{name}")
    public ResponseEntity<?> deleteTopic(@PathVariable String clusterId,
                                         @PathVariable String name) {
        log.info("删除 Kafka Topic: cluster={}, name={}, tenant={}",
                clusterId, name, TenantContext.getTenantId());
        try {
            String bootstrap = resolveBootstrap(clusterId);
            kafkaAdminService.deleteTopic(bootstrap, name);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 消费组列表。 */
    @GetMapping("/{clusterId}/consumer-groups")
    public ResponseEntity<?> listConsumerGroups(@PathVariable String clusterId) {
        log.info("列出 Kafka 消费组: cluster={}, tenant={}", clusterId, TenantContext.getTenantId());
        try {
            String bootstrap = resolveBootstrap(clusterId);
            return ResponseEntity.ok(kafkaAdminService.listConsumerGroups(bootstrap));
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 查询 Topic 消息（任务要求，默认采样 100 条）。 */
    @GetMapping("/{clusterId}/topics/{topic}/messages")
    public ResponseEntity<?> listMessages(@PathVariable String clusterId,
                                          @PathVariable String topic,
                                          @RequestParam(defaultValue = "100") int max) {
        log.info("查询 Kafka 消息: cluster={}, topic={}, max={}, tenant={}",
                clusterId, topic, max, TenantContext.getTenantId());
        try {
            String bootstrap = resolveBootstrap(clusterId);
            return ResponseEntity.ok(kafkaAdminService.sampleMessages(bootstrap, topic, max));
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 消息采样请求体（前端用）。 */
    public record SampleRequest(Integer max) {
    }

    /** 消息采样（前端用，POST 方式）。 */
    @PostMapping("/{clusterId}/topics/{topic}/sample")
    public ResponseEntity<?> sampleMessages(@PathVariable String clusterId,
                                            @PathVariable String topic,
                                            @RequestBody(required = false) SampleRequest req) {
        log.info("采样 Kafka 消息: cluster={}, topic={}, tenant={}",
                clusterId, topic, TenantContext.getTenantId());
        try {
            String bootstrap = resolveBootstrap(clusterId);
            int max = (req != null && req.max() != null) ? req.max() : 100;
            return ResponseEntity.ok(kafkaAdminService.sampleMessages(bootstrap, topic, max));
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 根据 clusterId 解析 bootstrap servers */
    private String resolveBootstrap(String clusterId) {
        String tenantId = TenantContext.getTenantId();
        Long id;
        try {
            id = Long.parseLong(clusterId);
        } catch (NumberFormatException e) {
            throw new EngineUnavailableException("无效的集群 ID: " + clusterId);
        }
        DataSourceEntity ds = dataSourceRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new EngineUnavailableException("Kafka 集群不存在: " + clusterId));
        if (!"kafka".equalsIgnoreCase(ds.getType())) {
            throw new EngineUnavailableException("数据源 " + clusterId + " 不是 Kafka 类型");
        }
        return ds.getHost() + ":" + ds.getPort();
    }
}
