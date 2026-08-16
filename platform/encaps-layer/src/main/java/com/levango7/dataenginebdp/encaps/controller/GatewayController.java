package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 大模型网关端点（ROADMAP 前后端接线：前端 /gateway）。
 *
 * <p>提供 API Key 管理、路由配置与调用统计。
 * 统一前缀：{@code /api/v1/gateway}</p>
 *
 * <ul>
 *   <li>GET    /stats        — 网关统计</li>
 *   <li>GET    /keys         — API 密钥列表</li>
 *   <li>POST   /keys         — 创建密钥</li>
 *   <li>PUT    /keys/{id}    — 更新密钥</li>
 *   <li>DELETE /keys/{id}    — 删除密钥</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/gateway")
public class GatewayController {

    /** 内存存储（TODO: 替换为持久化 Repository）。 */
    private static final Map<String, Map<String, Object>> KEY_STORE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicLong ID_SEQ = new AtomicLong(0);

    /** 网关调用统计。 */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        // TODO: 接入网关真实调用统计
        log.info("获取网关统计: tenant={}", TenantContext.getTenantId());
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("todayCallCount", 0);
        stats.put("avgLatencyMs", 0);
        stats.put("successRate", 100.0);
        stats.put("activeKeyCount", KEY_STORE.size());
        return ResponseEntity.ok(stats);
    }

    /** API 密钥列表。 */
    @GetMapping("/keys")
    public ResponseEntity<List<Map<String, Object>>> listApiKeys() {
        log.info("列出 API Key: tenant={}", TenantContext.getTenantId());
        return ResponseEntity.ok(List.copyOf(KEY_STORE.values()));
    }

    /** 创建 Key 请求体（对齐前端 CreateApiKeyParams）。 */
    public record CreateKeyRequest(
            String name,
            String routeModel,
            Integer rateLimit) {
    }

    /** 创建 API Key。 */
    @PostMapping("/keys")
    public ResponseEntity<Map<String, Object>> createApiKey(@RequestBody CreateKeyRequest req) {
        String id = String.valueOf(ID_SEQ.incrementAndGet());
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("id", id);
        key.put("name", req.name());
        key.put("routeModel", req.routeModel());
        key.put("rateLimit", req.rateLimit() != null ? req.rateLimit() : 100);
        key.put("status", "enabled");
        key.put("createdAt", Instant.now().toString());
        KEY_STORE.put(id, key);
        log.info("创建 API Key: id={}, name={}, model={}, tenant={}",
                id, req.name(), req.routeModel(), TenantContext.getTenantId());
        return ResponseEntity.ok(key);
    }

    /** 更新 Key 请求体（对齐前端 UpdateApiKeyParams）。 */
    public record UpdateKeyRequest(
            String name,
            String routeModel,
            Integer rateLimit,
            String status) {
    }

    /** 更新 API Key。 */
    @PutMapping("/keys/{id}")
    public ResponseEntity<?> updateApiKey(@PathVariable String id,
                                          @RequestBody UpdateKeyRequest req) {
        Map<String, Object> key = KEY_STORE.get(id);
        if (key == null) {
            return ResponseEntity.notFound().build();
        }
        if (req.name() != null) {
            key.put("name", req.name());
        }
        if (req.routeModel() != null) {
            key.put("routeModel", req.routeModel());
        }
        if (req.rateLimit() != null) {
            key.put("rateLimit", req.rateLimit());
        }
        if (req.status() != null) {
            key.put("status", req.status());
        }
        log.info("更新 API Key: id={}, tenant={}", id, TenantContext.getTenantId());
        return ResponseEntity.ok(key);
    }

    /** 删除 API Key。 */
    @DeleteMapping("/keys/{id}")
    public ResponseEntity<Void> deleteApiKey(@PathVariable String id) {
        KEY_STORE.remove(id);
        log.info("删除 API Key: id={}, tenant={}", id, TenantContext.getTenantId());
        return ResponseEntity.ok().build();
    }
}