package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.DataSourceEntity;
import com.levango7.dataenginebdp.encaps.repository.DataSourceRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.engine.EngineUnavailableException;
import com.levango7.dataenginebdp.encaps.service.engine.KafkaAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
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
@PreAuthorize("isAuthenticated()")  // R13 安全修复：类级认证校验
public class KafkaController {

    private final KafkaAdminService kafkaAdminService;
    private final DataSourceRepository dataSourceRepository;

    /** Broker 列表。 */
    @Operation(summary = "Broker 列表")
    @GetMapping("/{clusterId}/brokers")
    public ResponseEntity<?> listBrokers(@PathVariable String clusterId) {
        String tenantId = requireTenant();
        log.info("列出 Kafka Broker: cluster={}, tenant={}", clusterId, tenantId);
        try {
            String bootstrap = resolveBootstrap(clusterId, tenantId);
            return ResponseEntity.ok(kafkaAdminService.listBrokers(bootstrap));
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** Topic 列表（租户隔离：仅返回当前租户前缀的 Topic）。 */
    @Operation(summary = "Topic 列表")
    @GetMapping("/{clusterId}/topics")
    public ResponseEntity<?> listTopics(@PathVariable String clusterId) {
        String tenantId = requireTenant();
        log.info("列出 Kafka Topic: cluster={}, tenant={}", clusterId, tenantId);
        try {
            String bootstrap = resolveBootstrap(clusterId, tenantId);
            String prefix = tenantTopicPrefix(tenantId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> allTopics = (List<Map<String, Object>>) kafkaAdminService.listTopics(bootstrap);
            // 租户隔离：仅返回当前租户前缀的 Topic
            List<Map<String, Object>> filtered = allTopics.stream()
                    .filter(t -> {
                        Object name = t.get("name");
                        return name != null && String.valueOf(name).startsWith(prefix);
                    })
                    .map(t -> {
                        // 剥离租户前缀后返回原始 Topic 名
                        Map<String, Object> copy = new java.util.LinkedHashMap<>(t);
                        copy.put("name", String.valueOf(t.get("name")).substring(prefix.length()));
                        return copy;
                    })
                    .toList();
            return ResponseEntity.ok(filtered);
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 创建 Topic 请求体。 */
    public record CreateTopicRequest(
            @NotBlank(message = "topic 名称不能为空") String name,
            @Min(value = 1, message = "分区数最小为 1") @Max(value = 1000, message = "分区数最大为 1000") Integer partitions,
            @Min(value = 1, message = "副本因子最小为 1") @Max(value = 10, message = "副本因子最大为 10") Integer replicationFactor) {
    }

    /** 创建 Topic（租户隔离：自动在 Topic 名前加租户前缀）。 */
    @Operation(summary = "创建 Topic")
    @PostMapping("/{clusterId}/topics")
    public ResponseEntity<?> createTopic(@PathVariable String clusterId,
                                          @Valid @RequestBody CreateTopicRequest req) {
        String tenantId = requireTenant();
        log.info("创建 Kafka Topic: cluster={}, name={}, tenant={}",
                clusterId, req.name(), tenantId);
        try {
            String bootstrap = resolveBootstrap(clusterId, tenantId);
            int partitions = req.partitions() != null ? req.partitions() : 1;
            int rf = req.replicationFactor() != null ? req.replicationFactor() : 1;
            // 租户隔离：在 Topic 名前加 tenant_{tenantId}_ 前缀
            String fullTopicName = tenantTopicPrefix(tenantId) + req.name();
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(kafkaAdminService.createTopic(bootstrap, fullTopicName, partitions, rf));
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 删除 Topic（租户隔离：自动加租户前缀，防跨租户删除）。 */
    @Operation(summary = "删除 Topic")
    @DeleteMapping("/{clusterId}/topics/{name}")
    public ResponseEntity<?> deleteTopic(@PathVariable String clusterId,
                                         @PathVariable String name) {
        String tenantId = requireTenant();
        log.info("删除 Kafka Topic: cluster={}, name={}, tenant={}",
                clusterId, name, tenantId);
        try {
            String bootstrap = resolveBootstrap(clusterId, tenantId);
            // 租户隔离：在 Topic 名前加 tenant_{tenantId}_ 前缀
            String fullTopicName = tenantTopicPrefix(tenantId) + name;
            kafkaAdminService.deleteTopic(bootstrap, fullTopicName);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 消费组列表（租户隔离：仅返回当前租户前缀的消费组）。 */
    @Operation(summary = "查询Kafka列表")
    @GetMapping("/{clusterId}/consumer-groups")
    public ResponseEntity<?> listConsumerGroups(@PathVariable String clusterId) {
        String tenantId = requireTenant();
        log.info("列出 Kafka 消费组: cluster={}, tenant={}", clusterId, tenantId);
        try {
            String bootstrap = resolveBootstrap(clusterId, tenantId);
            String prefix = tenantTopicPrefix(tenantId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> allGroups = (List<Map<String, Object>>) kafkaAdminService.listConsumerGroups(bootstrap);
            // 租户隔离：仅返回当前租户前缀的消费组
            List<Map<String, Object>> filtered = allGroups.stream()
                    .filter(g -> {
                        Object groupId = g.get("groupId");
                        return groupId != null && String.valueOf(groupId).startsWith(prefix);
                    })
                    .map(g -> {
                        // 剥离租户前缀后返回原始消费组名
                        Map<String, Object> copy = new java.util.LinkedHashMap<>(g);
                        copy.put("groupId", String.valueOf(g.get("groupId")).substring(prefix.length()));
                        return copy;
                    })
                    .toList();
            return ResponseEntity.ok(filtered);
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 查询 Topic 消息（任务要求，默认采样 100 条，租户隔离加前缀）。 */
    @Operation(summary = "查询 Topic 消息（任务要求，默认采样 100 条）")
    @GetMapping("/{clusterId}/topics/{topic}/messages")
    public ResponseEntity<?> listMessages(@PathVariable String clusterId,
                                          @PathVariable String topic,
                                          @RequestParam(defaultValue = "100") int max) {
        String tenantId = requireTenant();
        // P2-3: max 参数上限校验，防超大采样拖垮消费者
        int safeMax = Math.min(Math.max(max, 1), 10000);
        log.info("查询 Kafka 消息: cluster={}, topic={}, max={}, tenant={}",
                clusterId, topic, safeMax, tenantId);
        try {
            String bootstrap = resolveBootstrap(clusterId, tenantId);
            // 租户隔离：在 Topic 名前加 tenant_{tenantId}_ 前缀
            String fullTopicName = tenantTopicPrefix(tenantId) + topic;
            return ResponseEntity.ok(kafkaAdminService.sampleMessages(bootstrap, fullTopicName, safeMax));
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 消息采样请求体（前端用）。 */
    public record SampleRequest(Integer max) {
    }

    /** 消息采样（前端用，POST 方式，租户隔离加前缀）。 */
    @Operation(summary = "消息采样（前端用，POST 方式）")
    @PostMapping("/{clusterId}/topics/{topic}/sample")
    public ResponseEntity<?> sampleMessages(@PathVariable String clusterId,
                                            @PathVariable String topic,
                                            @RequestBody(required = false) SampleRequest req) {
        String tenantId = requireTenant();
        log.info("采样 Kafka 消息: cluster={}, topic={}, tenant={}",
                clusterId, topic, tenantId);
        try {
            String bootstrap = resolveBootstrap(clusterId, tenantId);
            // P3: sampleMessages max 上限校验（max≤1000）
            int max = (req != null && req.max() != null) ? Math.min(req.max(), 1000) : 100;
            max = Math.max(max, 1);
            // 租户隔离：在 Topic 名前加 tenant_{tenantId}_ 前缀
            String fullTopicName = tenantTopicPrefix(tenantId) + topic;
            return ResponseEntity.ok(kafkaAdminService.sampleMessages(bootstrap, fullTopicName, max));
        } catch (EngineUnavailableException e) {
            log.warn("Kafka 引擎不可用: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Kafka 引擎不可用", "message", e.getMessage()));
        }
    }

    /** 根据 clusterId 解析 bootstrap servers（tenantId 由调用方传入，避免重复调用 requireTenant） */
    private String resolveBootstrap(String clusterId, String tenantId) {
        // R12 安全修复：显式 fail-closed，不依赖 findByIdAndTenantId 返回 empty 的隐式行为
        // P3-10: clusterId 白名单校验（仅允许数字，防路径注入）
        Long id;
        try {
            id = Long.parseLong(clusterId);
        } catch (NumberFormatException e) {
            throw new EngineUnavailableException("无效的集群 ID");
        }
        DataSourceEntity ds = dataSourceRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new EngineUnavailableException("Kafka 集群不存在"));
        if (!"kafka".equalsIgnoreCase(ds.getType())) {
            throw new EngineUnavailableException("数据源不是 Kafka 类型");
        }
        return ds.getHost() + ":" + ds.getPort();
    }

    /**
     * 从 TenantContext 获取租户 ID，缺失则 fail-closed（R12 安全修复）。
     *
     * @return 当前请求的租户 ID
     * @throws IllegalStateException 若 TenantContext 未设置租户 ID
     */
    private static String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /**
     * 构造租户 Topic 名前缀（R17 租户隔离）。
     *
     * <p>多租户共享同一 Kafka 集群时，通过 Topic 名前缀实现隔离：
     * 每个租户的 Topic 名前自动加 {@code tenant_{tenantId}_} 前缀，
     * 防止跨租户访问/创建/删除 Topic。</p>
     *
     * @param tenantId 租户 ID
     * @return 形如 "tenant_{tenantId}_" 的前缀
     */
    private static String tenantTopicPrefix(String tenantId) {
        return "tenant_" + tenantId + "_";
    }

    /** 租户上下文缺失时返回 403（防跨租户越权信息泄露）。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        log.warn("操作被拒绝: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "message", e.getMessage()));
    }
}
