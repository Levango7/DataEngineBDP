package com.levango7.dataenginebdp.encaps.controller;

import com.levango7.dataenginebdp.encaps.model.ApiKeyEntity;
import com.levango7.dataenginebdp.encaps.repository.ApiKeyRepository;
import com.levango7.dataenginebdp.common.security.TenantContext;
import com.levango7.dataenginebdp.encaps.service.GatewayStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型网关端点（ROADMAP 前后端接线：前端 /gateway）。
 *
 * <p>提供 API Key 管理、路由配置与调用统计。
 * 统一前缀：{@code /api/v1/gateway}</p>
 *
 * <ul>
 *   <li>GET    /stats        — 网关统计（请求总数、成功率、延迟、活跃 Key 数）</li>
 *   <li>GET    /keys         — API 密钥列表（从数据库，租户隔离）</li>
 *   <li>POST   /keys         — 创建密钥（生成 apiKey+secret，secret 仅本次返回）</li>
 *   <li>PUT    /keys/{id}    — 更新密钥（名称、权限范围、状态）</li>
 *   <li>DELETE /keys/{id}    — 删除密钥</li>
 * </ul>
 *
 * <p>密钥安全：创建时返回明文 secret 一次性给前端展示；数据库只存 SHA-256 哈希，
 * 列表/详情接口返回 {@code ***} 掩码。</p>
 */
@Slf4j
@RestController
@Tag(name = "封装网关-大模型网关", description = "API Key管理与调用统计")
@RequiredArgsConstructor
@RequestMapping("/api/v1/gateway")
public class GatewayController {

    private final ApiKeyRepository repository;
    private final GatewayStatsService statsService;

    private static final SecureRandom RNG = new SecureRandom();

    /** 网关调用统计。 */
    @Operation(summary = "网关调用统计")
    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getStats() {
        String tenantId = TenantContext.getTenantId();
        log.info("获取网关统计: tenant={}", tenantId);
        long activeKeyCount = tenantId == null ? 0
                : repository.countByTenantIdAndStatus(tenantId, "enabled");
        return ResponseEntity.ok(statsService.getStats(activeKeyCount));
    }

    /** API 密钥列表。 */
    @Operation(summary = "API 密钥列表")
    @GetMapping("/keys")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> listApiKeys() {
        String tenantId = requireTenant();
        log.info("列出 API Key: tenant={}", tenantId);
        List<Map<String, Object>> view = repository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream().map(this::toView).toList();
        return ResponseEntity.ok(view);
    }

    /** 创建 Key 请求体（对齐前端 CreateApiKeyParams）。 */
    public record CreateKeyRequest(
            String name,
            String routeModel,
            Integer rateLimit,
            String scope) {
    }

    /** 创建 API Key。secret 仅本次响应返回，之后不再泄露。 */
    @Operation(summary = "创建 API Key。secret 仅本次响应返回，之后不再泄露")
    @PostMapping("/keys")
    @Transactional
    public ResponseEntity<Map<String, Object>> createApiKey(@RequestBody CreateKeyRequest req) {
        String tenantId = requireTenant();
        String apiKey = generateApiKey();
        String secret = generateSecret();
        ApiKeyEntity entity = ApiKeyEntity.builder()
                .name(req.name())
                .routeModel(req.routeModel())
                .rateLimit(req.rateLimit() != null ? req.rateLimit() : 100)
                .status("enabled")
                .apiKey(apiKey)
                .secretHash(sha256(secret))
                .scope(req.scope())
                .tenantId(tenantId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        ApiKeyEntity saved = repository.save(entity);
        log.info("创建 API Key: id={}, name={}, model={}, tenant={}",
                saved.getId(), saved.getName(), saved.getRouteModel(), tenantId);

        // 返回视图 + 一次性明文 secret
        Map<String, Object> view = toView(saved);
        view.put("apiKey", apiKey);
        view.put("secret", secret);
        view.put("secretShownOnce", true);
        return ResponseEntity.ok(view);
    }

    /** 更新 Key 请求体（对齐前端 UpdateApiKeyParams）。 */
    public record UpdateKeyRequest(
            String name,
            String routeModel,
            Integer rateLimit,
            String status,
            String scope) {
    }

    /** 更新 API Key。 */
    @Operation(summary = "更新 API Key")
    @PutMapping("/keys/{id}")
    @Transactional
    public ResponseEntity<?> updateApiKey(@PathVariable Long id,
                                          @RequestBody UpdateKeyRequest req) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            if (req.name() != null) {
                entity.setName(req.name());
            }
            if (req.routeModel() != null) {
                entity.setRouteModel(req.routeModel());
            }
            if (req.rateLimit() != null) {
                entity.setRateLimit(req.rateLimit());
            }
            if (req.status() != null) {
                entity.setStatus(req.status());
            }
            if (req.scope() != null) {
                entity.setScope(req.scope());
            }
            entity.setUpdatedAt(Instant.now());
            ApiKeyEntity saved = repository.save(entity);
            log.info("更新 API Key: id={}, tenant={}", id, tenantId);
            return ResponseEntity.ok((Object) toView(saved));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 删除 API Key。 */
    @Operation(summary = "删除 API Key")
    @DeleteMapping("/keys/{id}")
    @Transactional
    public ResponseEntity<?> deleteApiKey(@PathVariable Long id) {
        String tenantId = requireTenant();
        return repository.findByIdAndTenantId(id, tenantId).map(entity -> {
            repository.delete(entity);
            log.info("删除 API Key: id={}, tenant={}", id, tenantId);
            return ResponseEntity.ok(Map.of("deleted", true));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* ================================ 辅助方法 ================================ */

    private String requireTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("缺少租户上下文");
        }
        return tenantId;
    }

    /** 实体 → 前端视图（secret 永远掩码）。 */
    private Map<String, Object> toView(ApiKeyEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("name", e.getName());
        m.put("routeModel", e.getRouteModel());
        m.put("rateLimit", e.getRateLimit());
        m.put("status", e.getStatus());
        m.put("scope", e.getScope());
        m.put("apiKey", e.getApiKey());
        // secret 哈希不返回，前端用 *** 占位
        m.put("secret", "***");
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        m.put("updatedAt", e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return m;
    }

    /** 生成 apiKey（32 字节 Base64URL）。 */
    private String generateApiKey() {
        byte[] bytes = new byte[24];
        RNG.nextBytes(bytes);
        return "sk-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 生成 secret（48 字节 Base64URL）。 */
    private String generateSecret() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 哈希（十六进制）。 */
    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
