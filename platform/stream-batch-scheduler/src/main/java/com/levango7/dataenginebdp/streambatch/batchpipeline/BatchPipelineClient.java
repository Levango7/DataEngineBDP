package com.levango7.dataenginebdp.streambatch.batchpipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * batch-pipeline API 客户端（提交批次 + 轮询状态）。
 *
 * <p>对接 platform/batch-pipeline 的 FastAPI 服务（data-quality 实体）：
 * <ul>
 *   <li>{@code POST /batches} — 提交批次（202，body {@code {"batch_id","config"}}；
 *       config 仅业务字段覆盖，tenant/run_dir/storage 由服务端按租户强制分区）</li>
 *   <li>{@code GET /batches/{batchId}} — 轮询状态（queued/running/success/failed）</li>
 * </ul>
 *
 * <p>鉴权：按请求签发 HS256 JWT（sub=batch-scheduler，tenantId claim，
 * role=admin），与服务端 JWT_SECRET 共享密钥；服务端 AUTH_MODE=jwt 时强制校验。
 *
 * <p>{@code realSubmitEnabled=false}（默认）为本地模拟：不发起 HTTP，
 * 提交即成功、状态直接 success——与 Spark/Flink 通道的模拟约定一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchPipelineClient {

    private static final long JWT_TTL_SECONDS = 300;

    private final BatchPipelineConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RestTemplate restTemplate() {
        // 必须设置超时：服务不可达时无超时会无限挂起（对齐 FlinkRestClient）
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeoutMs());
        factory.setReadTimeout(config.getReadTimeoutMs());
        return new RestTemplate(factory);
    }

    /**
     * 提交批次。
     *
     * @param batchId 批次 id（null/空则由服务端生成 api- 前缀随机 id）
     * @param tenantId 租户 id（写入 JWT tenantId claim，服务端据此分区）
     * @param configOverride config 业务字段覆盖（null 表示用服务端基础配置）
     * @return 提交结果
     */
    public BatchSubmitResult submitBatch(String batchId, String tenantId, Map<String, Object> configOverride) {
        if (batchId == null || batchId.isBlank()) {
            batchId = "sched-" + java.util.UUID.randomUUID().toString().substring(0, 12);
        }
        if (!config.isRealSubmitEnabled()) {
            log.info("batch-pipeline 模拟提交（realSubmitEnabled=false）: batchId={}, tenantId={}",
                    batchId, tenantId);
            return BatchSubmitResult.ok(batchId, tenantId);
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("batch_id", batchId);
            if (configOverride != null && !configOverride.isEmpty()) {
                body.put("config", configOverride);
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + mintJwt(tenantId, System.currentTimeMillis() / 1000));
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> resp = restTemplate().postForEntity(
                    config.getBaseUrl() + "/batches", entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                return BatchSubmitResult.fail("batch-pipeline 提交失败: HTTP " + resp.getStatusCode());
            }
            JsonNode root = objectMapper.readTree(resp.getBody());
            String acceptedId = root.path("batch_id").asText(batchId);
            String acceptedTenant = root.path("tenant_id").asText(tenantId);
            return BatchSubmitResult.ok(acceptedId, acceptedTenant);
        } catch (Exception e) {
            log.error("batch-pipeline 提交异常: batchId={}, tenantId={}", batchId, tenantId, e);
            return BatchSubmitResult.fail("batch-pipeline 提交异常: " + e.getMessage());
        }
    }

    /**
     * 查询批次状态。
     *
     * @param batchId 批次 id
     * @return 状态快照
     * @throws Exception HTTP 失败 / 响应不可解析 / 批次不存在（404）
     */
    public BatchStatusSnapshot getBatch(String batchId) throws Exception {
        if (!config.isRealSubmitEnabled()) {
            return BatchStatusSnapshot.builder().batchId(batchId).status("success").build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + mintJwt(config.getTenantId(),
                System.currentTimeMillis() / 1000));
        ResponseEntity<String> resp = restTemplate().getForEntity(
                config.getBaseUrl() + "/batches/" + batchId, String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("batch-pipeline 状态查询失败: HTTP " + resp.getStatusCode());
        }
        JsonNode root = objectMapper.readTree(resp.getBody());
        String status = root.path("status").asText("unknown");
        String error = root.has("error") && !root.get("error").isNull()
                ? root.get("error").asText() : null;
        return BatchStatusSnapshot.builder()
                .batchId(root.path("batch_id").asText(batchId))
                .status(status)
                .errorMessage(error)
                .build();
    }

    /**
     * 探活（GET /healthz，匿名可达）。
     *
     * @return {@code true} 表示服务健康
     */
    public boolean health() {
        try {
            ResponseEntity<String> resp = restTemplate()
                    .getForEntity(config.getBaseUrl() + "/healthz", String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 签发 HS256 JWT（与平台 jwt_auth 校验逻辑兼容，纯 JDK 实现，零第三方依赖）。
     *
     * @param tenantId 写入 token 的租户 claim（服务端据此裁决租户）
     * @param nowEpochSeconds 签发时间（秒）
     * @return JWT 字符串
     * @throws Exception jwt-secret 未配置或签名初始化失败
     */
    String mintJwt(String tenantId, long nowEpochSeconds) throws Exception {
        String secret = config.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "batch-pipeline jwt-secret 未配置（realSubmitEnabled=true 时必填，"
                            + "与 batch-pipeline 服务端 JWT_SECRET 一致）");
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "batch-scheduler");
        claims.put("tenantId", tenantId == null ? "" : tenantId);
        claims.put("role", "admin");
        claims.put("iat", nowEpochSeconds);
        claims.put("exp", nowEpochSeconds + JWT_TTL_SECONDS);
        String signingInput = b64Url(objectMapper.writeValueAsBytes(header)) + "."
                + b64Url(objectMapper.writeValueAsBytes(claims));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + b64Url(sig);
    }

    private static String b64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
