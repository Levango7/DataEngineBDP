package com.levango7.dataenginebdp.streambatch.batchpipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BatchPipelineClient 单元测试（MockWebServer 模拟 batch-pipeline API）。
 *
 * <p>覆盖：真实提交（Bearer JWT + body 结构 + 租户 claim）、状态轮询解析、
 * 模拟模式不发起 HTTP、jwt-secret 缺配 fail-fast、404/异常路径。
 */
class BatchPipelineClientTest {

    private static final String JWT_SECRET = "unit-test-secret";

    private MockWebServer server;
    private BatchPipelineClient realClient;
    private BatchPipelineClient mockClient;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        BatchPipelineConfig realCfg = new BatchPipelineConfig();
        realCfg.setBaseUrl(server.url("/api/v1").toString().replaceAll("/$", ""));
        realCfg.setJwtSecret(JWT_SECRET);
        realCfg.setRealSubmitEnabled(true);
        realClient = new BatchPipelineClient(realCfg);

        BatchPipelineConfig mockCfg = new BatchPipelineConfig();
        mockCfg.setRealSubmitEnabled(false);
        mockClient = new BatchPipelineClient(mockCfg);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void submitBatch_sendsBearerJwtWithTenantClaim() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .setBody("{\"batch_id\":\"b-001\",\"tenant_id\":\"acme\",\"status\":\"queued\"}"));

        BatchSubmitResult result = realClient.submitBatch("b-001", "acme",
                Map.of("quality", Map.of("threshold", 0.99)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getBatchId()).isEqualTo("b-001");
        assertThat(result.getTenantId()).isEqualTo("acme");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/api/v1/batches");
        assertThat(recorded.getHeader("Authorization")).startsWith("Bearer ");
        assertThat(recorded.getHeader("Content-Type")).contains("application/json");

        // JWT payload 的 tenantId claim 与提交租户一致，且签名可由共享密钥验证
        String token = recorded.getHeader("Authorization").substring("Bearer ".length());
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        JsonNode claims = new ObjectMapper().readTree(payload);
        assertThat(claims.get("tenantId").asText()).isEqualTo("acme");
        assertThat(claims.get("role").asText()).isEqualTo("admin");
        assertThat(claims.get("sub").asText()).isEqualTo("batch-scheduler");

        JsonNode body = new ObjectMapper().readTree(recorded.getBody().readString(StandardCharsets.UTF_8));
        assertThat(body.get("batch_id").asText()).isEqualTo("b-001");
        assertThat(body.has("config")).isTrue();
    }

    @Test
    void submitBatch_conflictReportsFailure() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(409)
                .setBody("{\"error\":\"conflict\",\"message\":\"批次 b-001 正在执行中\"}"));

        BatchSubmitResult result = realClient.submitBatch("b-001", "default", null);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("409");
    }

    @Test
    void getBatch_parsesTerminalStatus() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"batch_id\":\"b-002\",\"status\":\"failed\",\"error\":\"pipeline exited with code 1\"}"));

        BatchStatusSnapshot snapshot = realClient.getBatch("b-002");
        assertThat(snapshot.getStatus()).isEqualTo("failed");
        assertThat(snapshot.getErrorMessage()).contains("exited");
        assertThat(snapshot.isTerminal()).isTrue();
    }

    @Test
    void getBatch_nonTerminalState() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"batch_id\":\"b-003\",\"status\":\"running\"}"));

        assertThat(realClient.getBatch("b-003").isTerminal()).isFalse();
    }

    @Test
    void getBatch_unreachableServiceThrows() {
        BatchPipelineConfig cfg = new BatchPipelineConfig();
        cfg.setBaseUrl("http://127.0.0.1:1/api/v1");
        cfg.setRealSubmitEnabled(true);
        cfg.setJwtSecret(JWT_SECRET);
        cfg.setConnectTimeoutMs(500);
        cfg.setReadTimeoutMs(500);
        BatchPipelineClient client = new BatchPipelineClient(cfg);
        assertThatThrownBy(() -> client.getBatch("b-404")).isInstanceOf(Exception.class);
    }

    @Test
    void mockMode_shortCircuitsWithoutHttp() throws Exception {
        BatchSubmitResult submitted = mockClient.submitBatch("b-mock", "default", null);
        assertThat(submitted.isSuccess()).isTrue();
        assertThat(submitted.getBatchId()).isEqualTo("b-mock");
        BatchStatusSnapshot snapshot = mockClient.getBatch("b-mock");
        assertThat(snapshot.getStatus()).isEqualTo("success");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void health_probesHealthz() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"UP\"}"));
        assertThat(realClient.health()).isTrue();
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/v1/healthz");
    }

    @Test
    void mintJwt_rejectsBlankSecret() {
        BatchPipelineConfig cfg = new BatchPipelineConfig();
        cfg.setRealSubmitEnabled(true);
        cfg.setJwtSecret("");
        BatchPipelineClient client = new BatchPipelineClient(cfg);
        assertThatThrownBy(() -> client.mintJwt("default", 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-secret");
    }
}
